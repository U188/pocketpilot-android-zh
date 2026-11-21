package com.example.data.opencode

import com.example.data.local.KeystoreSecretStore
import com.example.data.local.OpenCodeServerAuth
import com.example.data.sftp.SftpRepository
import com.example.data.ssh.SshConnectionManager
import com.example.domain.model.CustomProviderConfig
import com.example.domain.model.OpenCodeEnvInfo
import com.example.domain.model.OpenCodeServerStatus
import com.example.domain.model.OpenCodeVerificationReport
import com.example.domain.model.SystemdServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class SshExecResult(
    val exitCode: Int,
    val output: String,
)

data class ServiceRestartCheckResult(
    val serviceActive: Boolean,
    val isUserService: Boolean,
    val stateText: String,
    val detail: String,
)

data class ServerReadyCheckResult(
    val serviceActive: Boolean,
    val serverAlive: Boolean,
    val detail: String,
    val activeTunnelPort: Int?,
)

private data class SessionProbeResult(
    val alive: Boolean,
    val isConnectionError: Boolean,
    val detail: String,
    val httpCode: Int = 0,
)

class OpenCodeServerInstaller(
    private val connectionManager: SshConnectionManager,
    private val sftpRepository: SftpRepository,
    private val secretStore: KeystoreSecretStore? = null,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    val providerManager: OpenCodeProviderManager by lazy {
        OpenCodeProviderManager(
            connectionManager = connectionManager,
            sftpRepository = sftpRepository,
            serverInstaller = this,
        )
    }

    suspend fun detectServerState(
        profileId: Long?,
        openCodePort: Int,
        localTunnelPort: Int?,
    ): Pair<OpenCodeServerStatus, OpenCodeEnvInfo> = withContext(Dispatchers.IO) {
        val env = inspectEnvironment(openCodePort = openCodePort, requireSystemd = false, onLog = null)
        var auth = profileId?.let { secretStore?.getOpenCodeServerAuth(it) }
        var probe = if (localTunnelPort != null) {
            probeTunnelSessionJson(localTunnelPort, auth, treat401AsAlive = true)
        } else {
            null
        }

        // If OpenCode responds with HTTP 401 (e.g. after reinstall, profile recreation, or key mismatch),
        // automatically recover OPENCODE_SERVER_PASSWORD from the server's systemd env file over SSH/SFTP.
        if (probe?.httpCode == 401 && profileId != null) {
            val synced = syncOpenCodeServerAuthFromServer(
                profileId = profileId,
                homeDir = env.homeDir,
                isRoot = env.isRoot,
            )
            if (synced != null && localTunnelPort != null) {
                auth = synced
                probe = probeTunnelSessionJson(localTunnelPort, auth, treat401AsAlive = true)
            }
        }

        val tunnelSessionOk = probe?.alive == true
        val serviceActive = env.serviceState == SystemdServiceState.ACTIVE

        val status = when {
            // Require either active persistent service + reachable endpoint, or reachable endpoint when service is already running
            tunnelSessionOk && (serviceActive || env.opencodePath != null) && !env.isTransientService ->
                OpenCodeServerStatus.RUNNING

            !env.opencodePath.isNullOrBlank() ->
                OpenCodeServerStatus.INSTALLED_STOPPED

            else ->
                OpenCodeServerStatus.NOT_INSTALLED
        }
        status to env
    }

    /**
     * Reads OPENCODE_SERVER_USERNAME / OPENCODE_SERVER_PASSWORD from the connected SSH server's
     * systemd environment file (or unit file / process environ) and saves it to Android Keystore.
     * Never logs or returns secrets in diagnostic output.
     */
    suspend fun syncOpenCodeServerAuthFromServer(
        profileId: Long,
        homeDir: String? = null,
        isRoot: Boolean? = null,
    ): com.example.data.local.OpenCodeServerAuth? = withContext(Dispatchers.IO) {
        val cleanHome = homeDir?.trim()?.takeIf { it.isNotBlank() }
            ?: runCatching {
                execStreaming("bash -lc 'printf %s \"\$HOME\"'", timeoutSec = 8, onLine = null).output.trim()
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "/root"

        val effectiveRoot = isRoot ?: (cleanHome == "/root")

        val unitPaths = listOf(
            "/etc/systemd/system/opencode-serve.service",
            "$cleanHome/.config/systemd/user/opencode-serve.service",
        )
        val envPaths = linkedSetOf(
            "/etc/opencode-serve.env",
            "$cleanHome/.config/opencode/opencode-serve.env",
        )

        // Check systemd unit files first for custom EnvironmentFile= or inline Environment=
        var parsedAuth: Pair<String, String>? = null
        for (unitPath in unitPaths) {
            val unitText = readRemoteSmallFile(unitPath, allowSudo = !effectiveRoot) ?: continue
            OpenCodeConfigHelper.extractEnvironmentFilePaths(unitText).forEach { envPaths.add(it) }
            if (parsedAuth == null) {
                parsedAuth = OpenCodeConfigHelper.parseOpenCodeAuthFromEnvText(unitText)
            }
        }

        // Check candidate env files
        for (envPath in envPaths) {
            val envText = readRemoteSmallFile(envPath, allowSudo = !effectiveRoot) ?: continue
            val candidate = OpenCodeConfigHelper.parseOpenCodeAuthFromEnvText(envText)
            if (candidate != null) {
                parsedAuth = candidate
                break
            }
        }

        // Last resort: inspect running opencode-serve process environment (works when env was injected externally)
        if (parsedAuth == null) {
            val procCmd = buildString {
                val sudoPrefix = if (effectiveRoot) "" else "sudo -n "
                append("bash -lc '")
                append("PID=$(systemctl show -p MainPID --value opencode-serve 2>/dev/null || systemctl --user show -p MainPID --value opencode-serve 2>/dev/null || true); ")
                append("if [ -z \"\$PID\" ] || [ \"\$PID\" = \"0\" ]; then PID=$(pgrep -f \"opencode.*serve\" | head -n 1 || true); fi; ")
                append("if [ -n \"\$PID\" ] && [ -r \"/proc/\$PID/environ\" ]; then tr \"\\0\" \"\\n\" < \"/proc/\$PID/environ\" | grep -E \"^OPENCODE_SERVER_(USERNAME|PASSWORD)=\" || true; ")
                append("elif [ -n \"\$PID\" ] && [ \"$sudoPrefix\" != \"\" ]; then ${sudoPrefix}tr \"\\0\" \"\\n\" < \"/proc/\$PID/environ\" 2>/dev/null | grep -E \"^OPENCODE_SERVER_(USERNAME|PASSWORD)=\" || true; fi")
                append("'")
            }
            val procOut = runCatching {
                execRawSilent(procCmd, timeoutSec = 10).output
            }.getOrNull()
            parsedAuth = OpenCodeConfigHelper.parseOpenCodeAuthFromEnvText(procOut)
        }

        val (username, password) = parsedAuth ?: return@withContext null
        secretStore?.saveOpenCodeServerAuth(profileId, username, password)
        com.example.data.local.OpenCodeServerAuth(username = username, password = password)
    }

    fun saveManualOpenCodeServerAuth(
        profileId: Long,
        username: String,
        password: String,
    ) {
        secretStore?.saveOpenCodeServerAuth(
            profileId = profileId,
            username = username.trim().ifBlank { OpenCodeConfigHelper.DEFAULT_AUTH_USERNAME },
            password = password,
        )
    }

    private suspend fun readRemoteSmallFile(
        path: String,
        allowSudo: Boolean,
    ): String? {
        val viaSftp = runCatching {
            sftpRepository.readTextFile(path).text
        }.getOrNull()
        if (!viaSftp.isNullOrBlank()) return viaSftp

        if (allowSudo) {
            val res = runCatching {
                execRawSilent("sudo -n cat ${shellQuote(path)} 2>/dev/null", timeoutSec = 8)
            }.getOrNull()
            if (res != null && res.exitCode == 0 && res.output.isNotBlank()) {
                return res.output
            }
        }
        return null
    }

    /**
     * Executes a silent SSH command without emitting or sanitizing logs.
     * Used strictly for internal secret sync (never exposed to UI or logs).
     */
    private suspend fun execRawSilent(
        command: String,
        timeoutSec: Long,
    ): SshExecResult = withContext(Dispatchers.IO) {
        val client = connectionManager.getActiveSshClient()
            ?: throw IllegalStateException("SSH 连接未激活")

        val outputBuilder = StringBuilder()
        var exitCode = -1
        client.startSession().use { session ->
            val cmd = session.exec(command)
            val stdoutReader = BufferedReader(InputStreamReader(cmd.inputStream, StandardCharsets.UTF_8))
            var line: String? = stdoutReader.readLine()
            while (line != null) {
                outputBuilder.appendLine(line)
                line = stdoutReader.readLine()
            }
            cmd.join(timeoutSec, TimeUnit.SECONDS)
            exitCode = cmd.exitStatus ?: -1
        }
        SshExecResult(exitCode = exitCode, output = outputBuilder.toString())
    }

    suspend fun inspectEnvironment(
        openCodePort: Int,
        requireSystemd: Boolean = true,
        onLog: ((String) -> Unit)? = null,
    ): OpenCodeEnvInfo = withContext(Dispatchers.IO) {
        onLog?.invoke("$ 正在检查服务器环境（操作系统、架构、curl、systemd、权限和磁盘空间）…")
        val script = """
            bash -lc '
            echo "OS=$(uname -s)"
            echo "ARCH=$(uname -m)"
            echo "USER=$(id -un)"
            echo "UID=$(id -u)"
            echo "HOME=${'$'}HOME"
            FREE_MB=$(df -Pm "${'$'}HOME" 2>/dev/null | tail -n 1 | tr -s " " | cut -d" " -f4 || echo "-1")
            echo "FREE_MB=${'$'}FREE_MB"
            echo "CURL=$(command -v curl || true)"
            if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then echo "SYSTEMD=1"; else echo "SYSTEMD=0"; fi
            echo "TMUX=$(command -v tmux || true)"
            echo "SUDO=$(command -v sudo || true)"
            if sudo -n true 2>/dev/null; then echo "SUDO_NOPASS=1"; else echo "SUDO_NOPASS=0"; fi

            # Always check HOME/.opencode/bin/opencode FIRST (Ubuntu non-interactive SSH PATH issue), then bash -lc command -v
            BIN=""
            if [ -x "${'$'}HOME/.opencode/bin/opencode" ]; then
              BIN="${'$'}HOME/.opencode/bin/opencode"
            else
              BIN=$(command -v opencode 2>/dev/null || true)
            fi
            if [ -z "${'$'}BIN" ] && [ -x "${'$'}HOME/.local/bin/opencode" ]; then BIN="${'$'}HOME/.local/bin/opencode"; fi
            if [ -z "${'$'}BIN" ] && [ -x "/usr/local/bin/opencode" ]; then BIN="/usr/local/bin/opencode"; fi
            echo "OPENCODE_BIN=${'$'}BIN"
            if [ -n "${'$'}BIN" ]; then
              VER=$("${'$'}BIN" --version 2>/dev/null | head -n 1 || true)
              echo "OPENCODE_VER=${'$'}VER"
            fi

            if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
              SYS_ACTIVE=$(systemctl is-active opencode-serve 2>/dev/null || true)
              SYS_FRAG=$(systemctl show -p FragmentPath --value opencode-serve 2>/dev/null || true)
              SYS_TRANS=$(systemctl show -p Transient --value opencode-serve 2>/dev/null || true)
              echo "SYS_ACTIVE=${'$'}SYS_ACTIVE"
              echo "SYS_FRAG=${'$'}SYS_FRAG"
              echo "SYS_TRANS=${'$'}SYS_TRANS"

              USER_ACTIVE=$(systemctl --user is-active opencode-serve 2>/dev/null || true)
              USER_FRAG=$(systemctl --user show -p FragmentPath --value opencode-serve 2>/dev/null || true)
              USER_TRANS=$(systemctl --user show -p Transient --value opencode-serve 2>/dev/null || true)
              echo "USER_ACTIVE=${'$'}USER_ACTIVE"
              echo "USER_FRAG=${'$'}USER_FRAG"
              echo "USER_TRANS=${'$'}USER_TRANS"
            fi

            if [ -f /etc/opencode-serve.env ] || [ -f "${'$'}HOME/.config/opencode/opencode-serve.env" ]; then
              echo "HAS_ENV_FILE=1"
            else
              echo "HAS_ENV_FILE=0"
            fi
            '
        """.trimIndent()

        val result = execStreaming(script, timeoutSec = 25, onLine = onLog)
        val map = parseKeyValues(result.output)

        val sysFrag = map["SYS_FRAG"].orEmpty()
        val userFrag = map["USER_FRAG"].orEmpty()
        val sysActive = map["SYS_ACTIVE"].orEmpty()
        val userActive = map["USER_ACTIVE"].orEmpty()
        val sysTrans = map["SYS_TRANS"].equals("yes", ignoreCase = true) || sysFrag.startsWith("/run/")
        val userTrans = map["USER_TRANS"].equals("yes", ignoreCase = true) || userFrag.startsWith("/run/")

        val hasSystemUnit = sysFrag.isNotBlank() || sysActive == "active" || sysActive == "failed" || sysActive == "activating"
        val hasUserUnit = userFrag.isNotBlank() || userActive == "active" || userActive == "failed" || userActive == "activating"

        val isUserSvc = !hasSystemUnit && hasUserUnit
        val activeRaw = if (isUserSvc) userActive else sysActive
        val isTransient = if (isUserSvc) userTrans else sysTrans
        val unitExists = if (isUserSvc) hasUserUnit else hasSystemUnit

        val serviceState = when {
            !unitExists -> SystemdServiceState.NOT_FOUND
            activeRaw == "active" || activeRaw == "activating" || activeRaw == "reloading" -> SystemdServiceState.ACTIVE
            activeRaw == "failed" -> SystemdServiceState.FAILED
            activeRaw == "inactive" || activeRaw == "deactivating" -> SystemdServiceState.INACTIVE
            else -> SystemdServiceState.UNKNOWN
        }

        val freeMb = map["FREE_MB"]?.toLongOrNull() ?: -1L

        val env = OpenCodeEnvInfo(
            osName = map["OS"].orEmpty().ifBlank { "Linux" },
            arch = map["ARCH"].orEmpty(),
            userName = map["USER"].orEmpty(),
            homeDir = map["HOME"].orEmpty().ifBlank { "/root" },
            freeSpaceMb = freeMb,
            hasCurl = !map["CURL"].isNullOrBlank(),
            hasSystemd = map["SYSTEMD"] == "1",
            hasTmux = !map["TMUX"].isNullOrBlank(),
            isRoot = map["UID"] == "0",
            hasSudo = !map["SUDO"].isNullOrBlank(),
            hasPasswordlessSudo = map["SUDO_NOPASS"] == "1",
            opencodePath = map["OPENCODE_BIN"]?.takeIf { it.isNotBlank() },
            opencodeVersion = map["OPENCODE_VER"]?.takeIf { it.isNotBlank() },
            serviceState = serviceState,
            isUserService = isUserSvc,
            isTransientService = isTransient,
            hasPasswordProtection = map["HAS_ENV_FILE"] == "1",
            openCodePort = openCodePort,
        )

        if (requireSystemd) {
            if (!env.hasCurl) {
                throw IllegalStateException("服务器未安装 curl。请先安装 curl（例如 sudo apt install -y curl），然后重试。")
            }
            if (!env.hasSystemd) {
                throw IllegalStateException("服务器未检测到活动的 systemd（/run/systemd/system）。持久后台服务需要 systemd，否则 SSH 关闭后进程会退出。")
            }
            if (env.freeSpaceMb in 0..99) {
                throw IllegalStateException("磁盘空间不足（${env.freeSpaceMb} MB）。请至少释放 100 MB 后重试。")
            }
        }

        val freeLabel = if (env.freeSpaceMb >= 0) "${env.freeSpaceMb} MB" else "无"
        val permLabel = when {
            env.isRoot -> "root"
            env.hasPasswordlessSudo -> "sudo（免密码）"
            else -> "user-service + linger"
        }
        onLog?.invoke("✓ ${env.osName} ${env.arch}，剩余空间 $freeLabel，systemd=可用，权限：$permLabel")
        env
    }

    suspend fun ensureOrUpdateOpenCodeBinary(
        env: OpenCodeEnvInfo,
        forceUpdate: Boolean,
        onLog: (String) -> Unit,
    ): Pair<String, String?> = withContext(Dispatchers.IO) {
        if (!forceUpdate && !env.opencodePath.isNullOrBlank()) {
            val ver = env.opencodeVersion ?: readBinaryVersion(env.opencodePath)
            onLog("✓ OpenCode 已安装：${env.opencodePath}（${ver ?: "版本未知"}），跳过重复下载。")
            return@withContext env.opencodePath to ver
        }

        onLog("$ bash -lc 'curl -fsSL https://opencode.ai/install | bash'")
        val installRes = execStreaming(
            command = "bash -lc 'curl -fsSL https://opencode.ai/install | bash'",
            timeoutSec = 180,
            onLine = onLog,
        )
        if (installRes.exitCode != 0) {
            throw IllegalStateException("OpenCode 安装程序退出，代码 ${installRes.exitCode}")
        }

        val resolvedPath = resolveAbsoluteBinaryPath()
            ?: throw IllegalStateException("在 \$HOME/.opencode/bin/opencode 和 PATH 中均未找到已安装的 OpenCode 可执行文件。")
        val version = readBinaryVersion(resolvedPath)
        onLog("✓ 可执行文件绝对路径：$resolvedPath" + (version?.let { " ($it)" } ?: ""))
        resolvedPath to version
    }

    suspend fun migrateLegacyRuns(
        env: OpenCodeEnvInfo,
        onLog: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        onLog("$ 正在停止临时服务和遗留的 opencode serve 进程…")
        val stopSystemCmd = when {
            env.isRoot -> "systemctl stop opencode-serve 2>/dev/null || true; systemctl reset-failed opencode-serve 2>/dev/null || true"
            env.hasPasswordlessSudo -> "sudo -n systemctl stop opencode-serve 2>/dev/null || true; sudo -n systemctl reset-failed opencode-serve 2>/dev/null || true"
            else -> "true"
        }
        val script = """
            bash -lc '
            $stopSystemCmd
            systemctl --user stop opencode-serve 2>/dev/null || true
            systemctl --user reset-failed opencode-serve 2>/dev/null || true
            if command -v tmux >/dev/null 2>&1; then
              tmux kill-session -t opencode-serve 2>/dev/null || true
            fi
            pkill -f "opencode serve" 2>/dev/null || true
            sleep 1
            '
        """.trimIndent()
        execStreaming(script, timeoutSec = 25, onLine = onLog)
        onLog("✓ 旧进程和临时服务已停止")
    }

    suspend fun installPersistentSystemdService(
        profileId: Long?,
        env: OpenCodeEnvInfo,
        binaryPath: String,
        port: Int,
        enablePasswordAuth: Boolean,
        onLog: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!env.hasSystemd) {
            throw IllegalStateException("服务器上无法使用 systemd，不能创建持久服务")
        }
        val cleanPort = port.coerceIn(1, 65535)
        val useSystemScope = env.isRoot || env.hasPasswordlessSudo
        val cleanHome = env.homeDir.trimEnd('/').ifBlank { "/root" }

        val envFilePath = if (enablePasswordAuth) {
            if (useSystemScope) "/etc/opencode-serve.env" else "$cleanHome/.config/opencode/opencode-serve.env"
        } else {
            null
        }

        var passwordSecret: String? = null
        val authUsername = OpenCodeConfigHelper.DEFAULT_AUTH_USERNAME

        if (enablePasswordAuth) {
            val existingAuth = profileId?.let { secretStore?.getOpenCodeServerAuth(it) }
            passwordSecret = existingAuth?.password ?: OpenCodeConfigHelper.generateStrongPassword(28)
            val effectiveUser = existingAuth?.username?.ifBlank { authUsername } ?: authUsername
            val envContent = OpenCodeConfigHelper.buildEnvFileContent(effectiveUser, passwordSecret)

            if (useSystemScope) {
                if (env.isRoot) {
                    onLog("→ 正在通过 SFTP 上传 /etc/opencode-serve.env（chmod 600）…")
                    sftpRepository.writeTextFile("/etc/opencode-serve.env", envContent, null, forceOverwrite = true)
                    sftpRepository.chmod("/etc/opencode-serve.env", 0x180)
                } else {
                    val tmpEnv = "$cleanHome/.opencode-serve.env.tmp"
                    onLog("→ 正在通过 SFTP 上传密码文件并移动到 /etc/opencode-serve.env（chmod 600）…")
                    sftpRepository.writeTextFile(tmpEnv, envContent, null, forceOverwrite = true)
                    runCatching { sftpRepository.chmod(tmpEnv, 0x180) }
                    val mvRes = execStreaming(
                        "sudo -n mv ${shellQuote(tmpEnv)} /etc/opencode-serve.env && sudo -n chown root:root /etc/opencode-serve.env && sudo -n chmod 600 /etc/opencode-serve.env",
                        timeoutSec = 20,
                        onLine = onLog,
                        secretsToRedact = listOf(passwordSecret),
                    )
                    if (mvRes.exitCode != 0) {
                        throw IllegalStateException("无法安装 /etc/opencode-serve.env（代码 ${mvRes.exitCode}）")
                    }
                }
            } else {
                val cfgDir = "$cleanHome/.config/opencode"
                execStreaming("mkdir -p ${shellQuote(cfgDir)} && chmod 700 ${shellQuote(cfgDir)}", 15, null)
                onLog("→ 正在通过 SFTP 上传 $envFilePath（chmod 600）…")
                sftpRepository.writeTextFile(envFilePath!!, envContent, null, forceOverwrite = true)
                runCatching { sftpRepository.chmod(envFilePath, 0x180) }
            }

            if (profileId != null) {
                secretStore?.saveOpenCodeServerAuth(profileId, effectiveUser, passwordSecret)
            }
            onLog("✓ 已配置 Basic Auth 密码保护，并保存到 Android Keystore")
        } else {
            if (profileId != null) {
                secretStore?.clearOpenCodeServerAuth(profileId)
            }
            // Remove stale env files so service starts without Basic Auth
            if (useSystemScope) {
                val rmCmd = if (env.isRoot) "rm -f /etc/opencode-serve.env" else "sudo -n rm -f /etc/opencode-serve.env"
                runCatching { execStreaming(rmCmd, 10, null) }
            } else {
                runCatching { execStreaming("rm -f ${shellQuote("$cleanHome/.config/opencode/opencode-serve.env")}", 10, null) }
            }
        }

        val redactList = listOfNotNull(passwordSecret)
        val unitContent = OpenCodeConfigHelper.buildSystemdUnit(
            opencodeBinaryPath = binaryPath,
            userName = env.userName,
            homeDir = cleanHome,
            port = cleanPort,
            isUserService = !useSystemScope,
            envFilePath = envFilePath,
        )

        if (useSystemScope) {
            val targetPath = "/etc/systemd/system/opencode-serve.service"
            if (env.isRoot) {
                onLog("→ 正在通过 SFTP 上传 $targetPath…")
                sftpRepository.writeTextFile(targetPath, unitContent, null, forceOverwrite = true)
                val res = execStreaming(
                    "systemctl daemon-reload && systemctl enable --now opencode-serve && systemctl restart opencode-serve",
                    timeoutSec = 35,
                    onLine = onLog,
                    secretsToRedact = redactList,
                )
                if (res.exitCode != 0) {
                    throw IllegalStateException("systemctl 无法启动 opencode-serve（代码 ${res.exitCode}）")
                }
            } else {
                val stagingPath = "$cleanHome/.opencode-serve.service.tmp"
                onLog("→ 正在通过 SFTP 上传 unit 文件，并通过 sudo 安装到 $targetPath…")
                sftpRepository.writeTextFile(stagingPath, unitContent, null, forceOverwrite = true)
                val res = execStreaming(
                    "sudo -n mv ${shellQuote(stagingPath)} $targetPath && " +
                        "sudo -n chmod 644 $targetPath && " +
                        "sudo -n systemctl daemon-reload && " +
                        "sudo -n systemctl enable --now opencode-serve && " +
                        "sudo -n systemctl restart opencode-serve",
                    timeoutSec = 35,
                    onLine = onLog,
                    secretsToRedact = redactList,
                )
                if (res.exitCode != 0) {
                    throw IllegalStateException("sudo systemctl 无法启动 opencode-serve（代码 ${res.exitCode}）")
                }
            }
            onLog("✓ 持久化系统服务 opencode-serve 已启用（127.0.0.1:$cleanPort）")
            return@withContext false // isUserService = false
        } else {
            val userUnitDir = "$cleanHome/.config/systemd/user"
            val userUnitPath = "$userUnitDir/opencode-serve.service"
            execStreaming("mkdir -p ${shellQuote(userUnitDir)}", timeoutSec = 15, onLine = null)
            onLog("→ 正在通过 SFTP 上传用户服务 $userUnitPath…")
            sftpRepository.writeTextFile(userUnitPath, unitContent, null, forceOverwrite = true)

            val lingerRes = execStreaming(
                "loginctl enable-linger ${shellQuote(env.userName)} 2>&1 || true",
                timeoutSec = 15,
                onLine = onLog,
                secretsToRedact = redactList,
            )
            if (lingerRes.exitCode == 0) {
                onLog("✓ 已为 ${env.userName} 执行 loginctl enable-linger")
            }

            val res = execStreaming(
                "bash -lc 'systemctl --user daemon-reload && systemctl --user enable --now opencode-serve && systemctl --user restart opencode-serve'",
                timeoutSec = 35,
                onLine = onLog,
                secretsToRedact = redactList,
            )
            if (res.exitCode != 0) {
                throw IllegalStateException("systemctl --user 无法启动 opencode-serve（代码 ${res.exitCode}）。请确认用户 systemd 会话已启用，或具有 root/sudo 权限。")
            }
            onLog("✓ 已启用带 linger 的持久化用户服务 opencode-serve（127.0.0.1:$cleanPort）")
            return@withContext true // isUserService = true
        }
    }

    suspend fun verifyInstallation(
        profileId: Long?,
        env: OpenCodeEnvInfo,
        isUserService: Boolean,
        port: Int,
        localTunnelPort: Int?,
        verifyPersistence: Boolean,
        onLog: (String) -> Unit,
    ): OpenCodeVerificationReport = withContext(Dispatchers.IO) {
        val cleanPort = port.coerceIn(1, 65535)
        val auth = profileId?.let { secretStore?.getOpenCodeServerAuth(it) }
        val redactList = listOfNotNull(auth?.password)

        val initial = runTripleVerification(
            isUserService = isUserService,
            port = cleanPort,
            localTunnelPort = localTunnelPort,
            auth = auth,
            onLog = onLog,
            redactList = redactList,
        )

        if (!initial.serviceActive || !initial.portListening || !initial.sessionJsonOk) {
            val detail = buildString {
                if (!initial.serviceActive) append("服务未处于 active 状态（${initial.serviceActiveDetail}）；")
                if (!initial.portListening) append("端口 127.0.0.1:$cleanPort 未在监听；")
                if (!initial.sessionJsonOk) append("GET /session 未返回 JSON（${initial.sessionJsonDetail}）")
            }
            throw IllegalStateException("自动检查未通过：$detail")
        }

        if (!verifyPersistence) {
            return@withContext initial
        }

        onLog("$ 正在检查稳定性：执行 systemctl restart opencode-serve…")
        val restartCmd = buildSystemctlCommand(env, isUserService, "restart")
        val restartRes = execStreaming(restartCmd, timeoutSec = 30, onLine = onLog, secretsToRedact = redactList)
        if (restartRes.exitCode != 0) {
            throw IllegalStateException("稳定性测试（systemctl restart）退出，代码 ${restartRes.exitCode}")
        }

        val afterRestart = runTripleVerification(
            isUserService = isUserService,
            port = cleanPort,
            localTunnelPort = localTunnelPort,
            auth = auth,
            onLog = onLog,
            redactList = redactList,
        )
        val survived = afterRestart.serviceActive && afterRestart.portListening && afterRestart.sessionJsonOk
        if (!survived) {
            throw IllegalStateException("systemctl restart 后服务器未能启动：${afterRestart.sessionJsonDetail.ifBlank { afterRestart.serviceActiveDetail }}")
        }
        onLog("✓ 服务重启后运行正常，并已配置为 VPS 重启后自动启动")

        afterRestart.copy(
            restartSurvived = true,
            restartSurvivedDetail = "重启检查通过，VPS 重启后服务会自动启动",
        )
    }

    suspend fun startExistingServer(
        profileId: Long?,
        env: OpenCodeEnvInfo,
        port: Int,
        localTunnelPort: Int?,
        onLog: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (!env.hasSystemd || !env.hasPersistentUnit) {
            throw IllegalStateException("尚未配置持久化 systemd 服务 opencode-serve。请点击“安装并配置 OpenCode”。")
        }
        val cmd = buildSystemctlCommand(env, env.isUserService, "start")
        onLog("$ $cmd")
        val res = execStreaming(cmd, timeoutSec = 30, onLine = onLog)
        if (res.exitCode != 0) {
            throw IllegalStateException("无法启动 opencode-serve 服务（代码 ${res.exitCode}）")
        }
        verifyInstallation(
            profileId = profileId,
            env = env,
            isUserService = env.isUserService,
            port = port,
            localTunnelPort = localTunnelPort,
            verifyPersistence = false,
            onLog = onLog,
        )
    }

    suspend fun stopOpenCodeServer(
        port: Int,
        onLog: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val env = inspectEnvironment(openCodePort = port, requireSystemd = false)
        val cmd = buildSystemctlCommand(env, env.isUserService, "stop")
        onLog("$ $cmd")
        val res = execStreaming(cmd, timeoutSec = 25, onLine = onLog)
        if (res.exitCode != 0) {
            throw IllegalStateException("无法停止 opencode-serve 服务（代码 ${res.exitCode}）")
        }
        onLog("✓ opencode-serve 服务已停止")
    }

    suspend fun restartOpenCodeServer(
        profileId: Long?,
        port: Int,
        localTunnelPort: Int?,
        onLog: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val env = inspectEnvironment(openCodePort = port, requireSystemd = true)
        if (!env.hasPersistentUnit) {
            throw IllegalStateException("尚未安装持久化 opencode-serve 服务。请使用“修复”创建服务。")
        }
        val cmd = buildSystemctlCommand(env, env.isUserService, "restart")
        onLog("$ $cmd")
        val res = execStreaming(cmd, timeoutSec = 30, onLine = onLog)
        if (res.exitCode != 0) {
            throw IllegalStateException("无法重启 opencode-serve（代码 ${res.exitCode}）")
        }
        val ready = waitForServerReadyAfterRestart(
            profileId = profileId,
            isUserService = env.isUserService,
            initialTunnelPort = localTunnelPort,
            onProgress = onLog,
        )
        if (!ready.serviceActive) {
            throw IllegalStateException("opencode-serve 未进入 active 状态：${ready.detail}")
        }
        if (!ready.serverAlive) {
            throw IllegalStateException("OpenCode 服务器在 40 秒内未响应 /session：${ready.detail}")
        }
    }

    /**
     * Restarts systemd service opencode-serve and checks whether it transitions to `active`.
     * Does NOT check HTTP /session — callers use waitForServerReadyAfterRestart() for the 40s HTTP polling.
     */
    suspend fun restartServiceAndCheckActive(
        port: Int,
        onLog: ((String) -> Unit)? = null,
    ): ServiceRestartCheckResult = withContext(Dispatchers.IO) {
        val env = inspectEnvironment(openCodePort = port, requireSystemd = true)
        if (!env.hasPersistentUnit) {
            throw IllegalStateException("尚未安装持久化 opencode-serve 服务。请使用“修复”创建服务。")
        }
        val cmd = buildSystemctlCommand(env, env.isUserService, "restart")
        onLog?.invoke("$ $cmd")
        val res = execStreaming(cmd, timeoutSec = 30, onLine = onLog)
        if (res.exitCode != 0) {
            return@withContext ServiceRestartCheckResult(
                serviceActive = false,
                isUserService = env.isUserService,
                stateText = "error(${res.exitCode})",
                detail = "systemctl restart 退出，代码 ${res.exitCode}",
            )
        }

        // Wait up to 6 seconds for activating -> active
        var stateText = "unknown"
        repeat(4) { idx ->
            stateText = querySystemdActiveState(env.isUserService)
            if (stateText == "active") {
                return@withContext ServiceRestartCheckResult(
                    serviceActive = true,
                    isUserService = env.isUserService,
                    stateText = stateText,
                    detail = "opencode-serve 服务处于活动状态（active）",
                )
            }
            if (stateText == "failed" || stateText == "inactive") {
                return@withContext ServiceRestartCheckResult(
                    serviceActive = false,
                    isUserService = env.isUserService,
                    stateText = stateText,
                    detail = "重启后 opencode-serve 状态为 $stateText",
                )
            }
            if (idx < 3) delay(1_500L)
        }

        ServiceRestartCheckResult(
            serviceActive = stateText == "active",
            isUserService = env.isUserService,
            stateText = stateText,
            detail = "opencode-serve 未进入 active 状态（当前状态：$stateText）",
        )
    }

    /**
     * Waits for OpenCode HTTP server in a loop: every 2 seconds for 40 seconds total (20 attempts).
     * - Sends GET /session with Basic Auth if enabled in Keystore.
     * - Treats HTTP 2xx JSON AND HTTP 401 as server alive.
     * - Recreates SSH tunnel on connection drops and retries.
     * - Detects if systemd service crashed (not active) during startup.
     */
    suspend fun waitForServerReadyAfterRestart(
        profileId: Long?,
        isUserService: Boolean?,
        initialTunnelPort: Int?,
        onProgress: ((String) -> Unit)? = null,
    ): ServerReadyCheckResult = withContext(Dispatchers.IO) {
        val auth = profileId?.let { secretStore?.getOpenCodeServerAuth(it) }
        var tunnelPort = initialTunnelPort ?: connectionManager.getActiveLocalTunnelPort()
        var resolvedIsUserService = isUserService
        if (resolvedIsUserService == null) {
            val env = runCatching { inspectEnvironment(openCodePort = 4096, requireSystemd = false) }.getOrNull()
            resolvedIsUserService = env?.isUserService ?: false
        }

        var lastServiceState = "active"
        var lastDetail = "/session 无响应"

        for (attempt in 1..RESTART_POLL_MAX_ATTEMPTS) {
            val elapsedSec = (attempt - 1) * (RESTART_POLL_INTERVAL_MS / 1000L)

            // Check systemd service status on server (best effort; ignore transient SSH drop)
            val svcState = runCatching { querySystemdActiveState(resolvedIsUserService) }.getOrNull()
            if (svcState != null) {
                lastServiceState = svcState
                if (svcState == "failed" || svcState == "inactive") {
                    return@withContext ServerReadyCheckResult(
                        serviceActive = false,
                        serverAlive = false,
                        detail = "opencode-serve 已停止（状态：$svcState）",
                        activeTunnelPort = tunnelPort,
                    )
                }
            }

            // Ensure tunnel port is available
            if (tunnelPort == null) {
                onProgress?.invoke("第 $attempt/$RESTART_POLL_MAX_ATTEMPTS 次尝试（${elapsedSec} 秒）：正在重建 SSH 隧道…")
                tunnelPort = runCatching { connectionManager.recreateLocalTunnel() }.getOrNull()
            }

            if (tunnelPort != null) {
                val probe = probeTunnelSessionJson(
                    localTunnelPort = tunnelPort,
                    auth = auth,
                    treat401AsAlive = true,
                )
                lastDetail = probe.detail

                if (probe.alive) {
                    onProgress?.invoke("服务器已响应：${probe.detail}")
                    return@withContext ServerReadyCheckResult(
                        serviceActive = lastServiceState == "active" || lastServiceState == "activating",
                        serverAlive = true,
                        detail = probe.detail,
                        activeTunnelPort = tunnelPort,
                    )
                }

                if (probe.isConnectionError) {
                    onProgress?.invoke("第 $attempt/$RESTART_POLL_MAX_ATTEMPTS 次尝试（${elapsedSec} 秒）：隧道已断开，正在重建…")
                    val rebuiltPort = runCatching { connectionManager.recreateLocalTunnel() }.getOrNull()
                    if (rebuiltPort != null) {
                        tunnelPort = rebuiltPort
                        val retryProbe = probeTunnelSessionJson(
                            localTunnelPort = rebuiltPort,
                            auth = auth,
                            treat401AsAlive = true,
                        )
                        lastDetail = retryProbe.detail
                        if (retryProbe.alive) {
                            onProgress?.invoke("服务器已响应：${retryProbe.detail}")
                            return@withContext ServerReadyCheckResult(
                                serviceActive = lastServiceState == "active" || lastServiceState == "activating",
                                serverAlive = true,
                                detail = retryProbe.detail,
                                activeTunnelPort = rebuiltPort,
                            )
                        }
                    }
                } else {
                    onProgress?.invoke("第 $attempt/$RESTART_POLL_MAX_ATTEMPTS 次尝试（${elapsedSec} 秒）：${probe.detail}")
                }
            } else {
                lastDetail = "SSH 隧道不可用"
                onProgress?.invoke("第 $attempt/$RESTART_POLL_MAX_ATTEMPTS 次尝试（${elapsedSec} 秒）：正在等待 SSH 隧道…")
            }

            if (attempt < RESTART_POLL_MAX_ATTEMPTS) {
                delay(RESTART_POLL_INTERVAL_MS)
            }
        }

        val finalSvcState = runCatching { querySystemdActiveState(resolvedIsUserService) }.getOrNull()
            ?: lastServiceState
        ServerReadyCheckResult(
            serviceActive = finalSvcState == "active",
            serverAlive = false,
            detail = lastDetail,
            activeTunnelPort = tunnelPort,
        )
    }

    private suspend fun querySystemdActiveState(isUserService: Boolean): String {
        val activeCmd = if (isUserService) {
            "bash -lc 'systemctl --user is-active opencode-serve 2>&1 || true'"
        } else {
            "bash -lc 'systemctl is-active opencode-serve 2>&1 || true'"
        }
        val actOut = execStreaming(activeCmd, 10, null).output.trim()
        return actOut.lines().firstOrNull()?.trim().orEmpty().ifBlank { "unknown" }
    }

    suspend fun updateOpenCode(
        profileId: Long?,
        port: Int,
        localTunnelPort: Int?,
        onLog: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val env = inspectEnvironment(openCodePort = port, requireSystemd = true, onLog = onLog)
        val (bin, _) = ensureOrUpdateOpenCodeBinary(env, forceUpdate = true, onLog = onLog)
        if (env.hasPersistentUnit) {
            restartOpenCodeServer(profileId, port, localTunnelPort, onLog)
        } else {
            migrateLegacyRuns(env, onLog)
            val isUser = installPersistentSystemdService(
                profileId = profileId,
                env = env,
                binaryPath = bin,
                port = port,
                enablePasswordAuth = env.hasPasswordProtection,
                onLog = onLog,
            )
            verifyInstallation(profileId, env, isUser, port, localTunnelPort, verifyPersistence = false, onLog = onLog)
        }
    }

    suspend fun removeOpenCodeService(
        profileId: Long?,
        port: Int,
        onLog: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val env = inspectEnvironment(openCodePort = port, requireSystemd = false)
        val cleanHome = env.homeDir.trimEnd('/').ifBlank { "/root" }
        onLog("$ 正在删除 opencode-serve 服务…")

        val sysRm = when {
            env.isRoot ->
                "systemctl stop opencode-serve 2>/dev/null || true; systemctl disable opencode-serve 2>/dev/null || true; rm -f /etc/systemd/system/opencode-serve.service /etc/opencode-serve.env; systemctl daemon-reload 2>/dev/null || true; systemctl reset-failed opencode-serve 2>/dev/null || true"
            env.hasPasswordlessSudo ->
                "sudo -n systemctl stop opencode-serve 2>/dev/null || true; sudo -n systemctl disable opencode-serve 2>/dev/null || true; sudo -n rm -f /etc/systemd/system/opencode-serve.service /etc/opencode-serve.env; sudo -n systemctl daemon-reload 2>/dev/null || true; sudo -n systemctl reset-failed opencode-serve 2>/dev/null || true"
            else -> "true"
        }
        val script = """
            bash -lc '
            $sysRm
            systemctl --user stop opencode-serve 2>/dev/null || true
            systemctl --user disable opencode-serve 2>/dev/null || true
            rm -f "${'$'}HOME/.config/systemd/user/opencode-serve.service" "${'$'}HOME/.config/opencode/opencode-serve.env"
            systemctl --user daemon-reload 2>/dev/null || true
            systemctl --user reset-failed opencode-serve 2>/dev/null || true
            pkill -f "opencode serve" 2>/dev/null || true
            '
        """.trimIndent()
        execStreaming(script, timeoutSec = 30, onLine = onLog)
        if (profileId != null) {
            secretStore?.clearOpenCodeServerAuth(profileId)
        }
        onLog("✓ opencode-serve 服务已删除")
    }

    suspend fun fetchServerLogs(
        profileId: Long? = null,
        lines: Int = 100,
    ): String = withContext(Dispatchers.IO) {
        val count = lines.coerceIn(20, 400)
        val secretToRedact = listOfNotNull(profileId?.let { secretStore?.getOpenCodeServerAuth(it)?.password })
        val cmd = """
            bash -lc '
            if command -v journalctl >/dev/null 2>&1; then
              if journalctl -u opencode-serve -n $count --no-pager 2>/dev/null | grep -q .; then
                journalctl -u opencode-serve -n $count --no-pager 2>&1
                exit 0
              fi
              if sudo -n journalctl -u opencode-serve -n $count --no-pager 2>/dev/null | grep -q .; then
                sudo -n journalctl -u opencode-serve -n $count --no-pager 2>&1
                exit 0
              fi
              if journalctl --user -u opencode-serve -n $count --no-pager 2>/dev/null | grep -q .; then
                journalctl --user -u opencode-serve -n $count --no-pager 2>&1
                exit 0
              fi
            fi
            echo ""
            '
        """.trimIndent()
        val result = execStreaming(cmd, timeoutSec = 20, onLine = null, secretsToRedact = secretToRedact)
        OpenCodeConfigHelper.sanitizeLogs(result.output.trim(), secretToRedact)
    }

    suspend fun testOpenAiCompatibleModels(
        baseUrl: String,
        apiKey: String,
    ): String = withContext(Dispatchers.IO) {
        val normalized = OpenCodeConfigHelper.normalizeBaseUrl(baseUrl)
        val url = "$normalized/models"
        val reqBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        }
        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${body.take(180).ifBlank { response.message }}")
            }
            val count = runCatching {
                val root = JSONObject(body)
                root.optJSONArray("data")?.length()
            }.getOrNull()
            if (count != null) {
                "已发现模型：$count 个"
            } else {
                "HTTP ${response.code} OK"
            }
        }
    }

    suspend fun saveCustomProviderAndRestart(
        profileId: Long?,
        config: CustomProviderConfig,
        port: Int,
        localTunnelPort: Int?,
        onLog: ((String) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val env = inspectEnvironment(openCodePort = port, requireSystemd = false)
        val configDir = "${env.homeDir.trimEnd('/')}/.config/opencode"
        val configPath = "$configDir/opencode.json"

        execStreaming(
            "mkdir -p ${shellQuote(configDir)} && chmod 700 ${shellQuote(configDir)}",
            timeoutSec = 15,
            onLine = null,
        )

        val existing = runCatching {
            sftpRepository.readTextFile(configPath).text
        }.getOrNull()

        val merged = OpenCodeConfigHelper.mergeOpenAiCompatibleProvider(existing, config)

        // Upload merged config exclusively via SFTP so API key NEVER appears in shell history or logs
        sftpRepository.writeTextFile(
            path = configPath,
            text = merged,
            expectedMtimeEpochSec = null,
            forceOverwrite = true,
        )
        runCatching { sftpRepository.chmod(configPath, 0x180) }

        onLog?.invoke("✓ 配置已保存到 ~/.config/opencode/opencode.json（chmod 600）")
        restartOpenCodeServer(profileId, port, localTunnelPort, onLog ?: {})
    }

    private suspend fun runTripleVerification(
        isUserService: Boolean,
        port: Int,
        localTunnelPort: Int?,
        auth: OpenCodeServerAuth?,
        onLog: (String) -> Unit,
        redactList: List<String>,
    ): OpenCodeVerificationReport {
        var serviceActive = false
        var serviceActiveDetail = "inactive"
        var portListening = false
        var portListeningDetail = ""
        var sessionJsonOk = false
        var sessionJsonDetail = ""

        val activeCmd = if (isUserService) {
            "bash -lc 'systemctl --user is-active opencode-serve 2>&1 || true'"
        } else {
            "bash -lc 'systemctl is-active opencode-serve 2>&1 || true'"
        }
        val ssCmd = "bash -lc 'ss -ltn 2>/dev/null | grep -E \"(:|\\])$port\\b\" || netstat -ltn 2>/dev/null | grep -E \":$port\\b\" || true'"

        var activePort = localTunnelPort ?: connectionManager.getActiveLocalTunnelPort()
        repeat(RESTART_POLL_MAX_ATTEMPTS) { attempt ->
            val actOut = execStreaming(activeCmd, 10, null, redactList).output.trim()
            serviceActive = actOut.lines().firstOrNull()?.trim() == "active"
            serviceActiveDetail = actOut.lines().firstOrNull()?.trim().orEmpty().ifBlank { "unknown" }

            val ssOut = execStreaming(ssCmd, 10, null, redactList).output.trim()
            val loopbackBound = ssOut.contains("127.0.0.1:$port") || ssOut.contains("[::1]:$port")
            val exposedPublicly = ssOut.contains("0.0.0.0:$port") || ssOut.contains("*:$port") || ssOut.contains("[::]:$port")
            portListening = loopbackBound && !exposedPublicly
            portListeningDetail = when {
                exposedPublicly -> "警告：检测到公开监听的套接字（$ssOut）"
                loopbackBound -> "127.0.0.1:$port（仅限本机访问）"
                else -> "端口 $port 未在监听"
            }

            if (activePort == null) {
                activePort = runCatching { connectionManager.recreateLocalTunnel() }.getOrNull()
            }
            if (activePort != null) {
                val probe = probeTunnelSessionJson(activePort, auth, treat401AsAlive = true)
                if (probe.isConnectionError) {
                    val rebuilt = runCatching { connectionManager.recreateLocalTunnel() }.getOrNull()
                    if (rebuilt != null) {
                        activePort = rebuilt
                        val retryProbe = probeTunnelSessionJson(rebuilt, auth, treat401AsAlive = true)
                        sessionJsonOk = retryProbe.alive
                        sessionJsonDetail = retryProbe.detail
                    } else {
                        sessionJsonOk = probe.alive
                        sessionJsonDetail = probe.detail
                    }
                } else {
                    sessionJsonOk = probe.alive
                    sessionJsonDetail = probe.detail
                }
            } else {
                sessionJsonOk = false
                sessionJsonDetail = "SSH 隧道未启用"
            }

            if (serviceActive && portListening && sessionJsonOk) {
                onLog("✓ 检查 1/3：opencode-serve 服务处于 active 状态")
                onLog("✓ 检查 2/3：ss -ltn — $portListeningDetail")
                onLog("✓ 检查 3/3：GET /session — $sessionJsonDetail")
                return OpenCodeVerificationReport(
                    serviceActive = true,
                    serviceActiveDetail = "active",
                    portListening = true,
                    portListeningDetail = portListeningDetail,
                    sessionJsonOk = true,
                    sessionJsonDetail = sessionJsonDetail,
                )
            }

            if (attempt < RESTART_POLL_MAX_ATTEMPTS - 1) {
                delay(RESTART_POLL_INTERVAL_MS)
            }
        }

        return OpenCodeVerificationReport(
            serviceActive = serviceActive,
            serviceActiveDetail = serviceActiveDetail,
            portListening = portListening,
            portListeningDetail = portListeningDetail,
            sessionJsonOk = sessionJsonOk,
            sessionJsonDetail = sessionJsonDetail,
        )
    }

    private fun probeTunnelSessionJson(
        localTunnelPort: Int,
        auth: OpenCodeServerAuth?,
        treat401AsAlive: Boolean = false,
    ): SessionProbeResult = runCatching {
        val builder = Request.Builder()
            .url("http://127.0.0.1:$localTunnelPort/session")
            .header("Accept", "application/json")
            .get()
        if (auth != null && auth.password.isNotBlank()) {
            builder.header(
                "Authorization",
                Credentials.basic(auth.username.ifBlank { OpenCodeConfigHelper.DEFAULT_AUTH_USERNAME }, auth.password, StandardCharsets.UTF_8),
            )
        }
        httpClient.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty().trim()
            if (resp.code == 401) {
                return@use SessionProbeResult(
                    alive = treat401AsAlive,
                    isConnectionError = false,
                    detail = if (treat401AsAlive) {
                        "HTTP 401（服务器已启动，已启用 Basic Auth）"
                    } else {
                        "HTTP 401（需要 Basic Auth）"
                    },
                    httpCode = 401,
                )
            }
            if (resp.code !in 200..299) {
                return@use SessionProbeResult(
                    alive = false,
                    isConnectionError = false,
                    detail = "HTTP ${resp.code}",
                    httpCode = resp.code,
                )
            }
            val isJson = (body.startsWith("[") && runCatching { JSONArray(body) }.isSuccess) ||
                (body.startsWith("{") && runCatching { JSONObject(body) }.isSuccess)
            if (isJson) {
                val authNote = if (auth != null) " (Basic Auth OK)" else ""
                SessionProbeResult(
                    alive = true,
                    isConnectionError = false,
                    detail = "HTTP ${resp.code} JSON$authNote",
                    httpCode = resp.code,
                )
            } else {
                SessionProbeResult(
                    alive = false,
                    isConnectionError = false,
                    detail = "响应不是 JSON",
                    httpCode = resp.code,
                )
            }
        }
    }.getOrElse { err ->
        SessionProbeResult(
            alive = false,
            isConnectionError = true,
            detail = err.message ?: "无连接",
        )
    }

    private suspend fun resolveAbsoluteBinaryPath(): String? {
        val resolveCmd = """
            bash -lc '
            if [ -x "${'$'}HOME/.opencode/bin/opencode" ]; then echo "${'$'}HOME/.opencode/bin/opencode"; exit 0; fi
            CMD_BIN=$(command -v opencode 2>/dev/null || true)
            if [ -n "${'$'}CMD_BIN" ] && [ -x "${'$'}CMD_BIN" ]; then echo "${'$'}CMD_BIN"; exit 0; fi
            for p in "${'$'}HOME/.local/bin/opencode" /usr/local/bin/opencode; do
              if [ -x "${'$'}p" ]; then echo "${'$'}p"; exit 0; fi
            done
            exit 1
            '
        """.trimIndent()
        val pathRes = execStreaming(resolveCmd, timeoutSec = 20, onLine = null)
        return pathRes.output.lines().map { it.trim() }.firstOrNull { it.startsWith("/") }
    }

    private suspend fun readBinaryVersion(binaryPath: String): String? {
        val res = runCatching {
            execStreaming("${shellQuote(binaryPath)} --version 2>/dev/null | head -n 1", 10, null)
        }.getOrNull()
        return res?.output?.lines()?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun buildSystemctlCommand(
        env: OpenCodeEnvInfo,
        isUserService: Boolean,
        action: String,
    ): String = when {
        isUserService -> "bash -lc 'systemctl --user $action opencode-serve'"
        env.isRoot -> "systemctl $action opencode-serve"
        env.hasPasswordlessSudo -> "sudo -n systemctl $action opencode-serve"
        else -> "bash -lc 'systemctl --user $action opencode-serve'"
    }

    private suspend fun execStreaming(
        command: String,
        timeoutSec: Long,
        onLine: ((String) -> Unit)?,
        secretsToRedact: List<String> = emptyList(),
    ): SshExecResult = withContext(Dispatchers.IO) {
        val client = connectionManager.getActiveSshClient()
            ?: throw IllegalStateException("SSH 连接未激活")

        val outputBuilder = StringBuilder()
        var exitCode = -1
        client.startSession().use { session ->
            val cmd = session.exec(command)
            val stdoutReader = BufferedReader(InputStreamReader(cmd.inputStream, StandardCharsets.UTF_8))
            val stderrReader = BufferedReader(InputStreamReader(cmd.errorStream, StandardCharsets.UTF_8))

            var line: String? = stdoutReader.readLine()
            while (line != null) {
                val safe = OpenCodeConfigHelper.sanitizeLogs(line, secretsToRedact)
                outputBuilder.appendLine(safe)
                onLine?.invoke(safe)
                line = stdoutReader.readLine()
            }

            var errLine: String? = stderrReader.readLine()
            while (errLine != null) {
                val safe = OpenCodeConfigHelper.sanitizeLogs(errLine, secretsToRedact)
                outputBuilder.appendLine(safe)
                onLine?.invoke(safe)
                errLine = stderrReader.readLine()
            }

            cmd.join(timeoutSec, TimeUnit.SECONDS)
            exitCode = cmd.exitStatus ?: -1
        }
        SshExecResult(exitCode = exitCode, output = outputBuilder.toString())
    }

    private fun parseKeyValues(output: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        output.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                val k = line.substring(0, idx).trim()
                val v = line.substring(idx + 1).trim()
                result[k] = v
            }
        }
        return result
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val RESTART_POLL_INTERVAL_MS = 2_000L
        private const val RESTART_POLL_MAX_ATTEMPTS = 20 // 20 * 2s = 40 seconds
    }
}
