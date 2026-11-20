package com.example.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.editor.CodeLanguage
import com.example.ui.editor.SyntaxHighlighter
import com.example.ui.editor.SyntaxPalette
import com.example.ui.theme.MonoFontFamily

private sealed interface MarkdownBlock {
    data class TextBlock(val markdown: String) : MarkdownBlock
    data class CodeBlock(val languageHint: String, val code: String) : MarkdownBlock
}

@Composable
fun MarkdownMessageContent(
    markdown: String,
    onCodeCopied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    SelectionContainer(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.TextBlock -> {
                        val styled = remember(block.markdown, inlineCodeBg, primaryColor) {
                            renderInlineMarkdown(block.markdown, inlineCodeBg, primaryColor)
                        }
                        if (styled.text.isNotBlank()) {
                            Text(
                                text = styled,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    is MarkdownBlock.CodeBlock -> {
                        MarkdownCodeBlockCard(
                            languageHint = block.languageHint,
                            code = block.code,
                            darkTheme = darkTheme,
                            onCodeCopied = onCodeCopied,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownCodeBlockCard(
    languageHint: String,
    code: String,
    darkTheme: Boolean,
    onCodeCopied: () -> Unit,
) {
    val context = LocalContext.current
    val language = remember(languageHint) { detectFenceLanguage(languageHint) }
    val highlighted = remember(code, language, darkTheme) {
        SyntaxHighlighter.highlight(
            code = code,
            language = language,
            palette = if (darkTheme) SyntaxPalette.Dark else SyntaxPalette.Light,
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = languageHint.ifBlank { language.label },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Code", code))
                        onCodeCopied()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_copy_code),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Text(
                text = highlighted,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MonoFontFamily,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}

private fun parseMarkdownBlocks(input: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = input.split('\n')
    val textBuffer = StringBuilder()
    val codeBuffer = StringBuilder()
    var inCode = false
    var fenceLang = ""

    fun flushText() {
        if (textBuffer.isNotEmpty()) {
            blocks.add(MarkdownBlock.TextBlock(textBuffer.toString().trimEnd()))
            textBuffer.setLength(0)
        }
    }

    for (line in lines) {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            if (!inCode) {
                flushText()
                inCode = true
                fenceLang = trimmed.removePrefix("```").trim()
                codeBuffer.setLength(0)
            } else {
                blocks.add(MarkdownBlock.CodeBlock(fenceLang, codeBuffer.toString().trimEnd('\n')))
                codeBuffer.setLength(0)
                inCode = false
                fenceLang = ""
            }
        } else if (inCode) {
            if (codeBuffer.isNotEmpty()) codeBuffer.append('\n')
            codeBuffer.append(line)
        } else {
            if (textBuffer.isNotEmpty()) textBuffer.append('\n')
            textBuffer.append(line)
        }
    }

    if (inCode) {
        blocks.add(MarkdownBlock.CodeBlock(fenceLang, codeBuffer.toString()))
    } else {
        flushText()
    }
    return blocks
}

private fun renderInlineMarkdown(
    text: String,
    inlineCodeBg: Color,
    accentColor: Color,
): AnnotatedString = buildAnnotatedString {
    val lines = text.split('\n')
    lines.forEachIndexed { idx, rawLine ->
        if (idx > 0) append('\n')
        val line = rawLine.trimEnd()
        when {
            line.startsWith("### ") -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = accentColor))
                appendInlineTokens(line.removePrefix("### "), inlineCodeBg)
                pop()
            }

            line.startsWith("## ") -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = accentColor))
                appendInlineTokens(line.removePrefix("## "), inlineCodeBg)
                pop()
            }

            line.startsWith("# ") -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp, color = accentColor))
                appendInlineTokens(line.removePrefix("# "), inlineCodeBg)
                pop()
            }

            line.startsWith("> ") -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append("▎ ")
                appendInlineTokens(line.removePrefix("> "), inlineCodeBg)
                pop()
            }

            line.startsWith("- ") || line.startsWith("* ") -> {
                append("• ")
                appendInlineTokens(line.substring(2), inlineCodeBg)
            }

            else -> appendInlineTokens(line, inlineCodeBg)
        }
    }
}

private fun AnnotatedString.Builder.appendInlineTokens(
    line: String,
    inlineCodeBg: Color,
) {
    var i = 0
    while (i < line.length) {
        when {
            line.startsWith("`", i) -> {
                val end = line.indexOf('`', i + 1)
                if (end > i + 1) {
                    pushStyle(SpanStyle(fontFamily = MonoFontFamily, background = inlineCodeBg))
                    append(line.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append(line[i])
                    i++
                }
            }

            line.startsWith("**", i) -> {
                val end = line.indexOf("**", i + 2)
                if (end > i + 2) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(line.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else {
                    append(line[i])
                    i++
                }
            }

            line.startsWith("*", i) && !line.startsWith("**", i) -> {
                val end = line.indexOf('*', i + 1)
                if (end > i + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(line.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append(line[i])
                    i++
                }
            }

            else -> {
                append(line[i])
                i++
            }
        }
    }
}

private fun detectFenceLanguage(hint: String): CodeLanguage {
    val clean = hint.trim().lowercase()
    return when (clean) {
        "py", "python" -> CodeLanguage.PYTHON
        "js", "javascript", "ts", "typescript", "jsx", "tsx" -> CodeLanguage.JAVASCRIPT
        "kt", "kotlin" -> CodeLanguage.KOTLIN
        "json", "jsonc" -> CodeLanguage.JSON
        "yml", "yaml" -> CodeLanguage.YAML
        "md", "markdown" -> CodeLanguage.MARKDOWN
        "sh", "bash", "zsh", "shell" -> CodeLanguage.BASH
        "html", "xml", "css", "scss" -> CodeLanguage.HTML_CSS
        else -> CodeLanguage.detect("file.$clean")
    }
}
