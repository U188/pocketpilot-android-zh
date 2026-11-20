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
        val auth = profileId?.let { secretStore?.getOpenCodeServerAuth(it) }
        val tunnelSessionOk = localTunnelPort != null &&
            probeTunnelSessionJson(localTunnelPort, auth, treat401AsAlive = true).alive
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

    suspend fun inspectEnvironment(
        openCodePort: Int,
        requireSystemd: Boolean = true,
        onLog: ((String) -> Unit)? = null,
    ): OpenCodeEnvInfo = withContext(Dispatchers.IO) {
        onLog?.invoke("$ Проверка окружения сервера (ОС, архитектура, curl, systemd, права, место на диске)…")
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
                throw IllegalStateException("На сервере не установлен curl. Установите curl (например, sudo apt install -y curl) и повторите шаг.")
            }
            if (!env.hasSystemd) {
                throw IllegalStateException("На сервере не найден активный systemd (/run/systemd/system). Постоянная фоновая служба требует systemd, иначе процесс завершится при закрытии SSH.")
            }
            if (env.freeSpaceMb in 0..99) {
                throw IllegalStateException("Недостаточно свободного места на диске (${env.freeSpaceMb} МБ). Освободите минимум 100 МБ и повторите шаг.")
            }
        }

        val freeLabel = if (env.freeSpaceMb >= 0) "${env.freeSpaceMb} МБ" else "н/д"
        val permLabel = when {
            env.isRoot -> "root"
            env.hasPasswordlessSudo -> "sudo (без пароля)"
            else -> "user-service + linger"
        }
        onLog?.invoke("✓ ${env.osName} ${env.arch}, свободно $freeLabel, systemd=да, права: $permLabel")
        env
    }

    suspend fun ensureOrUpdateOpenCodeBinary(
        env: OpenCodeEnvInfo,
        forceUpdate: Boolean,
        onLog: (String) -> Unit,
    ): Pair<String, String?> = withContext(Dispatchers.IO) {
        if (!forceUpdate && !env.opencodePath.isNullOrBlank()) {
            val ver = env.opencodeVersion ?: readBinaryVersion(env.opencodePath)
            onLog("✓ OpenCode уже установлен: ${env.opencodePath} (${ver ?: "версия не определена"}). Пропускаем повторную загрузку.")
            return@withContext env.opencodePath to ver
        }

        onLog("$ bash -lc 'curl -fsSL https://opencode.ai/install | bash'")
        val installRes = execStreaming(
            command = "bash -lc 'curl -fsSL https://opencode.ai/install | bash'",
            timeoutSec = 180,
            onLine = onLog,
        )
        if (installRes.exitCode != 0) {
            throw IllegalStateException("Установщик OpenCode завершился с кодом ${installRes.exitCode}")
        }

        val resolvedPath = resolveAbsoluteBinaryPath()
            ?: throw IllegalStateException("Не удалось найти установленный бинарник OpenCode ни в \$HOME/.opencode/bin/opencode, ни в PATH.")
        val version = readBinaryVersion(resolvedPath)
        onLog("✓ Абсолютный путь бинарника: $resolvedPath" + (version?.let { " ($it)" } ?: ""))
        resolvedPath to version
    }

    suspend fun migrateLegacyRuns(
        env: OpenCodeEnvInfo,
        onLog: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        onLog("$ Остановка временных служб (transient) и сиротских процессов opencode serve…")
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
        onLog("✓ Старые процессы и временные службы остановлены")
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
            throw IllegalStateException("systemd недоступен на сервере — невозможно создать постоянную службу")
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
                    onLog("→ Загрузка /etc/opencode-serve.env по SFTP (chmod 600)…")
                    sftpRepository.writeTextFile("/etc/opencode-serve.env", envContent, null, forceOverwrite = true)
                    sftpRepository.chmod("/etc/opencode-serve.env", 0x180)
                } else {
                    val tmpEnv = "$cleanHome/.opencode-serve.env.tmp"
                    onLog("→ Загрузка файла пароля по SFTP и перенос в /etc/opencode-serve.env (chmod 600)…")
                    sftpRepository.writeTextFile(tmpEnv, envContent, null, forceOverwrite = true)
                    runCatching { sftpRepository.chmod(tmpEnv, 0x180) }
                    val mvRes = execStreaming(
                        "sudo -n mv ${shellQuote(tmpEnv)} /etc/opencode-serve.env && sudo -n chown root:root /etc/opencode-serve.env && sudo -n chmod 600 /etc/opencode-serve.env",
                        timeoutSec = 20,
                        onLine = onLog,
                        secretsToRedact = listOf(passwordSecret),
                    )
                    if (mvRes.exitCode != 0) {
                        throw IllegalStateException("Не удалось установить /etc/opencode-serve.env (код ${mvRes.exitCode})")
                    }
                }
            } else {
                val cfgDir = "$cleanHome/.config/opencode"
                execStreaming("mkdir -p ${shellQuote(cfgDir)} && chmod 700 ${shellQuote(cfgDir)}", 15, null)
                onLog("→ Загрузка $envFilePath по SFTP (chmod 600)…")
                sftpRepository.writeTextFile(envFilePath!!, envContent, null, forceOverwrite = true)
                runCatching { sftpRepository.chmod(envFilePath, 0x180) }
            }

            if (profileId != null) {
                secretStore?.saveOpenCodeServerAuth(profileId, effectiveUser, passwordSecret)
            }
            onLog("✓ Защита паролем (Basic Auth) настроена и сохранена в Android Keystore")
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
                onLog("→ Загрузка $targetPath по SFTP…")
                sftpRepository.writeTextFile(targetPath, unitContent, null, forceOverwrite = true)
                val res = execStreaming(
                    "systemctl daemon-reload && systemctl enable --now opencode-serve && systemctl restart opencode-serve",
                    timeoutSec = 35,
                    onLine = onLog,
                    secretsToRedact = redactList,
                )
                if (res.exitCode != 0) {
                    throw IllegalStateException("systemctl не смог запустить opencode-serve (код ${res.exitCode})")
                }
            } else {
                val stagingPath = "$cleanHome/.opencode-serve.service.tmp"
                onLog("→ Загрузка unit-файла по SFTP и установка в $targetPath через sudo…")
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
                    throw IllegalStateException("sudo systemctl не смог запустить opencode-serve (код ${res.exitCode})")
                }
            }
            onLog("✓ Постоянная системная служба opencode-serve включена (127.0.0.1:$cleanPort)")
            return@withContext false // isUserService = false
        } else {
            val userUnitDir = "$cleanHome/.config/systemd/user"
            val userUnitPath = "$userUnitDir/opencode-serve.service"
            execStreaming("mkdir -p ${shellQuote(userUnitDir)}", timeoutSec = 15, onLine = null)
            onLog("→ Загрузка user-службы $userUnitPath по SFTP…")
            sftpRepository.writeTextFile(userUnitPath, unitContent, null, forceOverwrite = true)

            val lingerRes = execStreaming(
                "loginctl enable-linger ${shellQuote(env.userName)} 2>&1 || true",
                timeoutSec = 15,
                onLine = onLog,
                secretsToRedact = redactList,
            )
            if (lingerRes.exitCode == 0) {
                onLog("✓ loginctl enable-linger выполнен для ${env.userName}")
            }

            val res = execStreaming(
                "bash -lc 'systemctl --user daemon-reload && systemctl --user enable --now opencode-serve && systemctl --user restart opencode-serve'",
                timeoutSec = 35,
                onLine = onLog,
                secretsToRedact = redactList,
            )
            if (res.exitCode != 0) {
                throw IllegalStateException("systemctl --user не смог запустить opencode-serve (код ${res.exitCode}). Убедитесь, что активна user systemd сессия или есть доступ root/sudo.")
            }
            onLog("✓ Постоянная user-служба opencode-serve включена с linger (127.0.0.1:$cleanPort)")
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
                if (!initial.serviceActive) append("служба не active (${initial.serviceActiveDetail}); ")
                if (!initial.portListening) append("порт 127.0.0.1:$cleanPort не слушается; ")
                if (!initial.sessionJsonOk) append("GET /session не ответил JSON (${initial.sessionJsonDetail})")
            }
            throw IllegalStateException("Автопроверка не пройдена: $detail")
        }

        if (!verifyPersistence) {
            return@withContext initial
        }

        onLog("$ Проверка устойчивости: выполняем systemctl restart opencode-serve…")
        val restartCmd = buildSystemctlCommand(env, isUserService, "restart")
        val restartRes = execStreaming(restartCmd, timeoutSec = 30, onLine = onLog, secretsToRedact = redactList)
        if (restartRes.exitCode != 0) {
            throw IllegalStateException("Тест устойчивости (systemctl restart) завершился с кодом ${restartRes.exitCode}")
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
            throw IllegalStateException("После systemctl restart сервер не поднялся: ${afterRestart.sessionJsonDetail.ifBlank { afterRestart.serviceActiveDetail }}")
        }
        onLog("✓ Служба пережила перезапуск и готова к автозапуску после перезагрузки VPS")

        afterRestart.copy(
            restartSurvived = true,
            restartSurvivedDetail = "Перезапуск пройден — после перезагрузки VPS служба стартует сама",
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
            throw IllegalStateException("Постоянная systemd-служба opencode-serve ещё не настроена. Нажмите «Установить и настроить OpenCode».")
        }
        val cmd = buildSystemctlCommand(env, env.isUserService, "start")
        onLog("$ $cmd")
        val res = execStreaming(cmd, timeoutSec = 30, onLine = onLog)
        if (res.exitCode != 0) {
            throw IllegalStateException("Не удалось запустить службу opencode-serve (код ${res.exitCode})")
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
            throw IllegalStateException("Не удалось остановить службу opencode-serve (код ${res.exitCode})")
        }
        onLog("✓ Служба opencode-serve остановлена")
    }

    suspend fun restartOpenCodeServer(
        profileId: Long?,
        port: Int,
        localTunnelPort: Int?,
        onLog: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val env = inspectEnvironment(openCodePort = port, requireSystemd = true)
        if (!env.hasPersistentUnit) {
            throw IllegalStateException("Постоянная служба opencode-serve не установлена. Используйте «Починить» для создания службы.")
        }
        val cmd = buildSystemctlCommand(env, env.isUserService, "restart")
        onLog("$ $cmd")
        val res = execStreaming(cmd, timeoutSec = 30, onLine = onLog)
        if (res.exitCode != 0) {
            throw IllegalStateException("Не удалось перезапустить opencode-serve (код ${res.exitCode})")
        }
        val ready = waitForServerReadyAfterRestart(
            profileId = profileId,
            isUserService = env.isUserService,
            initialTunnelPort = localTunnelPort,
            onProgress = onLog,
        )
        if (!ready.serviceActive) {
            throw IllegalStateException("Служба opencode-serve не перешла в active: ${ready.detail}")
        }
        if (!ready.serverAlive) {
            throw IllegalStateException("Сервер OpenCode не ответил на /session за 40 с: ${ready.detail}")
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
            throw IllegalStateException("Постоянная служба opencode-serve не установлена. Используйте «Починить» для создания службы.")
        }
        val cmd = buildSystemctlCommand(env, env.isUserService, "restart")
        onLog?.invoke("$ $cmd")
        val res = execStreaming(cmd, timeoutSec = 30, onLine = onLog)
        if (res.exitCode != 0) {
            return@withContext ServiceRestartCheckResult(
                serviceActive = false,
                isUserService = env.isUserService,
                stateText = "error(${res.exitCode})",
                detail = "systemctl restart завершился с кодом ${res.exitCode}",
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
                    detail = "Служба opencode-serve активна (active)",
                )
            }
            if (stateText == "failed" || stateText == "inactive") {
                return@withContext ServiceRestartCheckResult(
                    serviceActive = false,
                    isUserService = env.isUserService,
                    stateText = stateText,
                    detail = "Служба opencode-serve в состоянии $stateText после перезапуска",
                )
            }
            if (idx < 3) delay(1_500L)
        }

        ServiceRestartCheckResult(
            serviceActive = stateText == "active",
            isUserService = env.isUserService,
            stateText = stateText,
            detail = "Служба opencode-serve не перешла в active (статус: $stateText)",
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
        var lastDetail = "Нет ответа от /session"

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
                        detail = "Служба opencode-serve остановилась (статус: $svcState)",
                        activeTunnelPort = tunnelPort,
                    )
                }
            }

            // Ensure tunnel port is available
            if (tunnelPort == null) {
                onProgress?.invoke("Попытка $attempt/$RESTART_POLL_MAX_ATTEMPTS (${elapsedSec} с): пересоздание SSH-туннеля…")
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
                    onProgress?.invoke("Сервер отвечает: ${probe.detail}")
                    return@withContext ServerReadyCheckResult(
                        serviceActive = lastServiceState == "active" || lastServiceState == "activating",
                        serverAlive = true,
                        detail = probe.detail,
                        activeTunnelPort = tunnelPort,
                    )
                }

                if (probe.isConnectionError) {
                    onProgress?.invoke("Попытка $attempt/$RESTART_POLL_MAX_ATTEMPTS (${elapsedSec} с): обрыв туннеля, пересоздаём…")
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
                            onProgress?.invoke("Сервер отвечает: ${retryProbe.detail}")
                            return@withContext ServerReadyCheckResult(
                                serviceActive = lastServiceState == "active" || lastServiceState == "activating",
                                serverAlive = true,
                                detail = retryProbe.detail,
                                activeTunnelPort = rebuiltPort,
                            )
                        }
                    }
                } else {
                    onProgress?.invoke("Попытка $attempt/$RESTART_POLL_MAX_ATTEMPTS (${elapsedSec} с): ${probe.detail}")
                }
            } else {
                lastDetail = "SSH-туннель недоступен"
                onProgress?.invoke("Попытка $attempt/$RESTART_POLL_MAX_ATTEMPTS (${elapsedSec} с): ожидание SSH-туннеля…")
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
        onLog("$ Удаление службы opencode-serve…")

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
        onLog("✓ Служба opencode-serve удалена")
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
                "найдено моделей: $count"
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

        onLog?.invoke("✓ Конфигурация сохранена в ~/.config/opencode/opencode.json (chmod 600)")
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
                exposedPublicly -> "Внимание: обнаружен публичный сокет ($ssOut)"
                loopbackBound -> "127.0.0.1:$port (только локально)"
                else -> "порт $port не слушается"
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
                sessionJsonDetail = "SSH-туннель не активен"
            }

            if (serviceActive && portListening && sessionJsonOk) {
                onLog("✓ Проверка 1/3: служба opencode-serve — active")
                onLog("✓ Проверка 2/3: ss -ltn — $portListeningDetail")
                onLog("✓ Проверка 3/3: GET /session — $sessionJsonDetail")
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
                        "HTTP 401 (сервер запущен, включена Basic Auth)"
                    } else {
                        "HTTP 401 (требуется Basic Auth)"
                    },
                )
            }
            if (resp.code !in 200..299) {
                return@use SessionProbeResult(
                    alive = false,
                    isConnectionError = false,
                    detail = "HTTP ${resp.code}",
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
                )
            } else {
                SessionProbeResult(
                    alive = false,
                    isConnectionError = false,
                    detail = "Ответ не является JSON",
                )
            }
        }
    }.getOrElse { err ->
        SessionProbeResult(
            alive = false,
            isConnectionError = true,
            detail = err.message ?: "Нет соединения",
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
            ?: throw IllegalStateException("SSH-соединение не активно")

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
