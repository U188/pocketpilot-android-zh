package com.example.domain.model

data class TerminalSnippet(
    val id: Long = 0L,
    val title: String,
    val command: String,
    val autoExecute: Boolean = true,
)

enum class TerminalTabStatus {
    CONNECTING,
    CONNECTED,
    CLOSED,
    ERROR,
}

object DangerousCommandDetector {
    private val dangerousPatterns = listOf(
        Regex("""\brm\s+-[a-zA-Z]*[rf][a-zA-Z]*\b"""),
        Regex("""\brm\s+--no-preserve-root\b"""),
        Regex("""\bmkfs(\.[a-z0-9]+)?\b"""),
        Regex("""\bdd\s+if=.*of=/dev/"""),
        Regex("""\b(shutdown|reboot|poweroff|halt)\b"""),
        Regex("""\bchmod\s+-R\s+777\s+/"""),
        Regex("""\bchown\s+-R\s+.*\s+/\s*$"""),
        Regex(""":\(\)\s*\{\s*:\|:\s*&\s*\}\s*;:"""),
        Regex(""">\s*/dev/sd[a-z]"""),
    )

    fun isDangerous(command: String): Boolean {
        val normalized = command.trim()
        if (normalized.isEmpty()) return false
        return dangerousPatterns.any { it.containsMatchIn(normalized) }
    }
}
