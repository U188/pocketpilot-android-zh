package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.opencode.OpenCodeJsonParser
import com.example.data.opencode.UnifiedDiffBuilder
import com.example.data.sftp.SftpRepository
import com.example.data.ssh.SshKeyGenerator
import com.example.data.terminal.AnsiTerminalBuffer
import com.example.domain.model.AppSettings
import com.example.domain.model.DangerousCommandDetector
import com.example.domain.model.DiffLineType
import com.example.domain.model.OpenCodeEvent
import com.example.domain.model.OpenCodePart
import com.example.domain.model.RemoteFileEntry
import com.example.domain.model.SshKeyAlgorithm
import com.example.domain.model.ToolCallStatus
import com.example.service.SshConnectionForegroundService
import com.example.ui.editor.CodeLanguage
import com.example.ui.editor.SyntaxHighlighter
import com.example.ui.editor.SyntaxPalette
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("PocketPilot", appName)
    }

    @Test
    fun `launch app and navigate servers editor chat files and settings`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("add_server_fab").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("editor_name_input").assertIsDisplayed()
        composeRule.onNodeWithTag("editor_back_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("open_workspace_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        // Default workspace tab is Chat (disconnected state shows choose server CTA)
        composeRule.onNodeWithTag("chat_choose_server_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_files").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("files_choose_server_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_terminal").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal_choose_server_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_settings").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_manage_servers_row").assertIsDisplayed()
    }

    @Test
    fun `generated Ed25519 key loads cleanly in SSHJ`() = runTest {
        val generator = SshKeyGenerator()
        val generated = generator.generate(SshKeyAlgorithm.ED25519, "test@opencode")

        assertTrue(generated.privateKeyPem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(generated.publicKeyOpenSsh.startsWith("ssh-ed25519 "))
        assertTrue(generated.fingerprintSha256.startsWith("SHA256:"))

        val sshClient = SSHClient()
        val keyProvider = sshClient.loadKeys(generated.privateKeyPem, null, null)
        assertNotNull(keyProvider.public)
        assertNotNull(keyProvider.private)
    }

    @Test
    fun `sftp path helpers and permission formatting work`() {
        assertEquals("/", SftpRepository.normalizeRemotePath("///"))
        assertEquals("/home/ubuntu/app", SftpRepository.joinRemotePath("/home/ubuntu/", "app"))
        assertEquals("/home/ubuntu", SftpRepository.parentRemotePath("/home/ubuntu/app"))

        val dirEntry = RemoteFileEntry(
            name = "src",
            path = "/home/ubuntu/src",
            isDirectory = true,
            isSymlink = false,
            sizeBytes = 4096,
            modifiedAtEpochSec = 1_700_000_000L,
            permissionsMask = 0x1ED, // 0755
        )
        assertEquals("0755", dirEntry.octalPermissions)
        assertEquals("drwxr-xr-x", dirEntry.rwxPermissions)
    }

    @Test
    fun `syntax highlighter detects languages and highlights code`() {
        assertEquals(CodeLanguage.KOTLIN, CodeLanguage.detect("MainActivity.kt"))
        assertEquals(CodeLanguage.PYTHON, CodeLanguage.detect("server.py"))
        assertEquals(CodeLanguage.JAVASCRIPT, CodeLanguage.detect("index.tsx"))
        assertEquals(CodeLanguage.YAML, CodeLanguage.detect("docker-compose.yml"))
        assertEquals(CodeLanguage.BASH, CodeLanguage.detect(".env"))

        val highlighted = SyntaxHighlighter.highlight(
            code = "fun main() { val x = \"hello\" // comment\n}",
            language = CodeLanguage.KOTLIN,
            palette = SyntaxPalette.Dark,
        )
        assertTrue(highlighted.spanStyles.isNotEmpty())
    }

    @Test
    fun `opencode parser discovers openapi paths and parses sessions messages and providers`() {
        val docJson = """
            {
              "openapi": "3.1.0",
              "paths": {
                "/session/{id}/message": {},
                "/session/{id}/prompt_async": {},
                "/session/{id}/revert": {},
                "/session/{id}/fork": {},
                "/config/providers": {},
                "/agent": {}
              }
            }
        """.trimIndent()
        val paths = OpenCodeJsonParser.parseOpenApiDoc(docJson)
        assertEquals("message", paths.messagesListSuffix)
        assertTrue(paths.promptAsyncSupported)
        assertEquals("/config/providers", paths.providersPath)
        assertEquals("/agent", paths.agentsPath)
        assertTrue(paths.revertSupported)
        assertTrue(paths.forkSupported)

        val sessionsJson = """
            [
              {"id":"ses_1","title":"Fix auth","directory":"/srv/app","time":{"created":1700000000,"updated":1700000100}},
              {"id":"ses_2","title":"Add tests","directory":"/srv/app","time":{"created":1700000200,"updated":1700000500}}
            ]
        """.trimIndent()
        val sessions = OpenCodeJsonParser.parseSessions(sessionsJson)
        assertEquals(2, sessions.size)
        assertEquals("ses_2", sessions.first().id)

        val messagesJson = """
            [
              {
                "info": {"id":"msg_1","sessionID":"ses_2","role":"assistant","providerID":"anthropic","modelID":"claude-sonnet-4","agent":"build","time":{"created":1700000300000}},
                "parts": [
                  {"id":"p_reason","type":"reasoning","text":"Checking file layout"},
                  {"id":"p_tool","type":"tool","tool":"read","callID":"call_1","state":{"status":"completed","title":"Read Main.kt","input":{"filePath":"/srv/app/Main.kt"},"output":"fun main() {}"}},
                  {"id":"p_text","type":"text","text":"Updated `Main.kt`."}
                ]
              }
            ]
        """.trimIndent()
        val messages = OpenCodeJsonParser.parseMessages(messagesJson, "ses_2")
        assertEquals(1, messages.size)
        assertEquals(3, messages.first().parts.size)
        val toolPart = messages.first().parts[1] as OpenCodePart.Tool
        assertEquals("read", toolPart.toolName)
        assertEquals(ToolCallStatus.COMPLETED, toolPart.status)
        assertEquals("/srv/app/Main.kt", toolPart.filePath)

        val providersJson = """
            {
              "connected": ["anthropic"],
              "providers": [
                {
                  "id": "anthropic",
                  "name": "Anthropic",
                  "models": {
                    "claude-sonnet-4": {"id":"claude-sonnet-4","name":"Claude Sonnet 4"}
                  }
                },
                {
                  "id": "openai",
                  "name": "OpenAI",
                  "models": [{"id":"gpt-5","name":"GPT-5"}]
                }
              ]
            }
        """.trimIndent()
        val providers = OpenCodeJsonParser.parseProviders(providersJson)
        assertEquals(1, providers.size)
        assertEquals("anthropic", providers.first().id)
        assertEquals("claude-sonnet-4", providers.first().models.first().modelId)
    }

    @Test
    fun `opencode parser parses SSE streaming events`() {
        val partEventJson = """
            {
              "type": "message.part.updated",
              "properties": {
                "part": {"id":"p1","sessionID":"ses_1","messageID":"msg_1","type":"text","text":"Hello world"},
                "delta": " world"
              }
            }
        """.trimIndent()
        val partEvent = OpenCodeJsonParser.parseSseEvent(partEventJson) as OpenCodeEvent.PartUpdated
        assertEquals(" world", partEvent.delta)
        assertEquals("Hello world", (partEvent.part as OpenCodePart.Text).text)

        val busyEvent = OpenCodeJsonParser.parseSseEvent(
            """{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"busy"}}}""",
        ) as OpenCodeEvent.SessionStatusChanged
        assertEquals("ses_1", busyEvent.sessionId)
        assertTrue(busyEvent.isBusy)

        val idleEvent = OpenCodeJsonParser.parseSseEvent(
            """{"type":"session.idle","properties":{"sessionID":"ses_1"}}""",
        ) as OpenCodeEvent.SessionStatusChanged
        assertEquals("ses_1", idleEvent.sessionId)
        assertFalse(idleEvent.isBusy)

        val msgUpdated = OpenCodeJsonParser.parseSseEvent(
            """{"type":"message.updated","properties":{"info":{"id":"msg_2","sessionID":"ses_1","role":"user"}}}""",
        ) as OpenCodeEvent.MessageUpdated
        assertEquals("msg_2", msgUpdated.message.id)
        assertEquals("user", msgUpdated.message.role)
    }

    @Test
    fun `opencode parser handles permission asked replied and session diff events`() {
        val permAsked = OpenCodeJsonParser.parseSseEvent(
            """
            {
              "type": "permission.asked",
              "properties": {
                "id": "perm_42",
                "sessionID": "ses_1",
                "permission": "bash",
                "title": "Run shell command",
                "patterns": ["rm -rf build"],
                "metadata": {"command": "rm -rf build"}
              }
            }
            """.trimIndent(),
        ) as OpenCodeEvent.PermissionAsked
        assertEquals("perm_42", permAsked.request.id)
        assertEquals("bash", permAsked.request.permission)
        assertEquals("rm -rf build", permAsked.request.command)
        assertTrue(permAsked.request.isHighRisk)

        val permReplied = OpenCodeJsonParser.parseSseEvent(
            """{"type":"permission.replied","properties":{"requestID":"perm_42","sessionID":"ses_1"}}""",
        ) as OpenCodeEvent.PermissionReplied
        assertEquals("perm_42", permReplied.requestId)

        val diffEvent = OpenCodeJsonParser.parseSseEvent(
            """
            {
              "type": "session.diff",
              "properties": {
                "sessionID": "ses_1",
                "diff": [
                  {
                    "file": "/srv/app/Main.kt",
                    "before": "fun main() {\n    println(\"old\")\n}",
                    "after": "fun main() {\n    println(\"new\")\n    println(\"done\")\n}",
                    "additions": 2,
                    "deletions": 1
                  }
                ]
              }
            }
            """.trimIndent(),
        ) as OpenCodeEvent.SessionDiffUpdated
        assertEquals("ses_1", diffEvent.sessionId)
        assertEquals(1, diffEvent.diffs.size)
        val fileDiff = diffEvent.diffs.first()
        assertEquals(2, fileDiff.additions)
        assertEquals(1, fileDiff.deletions)
        assertTrue(fileDiff.lines.any { it.type == DiffLineType.REMOVED && it.content.contains("old") })
        assertTrue(fileDiff.lines.any { it.type == DiffLineType.ADDED && it.content.contains("new") })
    }

    @Test
    fun `unified diff builder computes clean hunks and parses patches`() {
        val lines = UnifiedDiffBuilder.buildDiffLines(
            before = "a\nb\nc",
            after = "a\nB\nc\nd",
        )
        assertTrue(lines.any { it.type == DiffLineType.HUNK_HEADER })
        assertEquals(1, lines.count { it.type == DiffLineType.REMOVED })
        assertEquals(2, lines.count { it.type == DiffLineType.ADDED })

        val parsedPatch = UnifiedDiffBuilder.parseUnifiedPatch(
            """
            --- a/test.txt
            +++ b/test.txt
            @@ -1,2 +1,2 @@
             keep
            -old
            +new
            """.trimIndent(),
        )
        assertEquals(4, parsedPatch.size)
        assertEquals(DiffLineType.HUNK_HEADER, parsedPatch[0].type)
        assertEquals(DiffLineType.ADDED, parsedPatch[3].type)
    }

    @Test
    fun `ansi terminal buffer renders sgr colors cursor moves osc title and alt screen`() {
        val term = AnsiTerminalBuffer(initialCols = 40, initialRows = 10)
        term.feed("\u001B]0;ubuntu@vps:~/app\u0007")
        term.feed("\u001B[1;32mOK\u001B[0m hello\r\n")
        term.feed("line2")
        val snap = term.snapshot()
        assertEquals("ubuntu@vps:~/app", snap.title)
        assertEquals("OK hello", snap.plainLines[0])
        assertEquals("line2", snap.plainLines[1])
        assertTrue(snap.lines[0].spanStyles.isNotEmpty())

        // Alternate screen (like vim/htop) isolates and restores main screen
        term.feed("\u001B[?1049hALT SCREEN")
        assertEquals("ALT SCREEN", term.snapshot().plainLines[0])
        term.feed("\u001B[?1049l")
        assertEquals("OK hello", term.snapshot().plainLines[0])
    }

    @Test
    fun `dangerous command detector flags destructive commands`() {
        assertTrue(DangerousCommandDetector.isDangerous("rm -rf /tmp/test"))
        assertTrue(DangerousCommandDetector.isDangerous("sudo shutdown -h now"))
        assertFalse(DangerousCommandDetector.isDangerous("git status -sb"))
        assertFalse(DangerousCommandDetector.isDangerous("ls -la"))
    }

    @Test
    fun `stage 5 security and background settings have safe defaults`() {
        val defaults = AppSettings()
        assertTrue(defaults.keepAliveInBackground)
        assertFalse(defaults.biometricLockEnabled)
        assertEquals(60, defaults.biometricLockTimeoutSec)
        assertFalse(defaults.secureScreenEnabled)
        assertEquals("opencode_ssh_channel", SshConnectionForegroundService.CHANNEL_ID)
    }

    @Test
    fun `ssh security provider creates android-safe sshj client config without throwing`() {
        val config = com.example.data.ssh.SshSecurityProvider.createSshConfig()
        val client = net.schmizz.sshj.SSHClient(config)
        assertNotNull(client)
        client.close()
    }

    @Test
    fun `crash reporter persists reads and clears uncaught stack traces`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.example.crash.CrashReporter.clearPendingCrash(context)
        assertEquals(null, com.example.crash.CrashReporter.readPendingCrash(context))

        com.example.crash.CrashReporter.saveCrash(
            context = context,
            threadName = "ssh-worker",
            throwable = IllegalStateException("Simulated SSH provider failure"),
        )

        val saved = com.example.crash.CrashReporter.readPendingCrash(context)
        assertNotNull(saved)
        assertEquals("ssh-worker", saved!!.threadName)
        assertTrue(saved.exceptionTitle.contains("Simulated SSH provider failure"))
        assertTrue(saved.stackTrace.contains("IllegalStateException"))

        com.example.crash.CrashReporter.clearPendingCrash(context)
        assertEquals(null, com.example.crash.CrashReporter.readPendingCrash(context))
    }

    @Test
    fun `opencode systemd unit binds strictly to 127_0_0_1 with restart always and env file`() {
        val unit = com.example.data.opencode.OpenCodeConfigHelper.buildSystemdUnit(
            opencodeBinaryPath = "/home/ubuntu/.opencode/bin/opencode",
            userName = "ubuntu",
            homeDir = "/home/ubuntu",
            port = 4096,
            isUserService = false,
            envFilePath = "/etc/opencode-serve.env",
        )
        assertTrue(unit.contains("ExecStart=/home/ubuntu/.opencode/bin/opencode serve --hostname 127.0.0.1 --port 4096"))
        assertTrue(unit.contains("Environment=HOME=/home/ubuntu"))
        assertTrue(unit.contains("WorkingDirectory=/home/ubuntu"))
        assertTrue(unit.contains("EnvironmentFile=/etc/opencode-serve.env"))
        assertTrue(unit.contains("Restart=always"))
        assertTrue(unit.contains("RestartSec=3"))
        assertTrue(unit.contains("User=ubuntu"))
        assertTrue(unit.contains("WantedBy=multi-user.target"))
        assertFalse(unit.contains("0.0.0.0"))

        val userUnit = com.example.data.opencode.OpenCodeConfigHelper.buildSystemdUnit(
            opencodeBinaryPath = "/home/dev/.opencode/bin/opencode",
            userName = "dev",
            homeDir = "/home/dev",
            port = 4096,
            isUserService = true,
            envFilePath = "/home/dev/.config/opencode/opencode-serve.env",
        )
        assertTrue(userUnit.contains("WantedBy=default.target"))
        assertFalse(userUnit.contains("User=dev"))
    }

    @Test
    fun `opencode installer builds 5 idempotent steps and strong password env file`() {
        val steps = com.example.data.opencode.OpenCodeConfigHelper.buildDefaultSteps(
            port = 4096,
            alreadyInstalled = true,
            forceUpdateBinary = false,
            enablePasswordAuth = true,
            verifyPersistence = true,
        )
        assertEquals(5, steps.size)
        assertTrue(steps[1].title.contains("Проверка бинарника"))
        assertTrue(steps[2].id == com.example.data.opencode.OpenCodeConfigHelper.STEP_MIGRATE)
        assertTrue(steps[4].commandPreview.contains("systemctl restart"))

        val pass = com.example.data.opencode.OpenCodeConfigHelper.generateStrongPassword(28)
        assertEquals(28, pass.length)
        val envFile = com.example.data.opencode.OpenCodeConfigHelper.buildEnvFileContent("opencode", pass)
        assertTrue(envFile.contains("OPENCODE_SERVER_USERNAME=opencode"))
        assertTrue(envFile.contains("OPENCODE_SERVER_PASSWORD=$pass"))
    }

    @Test
    fun `opencode config merge preserves existing settings and adds openai-compatible provider`() {
        val existing = """
            {
              "theme": "dark",
              "provider": {
                "anthropic": {
                  "name": "Anthropic"
                }
              }
            }
        """.trimIndent()
        val merged = com.example.data.opencode.OpenCodeConfigHelper.mergeOpenAiCompatibleProvider(
            existingJson = existing,
            config = com.example.domain.model.CustomProviderConfig(
                providerId = "my-proxy",
                providerName = "My Proxy",
                baseUrl = "https://proxy.example.com/v1/",
                apiKey = "sk-test-secret-1234567890",
                modelId = "deepseek-chat",
                modelDisplayName = "DeepSeek Chat",
            ),
        )
        val root = org.json.JSONObject(merged)
        assertEquals("dark", root.getString("theme"))
        assertEquals("my-proxy/deepseek-chat", root.getString("model"))
        val providers = root.getJSONObject("provider")
        assertTrue(providers.has("anthropic"))
        val custom = providers.getJSONObject("my-proxy")
        assertEquals("@ai-sdk/openai-compatible", custom.getString("npm"))
        assertEquals("https://proxy.example.com/v1", custom.getJSONObject("options").getString("baseURL"))
        assertEquals("sk-test-secret-1234567890", custom.getJSONObject("options").getString("apiKey"))
        assertEquals("DeepSeek Chat", custom.getJSONObject("models").getJSONObject("deepseek-chat").getString("name"))
    }

    @Test
    fun `log sanitizer redacts api keys basic auth env passwords and explicit secrets`() {
        val raw = """Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.secret Authorization: Basic dXNlcjpwYXNzMTIzNA== OPENCODE_SERVER_PASSWORD=TopSecretValue "apiKey": "my-secret" key=sk-1234567890abcdef pass=SuperSecretPass"""
        val sanitized = com.example.data.opencode.OpenCodeConfigHelper.sanitizeLogs(
            raw = raw,
            explicitSecrets = listOf("SuperSecretPass"),
        )
        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(sanitized.contains("dXNlcjpwYXNzMTIzNA=="))
        assertFalse(sanitized.contains("TopSecretValue"))
        assertFalse(sanitized.contains("my-secret"))
        assertFalse(sanitized.contains("sk-1234567890abcdef"))
        assertFalse(sanitized.contains("SuperSecretPass"))
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun `provider helper masks keys parses configured providers updates keys and removes safely`() {
        val helper = com.example.data.opencode.OpenCodeConfigHelper
        assertEquals("••••9876", helper.maskApiKey("sk-live-secret-9876"))
        assertEquals("••••", helper.maskApiKey("1234"))
        assertEquals(null, helper.maskApiKey("   "))

        val existing = """
            {
              // user comment preserved safely when reading
              "theme": "dark",
              "model": "openrouter/openai/gpt-4o",
              "provider": {
                "anthropic": {
                  "name": "Anthropic",
                  "options": {"apiKey": "sk-ant-11112222"}
                },
                "openrouter": {
                  "npm": "@ai-sdk/openai-compatible",
                  "name": "OpenRouter",
                  "options": {
                    "baseURL": "https://openrouter.ai/api/v1",
                    "apiKey": "sk-or-v1-abcdef9876",
                    "timeout": 60000
                  },
                  "models": {
                    "openai/gpt-4o": {"name": "GPT-4o", "limit": {"context": 128000}},
                    "deepseek/deepseek-chat": {"name": "DeepSeek Chat"}
                  }
                }
              }
            }
        """.trimIndent()

        val parsed = helper.parseConfiguredProviders(existing)
        assertEquals(2, parsed.size)
        val openRouter = parsed.first { it.providerId == "openrouter" }
        assertTrue(openRouter.isDefaultProvider)
        assertEquals("openai/gpt-4o", openRouter.defaultModelId)
        assertEquals("••••9876", openRouter.maskedApiKey)
        assertTrue(openRouter.hasApiKey)
        assertEquals(2, openRouter.models.size)

        // Multi-model edit keeping existing apiKey (apiKey = null) and preserving extra options & other providers
        val merged = helper.mergeOpenAiCompatibleProvider(
            existingJson = existing,
            request = com.example.domain.model.CustomProviderSaveRequest(
                providerId = "openrouter",
                providerName = "OpenRouter Pro",
                baseUrl = "https://openrouter.ai/api/v1/",
                apiKey = null,
                selectedModels = listOf(
                    com.example.domain.model.CustomModelEntry("openai/gpt-4o", "GPT-4o Custom", selected = true),
                    com.example.domain.model.CustomModelEntry("qwen/qwen-2.5-coder", "Qwen Coder", selected = true),
                ),
                defaultModelId = "qwen/qwen-2.5-coder",
                replaceProviderModels = true,
            ),
        )
        val mergedRoot = org.json.JSONObject(merged)
        assertEquals("dark", mergedRoot.getString("theme"))
        assertEquals("openrouter/qwen/qwen-2.5-coder", mergedRoot.getString("model"))
        val orObj = mergedRoot.getJSONObject("provider").getJSONObject("openrouter")
        assertEquals("OpenRouter Pro", orObj.getString("name"))
        assertEquals("sk-or-v1-abcdef9876", orObj.getJSONObject("options").getString("apiKey"))
        assertEquals(60000, orObj.getJSONObject("options").getInt("timeout"))
        assertTrue(mergedRoot.getJSONObject("provider").has("anthropic"))
        assertEquals(2, orObj.getJSONObject("models").length())
        assertEquals(128000, orObj.getJSONObject("models").getJSONObject("openai/gpt-4o").getJSONObject("limit").getInt("context"))

        // Rotate key only
        val rotated = helper.updateProviderApiKey(merged, "openrouter", "sk-or-v1-newkey4321")
        val rotatedProviders = helper.parseConfiguredProviders(rotated)
        assertEquals("••••4321", rotatedProviders.first { it.providerId == "openrouter" }.maskedApiKey)

        // Delete openrouter — anthropic and theme stay intact, dangling default model cleared
        val removed = helper.removeProvider(rotated, "openrouter")
        val removedRoot = org.json.JSONObject(removed)
        assertEquals("dark", removedRoot.getString("theme"))
        assertTrue(removedRoot.getJSONObject("provider").has("anthropic"))
        assertFalse(removedRoot.getJSONObject("provider").has("openrouter"))
        assertFalse(removedRoot.has("model"))
    }

    @Test
    fun `openai models list chat completion parser and curl config escaping work`() {
        val helper = com.example.data.opencode.OpenCodeConfigHelper
        val modelsJson = """
            {
              "object": "list",
              "data": [
                {"id": "gpt-4o-mini", "owned_by": "openai"},
                {"id": "deepseek-chat", "owned_by": "deepseek"}
              ]
            }
        """.trimIndent()
        val models = helper.parseOpenAiModelsResponse(modelsJson)
        assertEquals(2, models.size)
        assertEquals("deepseek-chat", models[0].id)
        assertEquals("deepseek-chat", models[0].displayName)
        assertEquals("gpt-4o-mini", models[1].id)

        val completionJson = """
            {
              "id": "chatcmpl-1",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "Готово"}}
              ]
            }
        """.trimIndent()
        assertEquals("Готово", helper.parseChatCompletionReply(completionJson))

        val escaped = helper.escapeCurlConfigValue("""{"text":"a\"b\c"}""")
        assertEquals("""{\"text\":\"a\\\"b\\c\"}""", escaped)
    }

    @Test
    fun `github release update checker parses tag build number asset url throttle and errors`() {
        assertEquals(15, com.example.data.update.AppUpdateManager.parseBuildNumberFromTag("v1.0.15"))
        assertEquals(42, com.example.data.update.AppUpdateManager.parseBuildNumberFromTag("1.2.42"))
        assertEquals(null, com.example.data.update.AppUpdateManager.parseBuildNumberFromTag("latest"))
        assertEquals("1.0.15", com.example.data.update.AppUpdateManager.formatDisplayVersion("v1.0.15", 15))

        val releaseJson = """
            {
              "tag_name": "v1.0.15",
              "name": "PocketPilot 1.0.15",
              "body": "Исправлены ошибки SSH и добавлена проверка обновлений.",
              "html_url": "https://github.com/renderr03/OPENCODE-APK-FOR-ANDROID-WITH-AI/releases/tag/v1.0.15",
              "assets": [
                {
                  "name": "checksums.txt",
                  "browser_download_url": "https://example.com/checksums.txt"
                },
                {
                  "name": "app-debug.apk",
                  "browser_download_url": "https://github.com/renderr03/OPENCODE-APK-FOR-ANDROID-WITH-AI/releases/download/v1.0.15/app-debug.apk"
                }
              ]
            }
        """.trimIndent()
        val release = com.example.data.update.AppUpdateManager.parseLatestReleaseJson(releaseJson)
        assertEquals("v1.0.15", release.tagName)
        assertEquals("1.0.15", release.displayVersion)
        assertEquals(15, release.buildNumber)
        assertEquals("app-debug.apk", release.apkAssetName)
        assertTrue(release.apkDownloadUrl!!.endsWith("/app-debug.apk"))
        assertTrue(com.example.data.update.AppUpdateManager.isUpdateAvailable(release, currentVersionCode = 14))
        assertFalse(com.example.data.update.AppUpdateManager.isUpdateAvailable(release, currentVersionCode = 15))

        val sixHours = com.example.data.update.AppUpdateManager.AUTO_CHECK_INTERVAL_MS
        assertTrue(com.example.data.update.AppUpdateManager.shouldAutoCheck(true, 0L, 1_000L))
        assertFalse(com.example.data.update.AppUpdateManager.shouldAutoCheck(false, 0L, 1_000L))
        assertFalse(com.example.data.update.AppUpdateManager.shouldAutoCheck(true, 10_000L, 10_000L + sixHours - 1L))
        assertTrue(com.example.data.update.AppUpdateManager.shouldAutoCheck(true, 10_000L, 10_000L + sixHours))

        assertTrue(com.example.data.update.AppUpdateManager.mapHttpErrorToMessage(404).contains("404"))
        assertTrue(
            com.example.data.update.AppUpdateManager.mapHttpErrorToMessage(403, rateLimitRemaining = "0")
                .contains("лимит", ignoreCase = true),
        )
        assertTrue(
            com.example.data.update.AppUpdateManager.mapHttpErrorToMessage(429)
                .contains("лимит", ignoreCase = true),
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val unknownSourcesIntent = com.example.data.update.AppUpdateManager.buildUnknownSourcesSettingsIntent(context)
        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, unknownSourcesIntent.action)
        assertEquals("package:${context.packageName}", unknownSourcesIntent.dataString)

        val updatesDir = java.io.File(context.filesDir, com.example.data.update.AppUpdateManager.UPDATES_DIR_NAME)
        updatesDir.mkdirs()
        val fakeApk = java.io.File(updatesDir, "test-update.apk")
        fakeApk.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00))
        assertTrue(com.example.data.update.AppUpdateManager.isValidApkMagic(fakeApk))

        val installIntent = com.example.data.update.AppUpdateManager.buildInstallApkIntent(context, fakeApk)
        assertEquals(android.content.Intent.ACTION_VIEW, installIntent.action)
        assertEquals("application/vnd.android.package-archive", installIntent.type)
        assertTrue(installIntent.data.toString().startsWith("content://${context.packageName}.fileprovider/"))
        assertTrue((installIntent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        fakeApk.delete()

        val badApk = java.io.File(updatesDir, "bad.apk")
        badApk.writeText("<html>not an apk</html>")
        assertFalse(com.example.data.update.AppUpdateManager.isValidApkMagic(badApk))
        badApk.delete()

        assertEquals("512 Б", com.example.data.update.AppUpdateManager.formatBytes(512L))
        assertEquals("1.5 МБ", com.example.data.update.AppUpdateManager.formatBytes((1.5 * 1024 * 1024).toLong()))
    }
}
