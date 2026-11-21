package com.example

import com.example.data.git.GitParser
import com.example.data.opencode.ChatMarkdownExporter
import com.example.data.opencode.OpenCodeApiClient
import com.example.data.repository.PromptTemplateRepository
import com.example.domain.model.AttachedFileRef
import com.example.domain.model.AttachedImageRef
import com.example.domain.model.DiffLineType
import com.example.domain.model.GitFileStatus
import com.example.domain.model.OpenCodeMessage
import com.example.domain.model.OpenCodePart
import com.example.domain.model.PromptTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GitAndMobileChatTest {

    @Test
    fun gitParser_parsesPorcelainV2StatusAccurately() {
        val sample = """
            # branch.oid 1a2b3c4d5e6f7a8b9c0d
            # branch.head main
            # branch.upstream origin/main
            # branch.ab +2 -1
            1 M. N... 100644 100644 100644 abc def src/Main.kt
            1 .M N... 100644 100644 100644 abc def README.md
            1 MM N... 100644 100644 100644 abc def app/build.gradle.kts
            2 R. N... 100644 100644 100644 abc def R100 new/File.kt	old/File.kt
            ? notes/todo.txt
        """.trimIndent()

        val status = GitParser.parsePorcelainV2(sample, "/root/project")

        assertTrue(status.summary.isGitRepo)
        assertEquals("main", status.summary.branchName)
        assertEquals("origin/main", status.summary.upstreamName)
        assertEquals(2, status.summary.aheadCount)
        assertEquals(1, status.summary.behindCount)
        assertFalse(status.summary.isDetachedHead)

        assertEquals(3, status.stagedFiles.size)
        assertEquals(2, status.unstagedFiles.size)
        assertEquals(1, status.untrackedFiles.size)
        assertEquals(6, status.totalChangesCount)

        val renamed = status.stagedFiles.first { it.path == "new/File.kt" }
        assertEquals(GitFileStatus.RENAMED, renamed.status)
        assertEquals("old/File.kt", renamed.oldPath)
        assertEquals("notes/todo.txt", status.untrackedFiles.first().path)
    }

    @Test
    fun gitParser_validatesBranchNamesAndQuotesSafely() {
        assertTrue(GitParser.isValidBranchName("feature/git-panel"))
        assertTrue(GitParser.isValidBranchName("fix_401.v2"))
        assertFalse(GitParser.isValidBranchName(""))
        assertFalse(GitParser.isValidBranchName("-leading-dash"))
        assertFalse(GitParser.isValidBranchName("has space"))
        assertFalse(GitParser.isValidBranchName("bad..dots"))
        assertFalse(GitParser.isValidBranchName("bad~1"))
        assertEquals("'it'\"'\"'s a file.kt'", GitParser.shellQuote("it's a file.kt"))
    }

    @Test
    fun gitParser_parsesBranchesCommitsAndUnifiedDiff() {
        val branchRaw = """
            *	main	refs/heads/main	origin/main
             	dev	refs/heads/dev	
             	origin/main	refs/remotes/origin/main	
             	origin/HEAD	refs/remotes/origin/HEAD	
        """.trimIndent()
        val branches = GitParser.parseBranches(branchRaw)
        assertEquals(3, branches.size)
        assertTrue(branches[0].isCurrent)
        assertEquals("main", branches[0].name)
        assertTrue(branches.last().isRemote)

        val sep = GitParser.LOG_FIELD_SEP
        val rec = GitParser.LOG_RECORD_SEP
        val logRaw = "a1b2c3d${sep}a1b2c3d4e5f60708${sep}feat: add git panel${sep}Alex${sep}2 hours ago$rec"
        val commits = GitParser.parseCommits(logRaw)
        assertEquals(1, commits.size)
        assertEquals("a1b2c3d", commits[0].shortHash)
        assertEquals("feat: add git panel", commits[0].subject)

        val diffRaw = """
            @@ -10,3 +10,3 @@ fun test() {
             unchanged
            -oldLine
            +newLine
        """.trimIndent()
        val lines = GitParser.parseUnifiedDiff(diffRaw)
        assertEquals(4, lines.size)
        assertEquals(DiffLineType.HUNK_HEADER, lines[0].type)
        assertEquals(DiffLineType.CONTEXT, lines[1].type)
        assertEquals(10, lines[1].oldLineNumber)
        assertEquals(DiffLineType.REMOVED, lines[2].type)
        assertEquals(11, lines[2].oldLineNumber)
        assertEquals(DiffLineType.ADDED, lines[3].type)
        assertEquals(11, lines[3].newLineNumber)
    }

    @Test
    fun promptTemplates_extractsSlashQueryAndAppliesCleanly() {
        assertEquals("", PromptTemplateRepository.extractActiveSlashQuery("/"))
        assertEquals("fix", PromptTemplateRepository.extractActiveSlashQuery("/fix"))
        assertEquals("rev", PromptTemplateRepository.extractActiveSlashQuery("Проверь модуль /rev"))
        assertNull(PromptTemplateRepository.extractActiveSlashQuery("https://example.com/path"))
        assertNull(PromptTemplateRepository.extractActiveSlashQuery("/fix уже с пробелом"))

        val defaults = PromptTemplateRepository.defaultTemplates()
        val filtered = PromptTemplateRepository.filterBySlashQuery(defaults, "fix")
        assertEquals(1, filtered.size)
        assertEquals("fix", filtered[0].slashCommand)

        val tpl = PromptTemplate(title = "Fix", slashCommand = "fix", body = "Найди и исправь баг.")
        assertEquals(
            "Найди и исправь баг.",
            PromptTemplateRepository.applyTemplateToComposer("/fi", tpl),
        )
        assertEquals(
            "Контекст:\nНайди и исправь баг.",
            PromptTemplateRepository.applyTemplateToComposer("Контекст: /fi", tpl),
        )
    }

    @Test
    fun openCodeApiClient_serializesMultimodalImageParts() {
        val parts = OpenCodeApiClient.buildMessagePartsPayload(
            text = "Что на этом скриншоте?",
            attachments = listOf(AttachedFileRef("/root/app/Main.kt", "Main.kt")),
            imageAttachments = listOf(
                AttachedImageRef(
                    id = "img1",
                    filename = "screen.jpg",
                    mimeType = "image/jpeg",
                    base64DataUrl = "data:image/jpeg;base64,AAAA",
                    byteSize = 4,
                    previewBytes = byteArrayOf(1, 2),
                ),
            ),
        )

        assertEquals(3, parts.length())
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertTrue(parts.getJSONObject(0).getString("text").contains("@/root/app/Main.kt"))
        assertEquals("file", parts.getJSONObject(1).getString("type"))
        assertEquals("file:///root/app/Main.kt", parts.getJSONObject(1).getString("url"))
        assertEquals("file", parts.getJSONObject(2).getString("type"))
        assertEquals("image/jpeg", parts.getJSONObject(2).getString("mime"))
        assertEquals("data:image/jpeg;base64,AAAA", parts.getJSONObject(2).getString("url"))
    }

    @Test
    fun chatMarkdownExporter_formatsConversationCleanly() {
        val md = ChatMarkdownExporter.exportSession(
            sessionTitle = "Рефакторинг SSH",
            workspacePath = "/root/pocketpilot",
            messages = listOf(
                OpenCodeMessage(
                    id = "m1",
                    sessionId = "s1",
                    role = "user",
                    parts = listOf(OpenCodePart.Text("p1", "m1", "s1", "Проверь соединение")),
                ),
                OpenCodeMessage(
                    id = "m2",
                    sessionId = "s1",
                    role = "assistant",
                    modelId = "claude-sonnet",
                    parts = listOf(OpenCodePart.Text("p2", "m2", "s1", "Готово, всё работает.")),
                ),
            ),
        )

        assertTrue(md.contains("# Рефакторинг SSH"))
        assertTrue(md.contains("`/root/pocketpilot`"))
        assertTrue(md.contains("## 用户"))
        assertTrue(md.contains("## 助手"))
        assertTrue(md.contains("Готово, всё работает."))
}
