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
        assertEquals("OpenCode", appName)
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
}
