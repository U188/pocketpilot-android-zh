package com.example.data.opencode

import com.example.domain.model.DiffLine
import com.example.domain.model.DiffLineType
import kotlin.math.max
import kotlin.math.min

object UnifiedDiffBuilder {

    private const val MAX_LCS_LINES = 800
    private const val CONTEXT_LINES = 3

    fun buildDiffLines(
        before: String,
        after: String,
        rawPatch: String? = null,
    ): List<DiffLine> {
        if (!rawPatch.isNullOrBlank()) {
            val parsed = parseUnifiedPatch(rawPatch)
            if (parsed.isNotEmpty()) return parsed
        }
        if (before == after) return emptyList()

        val oldLines = if (before.isEmpty()) emptyList() else before.split("\n")
        val newLines = if (after.isEmpty()) emptyList() else after.split("\n")

        if (oldLines.isEmpty()) {
            return buildList {
                add(DiffLine(DiffLineType.HUNK_HEADER, content = "@@ -0,0 +1,${newLines.size} @@"))
                newLines.forEachIndexed { idx, line ->
                    add(DiffLine(DiffLineType.ADDED, newLineNumber = idx + 1, content = line))
                }
            }
        }
        if (newLines.isEmpty()) {
            return buildList {
                add(DiffLine(DiffLineType.HUNK_HEADER, content = "@@ -1,${oldLines.size} +0,0 @@"))
                oldLines.forEachIndexed { idx, line ->
                    add(DiffLine(DiffLineType.REMOVED, oldLineNumber = idx + 1, content = line))
                }
            }
        }

        // Trim common prefix & suffix to reduce DP matrix size dramatically
        var prefix = 0
        val minLen = min(oldLines.size, newLines.size)
        while (prefix < minLen && oldLines[prefix] == newLines[prefix]) {
            prefix++
        }
        var suffix = 0
        while (suffix < (minLen - prefix) &&
            oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
        ) {
            suffix++
        }

        val midOld = oldLines.subList(prefix, oldLines.size - suffix)
        val midNew = newLines.subList(prefix, newLines.size - suffix)

        val rawOps = mutableListOf<DiffLine>()
        for (i in 0 until prefix) {
            rawOps.add(
                DiffLine(
                    type = DiffLineType.CONTEXT,
                    oldLineNumber = i + 1,
                    newLineNumber = i + 1,
                    content = oldLines[i],
                ),
            )
        }

        if (midOld.size <= MAX_LCS_LINES && midNew.size <= MAX_LCS_LINES) {
            appendLcsDiff(rawOps, midOld, midNew, prefix + 1, prefix + 1)
        } else {
            // Safe linear fallback for huge files so UI never OOMs
            midOld.forEachIndexed { idx, line ->
                rawOps.add(
                    DiffLine(
                        type = DiffLineType.REMOVED,
                        oldLineNumber = prefix + idx + 1,
                        content = line,
                    ),
                )
            }
            midNew.forEachIndexed { idx, line ->
                rawOps.add(
                    DiffLine(
                        type = DiffLineType.ADDED,
                        newLineNumber = prefix + idx + 1,
                        content = line,
                    ),
                )
            }
        }

        val suffixOldStart = oldLines.size - suffix
        val suffixNewStart = newLines.size - suffix
        for (i in 0 until suffix) {
            rawOps.add(
                DiffLine(
                    type = DiffLineType.CONTEXT,
                    oldLineNumber = suffixOldStart + i + 1,
                    newLineNumber = suffixNewStart + i + 1,
                    content = oldLines[suffixOldStart + i],
                ),
            )
        }

        return compactIntoHunks(rawOps)
    }

    fun parseUnifiedPatch(patch: String): List<DiffLine> {
        val result = mutableListOf<DiffLine>()
        var oldNum = 0
        var newNum = 0
        val hunkRegex = Regex("""^@@\s*-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s*@@.*""")

        for (rawLine in patch.lines()) {
            when {
                rawLine.startsWith("diff --git") ||
                    rawLine.startsWith("index ") ||
                    rawLine.startsWith("--- ") ||
                    rawLine.startsWith("+++ ") -> continue

                rawLine.startsWith("@@") -> {
                    val match = hunkRegex.find(rawLine)
                    if (match != null) {
                        oldNum = match.groupValues[1].toIntOrNull() ?: 1
                        newNum = match.groupValues[2].toIntOrNull() ?: 1
                    }
                    result.add(DiffLine(DiffLineType.HUNK_HEADER, content = rawLine))
                }

                rawLine.startsWith("+") -> {
                    result.add(
                        DiffLine(
                            type = DiffLineType.ADDED,
                            newLineNumber = newNum++,
                            content = rawLine.substring(1),
                        ),
                    )
                }

                rawLine.startsWith("-") -> {
                    result.add(
                        DiffLine(
                            type = DiffLineType.REMOVED,
                            oldLineNumber = oldNum++,
                            content = rawLine.substring(1),
                        ),
                    )
                }

                rawLine.startsWith(" ") -> {
                    result.add(
                        DiffLine(
                            type = DiffLineType.CONTEXT,
                            oldLineNumber = oldNum++,
                            newLineNumber = newNum++,
                            content = rawLine.substring(1),
                        ),
                    )
                }
            }
        }
        return result
    }

    private fun appendLcsDiff(
        out: MutableList<DiffLine>,
        a: List<String>,
        b: List<String>,
        oldBase: Int,
        newBase: Int,
    ) {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }

        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    max(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out.add(
                        DiffLine(
                            type = DiffLineType.CONTEXT,
                            oldLineNumber = oldBase + i,
                            newLineNumber = newBase + j,
                            content = a[i],
                        ),
                    )
                    i++
                    j++
                }

                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out.add(
                        DiffLine(
                            type = DiffLineType.REMOVED,
                            oldLineNumber = oldBase + i,
                            content = a[i],
                        ),
                    )
                    i++
                }

                else -> {
                    out.add(
                        DiffLine(
                            type = DiffLineType.ADDED,
                            newLineNumber = newBase + j,
                            content = b[j],
                        ),
                    )
                    j++
                }
            }
        }
        while (i < n) {
            out.add(
                DiffLine(
                    type = DiffLineType.REMOVED,
                    oldLineNumber = oldBase + i,
                    content = a[i],
                ),
            )
            i++
        }
        while (j < m) {
            out.add(
                DiffLine(
                    type = DiffLineType.ADDED,
                    newLineNumber = newBase + j,
                    content = b[j],
                ),
            )
            j++
        }
    }

    private fun compactIntoHunks(ops: List<DiffLine>): List<DiffLine> {
        val changedIndices = ops.indices.filter { ops[it].type != DiffLineType.CONTEXT }
        if (changedIndices.isEmpty()) return emptyList()

        val ranges = mutableListOf<IntRange>()
        var start = max(0, changedIndices.first() - CONTEXT_LINES)
        var end = min(ops.lastIndex, changedIndices.first() + CONTEXT_LINES)

        for (idx in changedIndices.drop(1)) {
            val nextStart = max(0, idx - CONTEXT_LINES)
            val nextEnd = min(ops.lastIndex, idx + CONTEXT_LINES)
            if (nextStart <= end + 1) {
                end = max(end, nextEnd)
            } else {
                ranges.add(start..end)
                start = nextStart
                end = nextEnd
            }
        }
        ranges.add(start..end)

        val result = mutableListOf<DiffLine>()
        for (range in ranges) {
            val slice = ops.subList(range.first, range.last + 1)
            val oldStart = slice.firstNotNullOfOrNull { it.oldLineNumber } ?: 1
            val newStart = slice.firstNotNullOfOrNull { it.newLineNumber } ?: 1
            val oldCount = slice.count { it.type != DiffLineType.ADDED }
            val newCount = slice.count { it.type != DiffLineType.REMOVED }
            result.add(
                DiffLine(
                    type = DiffLineType.HUNK_HEADER,
                    content = "@@ -$oldStart,$oldCount +$newStart,$newCount @@",
                ),
            )
            result.addAll(slice)
        }
        return result
    }
}
