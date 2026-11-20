package com.example.data.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlin.math.max
import kotlin.math.min

data class TerminalStyle(
    val fgColor: Color = DEFAULT_FG,
    val bgColor: Color = DEFAULT_BG,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
) {
    companion object {
        val DEFAULT_FG = Color(0xFFE6EDF3)
        val DEFAULT_BG = Color.Transparent
        val DEFAULT = TerminalStyle()
    }
}

data class TerminalScreenSnapshot(
    val lines: List<AnnotatedString> = emptyList(),
    val plainLines: List<String> = emptyList(),
    val cursorRowInAllLines: Int = 0,
    val cursorCol: Int = 0,
    val cols: Int = 80,
    val rows: Int = 24,
    val title: String? = null,
    val revision: Long = 0L,
)

class AnsiTerminalBuffer(
    initialCols: Int = 80,
    initialRows: Int = 24,
    private val maxScrollback: Int = 1_500,
) {
    private data class Cell(
        var ch: Char = ' ',
        var style: TerminalStyle = TerminalStyle.DEFAULT,
    )

    private enum class ParseState {
        NORMAL,
        ESC,
        CSI,
        OSC,
        OSC_ESC,
        CHARSET,
    }

    private var cols: Int = initialCols.coerceIn(20, 240)
    private var rows: Int = initialRows.coerceIn(8, 120)

    private var screen: MutableList<Array<Cell>> = createEmptyGrid(rows, cols)
    private val scrollback = ArrayDeque<Array<Cell>>()

    private var savedMainScreen: MutableList<Array<Cell>>? = null
    private var savedMainCursorRow = 0
    private var savedMainCursorCol = 0
    private var inAltScreen = false

    private var cursorRow = 0
    private var cursorCol = 0
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var currentStyle = TerminalStyle.DEFAULT

    private var parseState = ParseState.NORMAL
    private val csiBuffer = StringBuilder()
    private val oscBuffer = StringBuilder()

    private var windowTitle: String? = null
    private var revision: Long = 0L

    @Synchronized
    fun feed(text: String) {
        if (text.isEmpty()) return
        for (ch in text) {
            processChar(ch)
        }
        revision++
    }

    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        val clampedCols = newCols.coerceIn(20, 240)
        val clampedRows = newRows.coerceIn(8, 120)
        if (clampedCols == cols && clampedRows == rows) return

        val resized = MutableList(clampedRows) { r ->
            Array(clampedCols) { c ->
                if (r < screen.size && c < screen[r].size) {
                    val existing = screen[r][c]
                    Cell(existing.ch, existing.style)
                } else {
                    Cell()
                }
            }
        }
        screen = resized
        cols = clampedCols
        rows = clampedRows
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        revision++
    }

    @Synchronized
    fun clearAll() {
        scrollback.clear()
        screen = createEmptyGrid(rows, cols)
        cursorRow = 0
        cursorCol = 0
        revision++
    }

    @Synchronized
    fun snapshot(): TerminalScreenSnapshot {
        val allRows = if (inAltScreen) {
            screen
        } else {
            val combined = ArrayList<Array<Cell>>(scrollback.size + screen.size)
            combined.addAll(scrollback)
            combined.addAll(screen)
            combined
        }

        // Trim trailing blank rows below cursor for cleaner mobile scroll
        val cursorAbsRow = (if (inAltScreen) 0 else scrollback.size) + cursorRow
        var lastNonEmpty = cursorAbsRow
        for (i in allRows.lastIndex downTo cursorAbsRow + 1) {
            if (allRows[i].any { it.ch != ' ' || it.style.bgColor != TerminalStyle.DEFAULT_BG }) {
                lastNonEmpty = i
                break
            }
        }

        val visibleCount = (lastNonEmpty + 1).coerceAtMost(allRows.size)
        val annotated = ArrayList<AnnotatedString>(visibleCount)
        val plain = ArrayList<String>(visibleCount)

        for (r in 0 until visibleCount) {
            val rowCells = allRows[r]
            val (styledLine, plainLine) = renderRow(
                rowCells = rowCells,
                cursorColumn = if (r == cursorAbsRow) cursorCol else -1,
            )
            annotated.add(styledLine)
            plain.add(plainLine)
        }

        return TerminalScreenSnapshot(
            lines = annotated,
            plainLines = plain,
            cursorRowInAllLines = cursorAbsRow.coerceIn(0, max(0, visibleCount - 1)),
            cursorCol = cursorCol,
            cols = cols,
            rows = rows,
            title = windowTitle,
            revision = revision,
        )
    }

    private fun processChar(ch: Char) {
        when (parseState) {
            ParseState.NORMAL -> processNormalChar(ch)
            ParseState.ESC -> processEscChar(ch)
            ParseState.CSI -> processCsiChar(ch)
            ParseState.OSC -> processOscChar(ch)
            ParseState.OSC_ESC -> {
                if (ch == '\\') {
                    applyOsc(oscBuffer.toString())
                    oscBuffer.setLength(0)
                    parseState = ParseState.NORMAL
                } else {
                    oscBuffer.append('\u001B').append(ch)
                    parseState = ParseState.OSC
                }
            }

            ParseState.CHARSET -> {
                // Consume charset designator byte (e.g., ESC ( B)
                parseState = ParseState.NORMAL
            }
        }
    }

    private fun processNormalChar(ch: Char) {
        when (ch) {
            '\u001B' -> parseState = ParseState.ESC
            '\r' -> cursorCol = 0
            '\n', '\u000B', '\u000C' -> lineFeed()
            '\b' -> if (cursorCol > 0) cursorCol--
            '\t' -> {
                val nextTab = ((cursorCol / 8) + 1) * 8
                cursorCol = min(nextTab, cols - 1)
            }
            '\u0007', '\u0000' -> Unit // ignore bell/null
            else -> {
                if (ch.code >= 0x20) {
                    putChar(ch)
                }
            }
        }
    }

    private fun processEscChar(ch: Char) {
        when (ch) {
            '[' -> {
                csiBuffer.setLength(0)
                parseState = ParseState.CSI
            }

            ']' -> {
                oscBuffer.setLength(0)
                parseState = ParseState.OSC
            }

            '(', ')', '*', '+' -> parseState = ParseState.CHARSET

            '7' -> {
                savedCursorRow = cursorRow
                savedCursorCol = cursorCol
                parseState = ParseState.NORMAL
            }

            '8' -> {
                cursorRow = savedCursorRow.coerceIn(0, rows - 1)
                cursorCol = savedCursorCol.coerceIn(0, cols - 1)
                parseState = ParseState.NORMAL
            }

            'M' -> {
                // Reverse index
                if (cursorRow > 0) {
                    cursorRow--
                } else {
                    scrollDown(1)
                }
                parseState = ParseState.NORMAL
            }

            'c' -> {
                currentStyle = TerminalStyle.DEFAULT
                screen = createEmptyGrid(rows, cols)
                cursorRow = 0
                cursorCol = 0
                parseState = ParseState.NORMAL
            }

            else -> parseState = ParseState.NORMAL
        }
    }

    private fun processCsiChar(ch: Char) {
        if (ch.code in 0x40..0x7E) {
            executeCsi(csiBuffer.toString(), ch)
            csiBuffer.setLength(0)
            parseState = ParseState.NORMAL
        } else {
            if (csiBuffer.length < 64) {
                csiBuffer.append(ch)
            }
        }
    }

    private fun processOscChar(ch: Char) {
        when (ch) {
            '\u0007' -> {
                applyOsc(oscBuffer.toString())
                oscBuffer.setLength(0)
                parseState = ParseState.NORMAL
            }

            '\u001B' -> parseState = ParseState.OSC_ESC

            else -> {
                if (oscBuffer.length < 256) {
                    oscBuffer.append(ch)
                }
            }
        }
    }

    private fun applyOsc(osc: String) {
        val sep = osc.indexOf(';')
        if (sep <= 0) return
        val code = osc.substring(0, sep)
        val value = osc.substring(sep + 1).trim()
        if ((code == "0" || code == "2") && value.isNotEmpty()) {
            windowTitle = value.take(40)
        }
    }

    private fun executeCsi(rawParams: String, finalChar: Char) {
        val isPrivate = rawParams.startsWith("?")
        val cleanParams = if (isPrivate) rawParams.drop(1) else rawParams
        val params = if (cleanParams.isEmpty()) {
            emptyList()
        } else {
            cleanParams.split(';').map { it.toIntOrNull() ?: 0 }
        }

        if (isPrivate) {
            handlePrivateMode(params, finalChar)
            return
        }

        when (finalChar) {
            'm' -> applySgr(params.ifEmpty { listOf(0) })
            'A' -> cursorRow = max(0, cursorRow - max(1, params.getOrElse(0) { 1 }))
            'B' -> cursorRow = min(rows - 1, cursorRow + max(1, params.getOrElse(0) { 1 }))
            'C' -> cursorCol = min(cols - 1, cursorCol + max(1, params.getOrElse(0) { 1 }))
            'D' -> cursorCol = max(0, cursorCol - max(1, params.getOrElse(0) { 1 }))
            'E' -> {
                cursorRow = min(rows - 1, cursorRow + max(1, params.getOrElse(0) { 1 }))
                cursorCol = 0
            }

            'F' -> {
                cursorRow = max(0, cursorRow - max(1, params.getOrElse(0) { 1 }))
                cursorCol = 0
            }

            'G' -> cursorCol = (max(1, params.getOrElse(0) { 1 }) - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (max(1, params.getOrElse(0) { 1 }) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                val targetRow = max(1, params.getOrElse(0) { 1 }) - 1
                val targetCol = max(1, params.getOrElse(1) { 1 }) - 1
                cursorRow = targetRow.coerceIn(0, rows - 1)
                cursorCol = targetCol.coerceIn(0, cols - 1)
            }

            'J' -> eraseDisplay(params.getOrElse(0) { 0 })
            'K' -> eraseLine(params.getOrElse(0) { 0 })
            'L' -> insertLines(max(1, params.getOrElse(0) { 1 }))
            'M' -> deleteLines(max(1, params.getOrElse(0) { 1 }))
            'P' -> deleteChars(max(1, params.getOrElse(0) { 1 }))
            '@' -> insertChars(max(1, params.getOrElse(0) { 1 }))
            'X' -> eraseChars(max(1, params.getOrElse(0) { 1 }))
            'S' -> scrollUp(max(1, params.getOrElse(0) { 1 }))
            'T' -> scrollDown(max(1, params.getOrElse(0) { 1 }))
            's' -> {
                savedCursorRow = cursorRow
                savedCursorCol = cursorCol
            }

            'u' -> {
                cursorRow = savedCursorRow.coerceIn(0, rows - 1)
                cursorCol = savedCursorCol.coerceIn(0, cols - 1)
            }
        }
    }

    private fun handlePrivateMode(params: List<Int>, finalChar: Char) {
        val enable = finalChar == 'h'
        val disable = finalChar == 'l'
        if (!enable && !disable) return

        for (mode in params) {
            if (mode == 1049 || mode == 47 || mode == 1047) {
                if (enable && !inAltScreen) {
                    savedMainScreen = screen
                    savedMainCursorRow = cursorRow
                    savedMainCursorCol = cursorCol
                    screen = createEmptyGrid(rows, cols)
                    cursorRow = 0
                    cursorCol = 0
                    inAltScreen = true
                } else if (disable && inAltScreen) {
                    screen = savedMainScreen ?: createEmptyGrid(rows, cols)
                    savedMainScreen = null
                    cursorRow = savedMainCursorRow.coerceIn(0, rows - 1)
                    cursorCol = savedMainCursorCol.coerceIn(0, cols - 1)
                    inAltScreen = false
                }
            }
        }
    }

    private fun applySgr(codes: List<Int>) {
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> currentStyle = TerminalStyle.DEFAULT
                1 -> currentStyle = currentStyle.copy(bold = true)
                2 -> currentStyle = currentStyle.copy(dim = true)
                3 -> currentStyle = currentStyle.copy(italic = true)
                4 -> currentStyle = currentStyle.copy(underline = true)
                7 -> currentStyle = currentStyle.copy(inverse = true)
                22 -> currentStyle = currentStyle.copy(bold = false, dim = false)
                23 -> currentStyle = currentStyle.copy(italic = false)
                24 -> currentStyle = currentStyle.copy(underline = false)
                27 -> currentStyle = currentStyle.copy(inverse = false)
                in 30..37 -> currentStyle = currentStyle.copy(fgColor = ANSI_STANDARD[code - 30])
                39 -> currentStyle = currentStyle.copy(fgColor = TerminalStyle.DEFAULT_FG)
                in 40..47 -> currentStyle = currentStyle.copy(bgColor = ANSI_STANDARD[code - 40])
                49 -> currentStyle = currentStyle.copy(bgColor = TerminalStyle.DEFAULT_BG)
                in 90..97 -> currentStyle = currentStyle.copy(fgColor = ANSI_BRIGHT[code - 90])
                in 100..107 -> currentStyle = currentStyle.copy(bgColor = ANSI_BRIGHT[code - 100])
                38, 48 -> {
                    val isFg = code == 38
                    val mode = codes.getOrNull(i + 1)
                    if (mode == 5 && i + 2 < codes.size) {
                        val color = xterm256Color(codes[i + 2])
                        currentStyle = if (isFg) currentStyle.copy(fgColor = color) else currentStyle.copy(bgColor = color)
                        i += 2
                    } else if (mode == 2 && i + 4 < codes.size) {
                        val r = codes[i + 2].coerceIn(0, 255)
                        val g = codes[i + 3].coerceIn(0, 255)
                        val b = codes[i + 4].coerceIn(0, 255)
                        val color = Color(r, g, b)
                        currentStyle = if (isFg) currentStyle.copy(fgColor = color) else currentStyle.copy(bgColor = color)
                        i += 4
                    }
                }
            }
            i++
        }
    }

    private fun putChar(ch: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            lineFeed()
        }
        val cell = screen[cursorRow][cursorCol]
        cell.ch = ch
        cell.style = currentStyle
        cursorCol++
    }

    private fun lineFeed() {
        if (cursorRow < rows - 1) {
            cursorRow++
        } else {
            scrollUp(1)
        }
    }

    private fun scrollUp(count: Int) {
        repeat(count.coerceAtMost(rows)) {
            val evicted = screen.removeAt(0)
            if (!inAltScreen) {
                scrollback.addLast(evicted)
                while (scrollback.size > maxScrollback) {
                    scrollback.removeFirst()
                }
            }
            screen.add(Array(cols) { Cell() })
        }
    }

    private fun scrollDown(count: Int) {
        repeat(count.coerceAtMost(rows)) {
            screen.removeAt(screen.lastIndex)
            screen.add(0, Array(cols) { Cell() })
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in cursorRow + 1 until rows) {
                    screen[r] = Array(cols) { Cell() }
                }
            }

            1 -> {
                for (r in 0 until cursorRow) {
                    screen[r] = Array(cols) { Cell() }
                }
                eraseLine(1)
            }

            2, 3 -> {
                if (mode == 3 && !inAltScreen) {
                    scrollback.clear()
                }
                screen = createEmptyGrid(rows, cols)
            }
        }
    }

    private fun eraseLine(mode: Int) {
        val row = screen[cursorRow]
        val range = when (mode) {
            0 -> cursorCol until cols
            1 -> 0..min(cursorCol, cols - 1)
            2 -> 0 until cols
            else -> return
        }
        for (c in range) {
            row[c] = Cell()
        }
    }

    private fun insertLines(count: Int) {
        repeat(count.coerceAtMost(rows - cursorRow)) {
            screen.removeAt(rows - 1)
            screen.add(cursorRow, Array(cols) { Cell() })
        }
    }

    private fun deleteLines(count: Int) {
        repeat(count.coerceAtMost(rows - cursorRow)) {
            screen.removeAt(cursorRow)
            screen.add(Array(cols) { Cell() })
        }
    }

    private fun deleteChars(count: Int) {
        val row = screen[cursorRow]
        val shift = count.coerceAtMost(cols - cursorCol)
        for (c in cursorCol until cols - shift) {
            row[c] = row[c + shift]
        }
        for (c in cols - shift until cols) {
            row[c] = Cell()
        }
    }

    private fun insertChars(count: Int) {
        val row = screen[cursorRow]
        val shift = count.coerceAtMost(cols - cursorCol)
        for (c in cols - 1 downTo cursorCol + shift) {
            row[c] = row[c - shift]
        }
        for (c in cursorCol until cursorCol + shift) {
            row[c] = Cell()
        }
    }

    private fun eraseChars(count: Int) {
        val row = screen[cursorRow]
        val end = min(cols, cursorCol + count)
        for (c in cursorCol until end) {
            row[c] = Cell()
        }
    }

    private fun renderRow(
        rowCells: Array<Cell>,
        cursorColumn: Int,
    ): Pair<AnnotatedString, String> {
        var endCol = rowCells.lastIndex
        while (endCol >= 0 &&
            endCol != cursorColumn &&
            rowCells[endCol].ch == ' ' &&
            rowCells[endCol].style.bgColor == TerminalStyle.DEFAULT_BG &&
            !rowCells[endCol].style.inverse
        ) {
            endCol--
        }
        val effectiveLen = max(endCol + 1, if (cursorColumn in rowCells.indices) cursorColumn + 1 else 0)
        if (effectiveLen == 0) {
            return AnnotatedString("") to ""
        }

        val plainBuilder = StringBuilder(effectiveLen)
        val styled = buildAnnotatedString {
            var runStart = 0
            var runStyle = resolvedSpanStyle(rowCells[0].style, isCursor = cursorColumn == 0)

            for (c in 0 until effectiveLen) {
                val cell = rowCells[c]
                plainBuilder.append(cell.ch)
                append(cell.ch)

                val style = resolvedSpanStyle(cell.style, isCursor = c == cursorColumn)
                if (style != runStyle) {
                    if (c > runStart && runStyle != null) {
                        addStyle(runStyle, runStart, c)
                    }
                    runStart = c
                    runStyle = style
                }
            }
            if (effectiveLen > runStart && runStyle != null) {
                addStyle(runStyle, runStart, effectiveLen)
            }
        }
        return styled to plainBuilder.toString().trimEnd()
    }

    private fun resolvedSpanStyle(style: TerminalStyle, isCursor: Boolean): SpanStyle? {
        if (!isCursor && style == TerminalStyle.DEFAULT) return null
        val fg = when {
            isCursor -> CURSOR_FG
            style.inverse -> if (style.bgColor == TerminalStyle.DEFAULT_BG) TERMINAL_BG_SOLID else style.bgColor
            style.dim -> style.fgColor.copy(alpha = 0.6f)
            else -> style.fgColor
        }
        val bg = when {
            isCursor -> CURSOR_BG
            style.inverse -> style.fgColor
            else -> style.bgColor
        }
        return SpanStyle(
            color = fg,
            background = bg,
            fontWeight = if (style.bold) FontWeight.Bold else null,
            fontStyle = if (style.italic) FontStyle.Italic else null,
            textDecoration = if (style.underline) TextDecoration.Underline else null,
        )
    }

    private fun createEmptyGrid(r: Int, c: Int): MutableList<Array<Cell>> =
        MutableList(r) { Array(c) { Cell() } }

    companion object {
        private val TERMINAL_BG_SOLID = Color(0xFF0B1017)
        private val CURSOR_BG = Color(0xFF38BDF8)
        private val CURSOR_FG = Color(0xFF081018)

        private val ANSI_STANDARD = arrayOf(
            Color(0xFF1F2937), // 0 black
            Color(0xFFF87171), // 1 red
            Color(0xFF4ADE80), // 2 green
            Color(0xFFFACC15), // 3 yellow
            Color(0xFF60A5FA), // 4 blue
            Color(0xFFC084FC), // 5 magenta
            Color(0xFF22D3EE), // 6 cyan
            Color(0xFFE5E7EB), // 7 white
        )

        private val ANSI_BRIGHT = arrayOf(
            Color(0xFF6B7280), // 8 bright black
            Color(0xFFFCA5A5), // 9 bright red
            Color(0xFF86EFAC), // 10 bright green
            Color(0xFFFDE047), // 11 bright yellow
            Color(0xFF93C5FD), // 12 bright blue
            Color(0xFFD8B4FE), // 13 bright magenta
            Color(0xFF67E8F9), // 14 bright cyan
            Color(0xFFFFFFFF), // 15 bright white
        )

        fun xterm256Color(index: Int): Color {
            val n = index.coerceIn(0, 255)
            return when {
                n < 8 -> ANSI_STANDARD[n]
                n < 16 -> ANSI_BRIGHT[n - 8]
                n < 232 -> {
                    val v = n - 16
                    val r = (v / 36) % 6
                    val g = (v / 6) % 6
                    val b = v % 6
                    val levels = intArrayOf(0, 95, 135, 175, 215, 255)
                    Color(levels[r], levels[g], levels[b])
                }

                else -> {
                    val gray = 8 + (n - 232) * 10
                    Color(gray, gray, gray)
                }
            }
        }
    }
}
