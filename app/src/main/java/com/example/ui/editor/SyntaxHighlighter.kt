package com.example.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

enum class CodeLanguage(val label: String) {
    PYTHON("Python"),
    JAVASCRIPT("JS/TS"),
    KOTLIN("Kotlin"),
    JSON("JSON"),
    YAML("YAML"),
    MARKDOWN("Markdown"),
    BASH("Bash"),
    HTML_CSS("HTML/CSS"),
    PLAIN("Text");

    companion object {
        fun detect(fileName: String): CodeLanguage {
            val lower = fileName.lowercase()
            if (lower == "dockerfile" || lower == "makefile" || lower.startsWith(".env") ||
                lower == ".bashrc" || lower == ".zshrc"
            ) {
                return BASH
            }
            return when (lower.substringAfterLast('.', "")) {
                "py", "pyw", "pyi" -> PYTHON
                "js", "jsx", "ts", "tsx", "mjs", "cjs" -> JAVASCRIPT
                "kt", "kts" -> KOTLIN
                "json", "jsonc" -> JSON
                "yml", "yaml" -> YAML
                "md", "markdown", "mdx" -> MARKDOWN
                "sh", "bash", "zsh", "env", "conf", "ini", "toml" -> BASH
                "html", "htm", "xml", "svg", "css", "scss", "less" -> HTML_CSS
                else -> PLAIN
            }
        }
    }
}

data class SyntaxPalette(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val typeOrKey: Color,
    val annotation: Color,
    val matchBackground: Color,
    val activeMatchBackground: Color,
) {
    companion object {
        val Dark = SyntaxPalette(
            keyword = Color(0xFFC084FC),
            string = Color(0xFF34D399),
            comment = Color(0xFF64748B),
            number = Color(0xFFFBBF24),
            typeOrKey = Color(0xFF38BDF8),
            annotation = Color(0xFFFB7185),
            matchBackground = Color(0x55FBBF24),
            activeMatchBackground = Color(0xAAFB923C),
        )

        val Light = SyntaxPalette(
            keyword = Color(0xFF7C3AED),
            string = Color(0xFF059669),
            comment = Color(0xFF64748B),
            number = Color(0xFFD97706),
            typeOrKey = Color(0xFF0284C7),
            annotation = Color(0xFFE11D48),
            matchBackground = Color(0x55F59E0B),
            activeMatchBackground = Color(0x99F97316),
        )
    }
}

class SyntaxVisualTransformation(
    private val language: CodeLanguage,
    private val darkTheme: Boolean,
    private val searchRanges: List<IntRange>,
    private val activeMatchIndex: Int,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val highlighted = SyntaxHighlighter.highlight(
            code = text.text,
            language = language,
            palette = if (darkTheme) SyntaxPalette.Dark else SyntaxPalette.Light,
            searchRanges = searchRanges,
            activeMatchIndex = activeMatchIndex,
        )
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}

object SyntaxHighlighter {

    private val pythonKeywords = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import",
        "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return",
        "True", "try", "while", "with", "yield", "match", "case", "self",
    )

    private val jsKeywords = setOf(
        "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "enum", "export", "extends", "false", "finally",
        "for", "from", "function", "if", "implements", "import", "in", "instanceof", "interface",
        "let", "new", "null", "of", "package", "private", "protected", "public", "readonly",
        "return", "static", "super", "switch", "this", "throw", "true", "try", "type", "typeof",
        "undefined", "var", "void", "while", "yield",
    )

    private val kotlinKeywords = setOf(
        "abstract", "actual", "annotation", "as", "break", "by", "catch", "class", "companion",
        "const", "constructor", "continue", "data", "do", "else", "enum", "expect", "false",
        "final", "finally", "for", "fun", "if", "import", "in", "init", "inline", "inner",
        "interface", "internal", "is", "lateinit", "null", "object", "open", "operator", "out",
        "override", "package", "private", "protected", "public", "reified", "return", "sealed",
        "super", "suspend", "this", "throw", "true", "try", "typealias", "val", "var", "when",
        "while",
    )

    private val bashKeywords = setOf(
        "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case",
        "esac", "function", "return", "local", "export", "unset", "readonly", "echo", "exit",
        "true", "false", "cd", "sudo", "set", "source",
    )

    private val numberRegex = Regex("""\b0x[0-9a-fA-F]+\b|\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b""")
    private val wordRegex = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")
    private val stringRegex = Regex("""\"(?:\\.|[^\"\\\n])*\"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`""")
    private val slashCommentRegex = Regex("""//[^\n]*|/\*[\s\S]*?\*/""")
    private val hashCommentRegex = Regex("""#[^\n]*""")
    private val jsonKeyRegex = Regex("""\"(?:\\.|[^\"\\\n])*\"\s*(?=:)""")
    private val yamlKeyRegex = Regex("""(?m)^[\t ]*[A-Za-z0-9_.-]+(?=\s*:)""")
    private val markdownHeadingRegex = Regex("""(?m)^#{1,6}\s+[^\n]*""")
    private val markdownCodeRegex = Regex("""`[^`\n]+`""")
    private val htmlTagRegex = Regex("""</?[A-Za-z][A-Za-z0-9:-]*|/?>""")
    private val annotationRegex = Regex("""@[A-Za-z_][A-Za-z0-9_]*""")
    private val bashVarRegex = Regex("""\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_]*""")

    private const val MAX_HIGHLIGHT_CHARS = 120_000

    fun highlight(
        code: String,
        language: CodeLanguage,
        palette: SyntaxPalette,
        searchRanges: List<IntRange> = emptyList(),
        activeMatchIndex: Int = -1,
    ): AnnotatedString = buildAnnotatedString {
        append(code)

        if (code.length <= MAX_HIGHLIGHT_CHARS && language != CodeLanguage.PLAIN) {
            when (language) {
                CodeLanguage.PYTHON -> highlightImperative(
                    code = code,
                    keywords = pythonKeywords,
                    commentRegex = hashCommentRegex,
                    palette = palette,
                    annotations = true,
                )

                CodeLanguage.JAVASCRIPT -> highlightImperative(
                    code = code,
                    keywords = jsKeywords,
                    commentRegex = slashCommentRegex,
                    palette = palette,
                    annotations = true,
                )

                CodeLanguage.KOTLIN -> highlightImperative(
                    code = code,
                    keywords = kotlinKeywords,
                    commentRegex = slashCommentRegex,
                    palette = palette,
                    annotations = true,
                )

                CodeLanguage.JSON -> {
                    applyRegex(code, stringRegex, SpanStyle(color = palette.string))
                    applyRegex(code, jsonKeyRegex, SpanStyle(color = palette.typeOrKey))
                    applyRegex(code, numberRegex, SpanStyle(color = palette.number))
                    wordRegex.findAll(code).forEach { m ->
                        if (m.value == "true" || m.value == "false" || m.value == "null") {
                            addStyle(
                                SpanStyle(color = palette.keyword, fontWeight = FontWeight.Medium),
                                m.range.first,
                                m.range.last + 1,
                            )
                        }
                    }
                }

                CodeLanguage.YAML -> {
                    applyRegex(code, yamlKeyRegex, SpanStyle(color = palette.typeOrKey, fontWeight = FontWeight.Medium))
                    applyRegex(code, numberRegex, SpanStyle(color = palette.number))
                    applyRegex(code, stringRegex, SpanStyle(color = palette.string))
                    wordRegex.findAll(code).forEach { m ->
                        if (m.value == "true" || m.value == "false" || m.value == "null") {
                            addStyle(SpanStyle(color = palette.keyword), m.range.first, m.range.last + 1)
                        }
                    }
                    applyRegex(code, hashCommentRegex, SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic))
                }

                CodeLanguage.MARKDOWN -> {
                    applyRegex(code, markdownHeadingRegex, SpanStyle(color = palette.typeOrKey, fontWeight = FontWeight.Bold))
                    applyRegex(code, markdownCodeRegex, SpanStyle(color = palette.string))
                }

                CodeLanguage.BASH -> {
                    highlightImperative(
                        code = code,
                        keywords = bashKeywords,
                        commentRegex = hashCommentRegex,
                        palette = palette,
                        annotations = false,
                    )
                    applyRegex(code, bashVarRegex, SpanStyle(color = palette.typeOrKey))
                }

                CodeLanguage.HTML_CSS -> {
                    applyRegex(code, htmlTagRegex, SpanStyle(color = palette.keyword, fontWeight = FontWeight.Medium))
                    applyRegex(code, numberRegex, SpanStyle(color = palette.number))
                    applyRegex(code, stringRegex, SpanStyle(color = palette.string))
                    applyRegex(code, slashCommentRegex, SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic))
                }

                CodeLanguage.PLAIN -> Unit
            }
        }

        // Overlay search match highlights on top so they always stand out
        searchRanges.forEachIndexed { idx, range ->
            if (range.first >= 0 && range.last < code.length) {
                val bg = if (idx == activeMatchIndex) palette.activeMatchBackground else palette.matchBackground
                addStyle(SpanStyle(background = bg), range.first, range.last + 1)
            }
        }
    }

    private fun AnnotatedString.Builder.highlightImperative(
        code: String,
        keywords: Set<String>,
        commentRegex: Regex,
        palette: SyntaxPalette,
        annotations: Boolean,
    ) {
        applyRegex(code, numberRegex, SpanStyle(color = palette.number))
        wordRegex.findAll(code).forEach { m ->
            val word = m.value
            when {
                word in keywords -> addStyle(
                    SpanStyle(color = palette.keyword, fontWeight = FontWeight.Medium),
                    m.range.first,
                    m.range.last + 1,
                )

                word.first().isUpperCase() && word.any { it.isLowerCase() } -> addStyle(
                    SpanStyle(color = palette.typeOrKey),
                    m.range.first,
                    m.range.last + 1,
                )
            }
        }
        if (annotations) {
            applyRegex(code, annotationRegex, SpanStyle(color = palette.annotation))
        }
        applyRegex(code, stringRegex, SpanStyle(color = palette.string))
        applyRegex(code, commentRegex, SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic))
    }

    private fun AnnotatedString.Builder.applyRegex(
        code: String,
        regex: Regex,
        style: SpanStyle,
    ) {
        regex.findAll(code).forEach { match ->
            addStyle(style, match.range.first, match.range.last + 1)
        }
    }
}
