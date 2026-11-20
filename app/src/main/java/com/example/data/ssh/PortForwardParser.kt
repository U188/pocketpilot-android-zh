package com.example.data.ssh

import com.example.domain.model.DiscoveredRemotePort

object PortForwardParser {

    private val SS_PROCESS_REGEX = Regex("""users:\(\("([^"]+)"(?:,pid=(\d+))?""")
    private val NETSTAT_PROCESS_REGEX = Regex("""^(\d+)/([^\s:]+)""")
    private val SAFE_HOST_REGEX = Regex("""^[a-zA-Z0-9._:\-\[\]]{1,253}$""")

    fun validatePort(raw: String): Int? {
        val port = raw.trim().toIntOrNull() ?: return null
        return port.takeIf { it in 1..65535 }
    }

    fun validateRemoteHost(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "127.0.0.1"
        return trimmed.takeIf { SAFE_HOST_REGEX.matches(it) }
    }

    /**
     * Parses listening TCP ports from `ss -tlnp` or `netstat -tlnp` output.
     * Filters out SSH port 22 and deduplicates by port number.
     */
    fun parseListeningPorts(
        output: String,
        openCodePort: Int? = null,
    ): List<DiscoveredRemotePort> {
        if (output.isBlank()) return emptyList()

        val byPort = LinkedHashMap<Int, DiscoveredRemotePort>()
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val lower = line.lowercase()
            if (lower.startsWith("state") ||
                lower.startsWith("proto") ||
                lower.startsWith("active internet") ||
                lower.startsWith("netid")
            ) {
                continue
            }

            val parsed = parseSsLine(line, openCodePort) ?: parseNetstatLine(line, openCodePort) ?: continue
            if (parsed.port == 22) continue

            val existing = byPort[parsed.port]
            if (existing == null || shouldReplace(existing, parsed)) {
                byPort[parsed.port] = parsed
            }
        }

        return byPort.values.sortedWith(
            compareBy<DiscoveredRemotePort> { it.isOpenCodePort }
                .thenBy { it.port },
        )
    }

    private fun parseSsLine(line: String, openCodePort: Int?): DiscoveredRemotePort? {
        // ss -tlnp typical formats:
        // LISTEN 0 511 127.0.0.1:3000 0.0.0.0:* users:(("node",pid=1234,fd=21))
        // tcp LISTEN 0 4096 [::]:5173 [::]:* users:(("vite",pid=88,fd=19))
        val tokens = line.split(Regex("""\s+"""))
        val listenIdx = tokens.indexOfFirst { it.equals("LISTEN", ignoreCase = true) }
        if (listenIdx < 0) return null

        // Local address in ss is typically at listenIdx + 3 (after Recv-Q and Send-Q)
        val localAddrToken = tokens.getOrNull(listenIdx + 3) ?: return null
        val (bindAddr, port) = extractAddressAndPort(localAddrToken) ?: return null

        val procMatch = SS_PROCESS_REGEX.find(line)
        val processName = procMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val pid = procMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

        return DiscoveredRemotePort(
            port = port,
            processName = processName,
            bindAddress = bindAddr,
            pid = pid,
            isOpenCodePort = openCodePort != null && port == openCodePort,
        )
    }

    private fun parseNetstatLine(line: String, openCodePort: Int?): DiscoveredRemotePort? {
        // netstat -tlnp format:
        // tcp 0 0 127.0.0.1:8000 0.0.0.0:* LISTEN 4321/python3
        // tcp6 0 0 :::3000 :::* LISTEN 999/node
        val tokens = line.split(Regex("""\s+"""))
        if (tokens.size < 6) return null
        if (!tokens[0].startsWith("tcp", ignoreCase = true)) return null
        if (!tokens[5].equals("LISTEN", ignoreCase = true)) return null

        val (bindAddr, port) = extractAddressAndPort(tokens[3]) ?: return null
        val procToken = tokens.getOrNull(6).orEmpty()
        val procMatch = NETSTAT_PROCESS_REGEX.find(procToken)
        val pid = procMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val processName = procMatch?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }

        return DiscoveredRemotePort(
            port = port,
            processName = processName,
            bindAddress = bindAddr,
            pid = pid,
            isOpenCodePort = openCodePort != null && port == openCodePort,
        )
    }

    private fun extractAddressAndPort(token: String): Pair<String, Int>? {
        val lastColon = token.lastIndexOf(':')
        if (lastColon <= 0 || lastColon == token.lastIndex) return null
        val portStr = token.substring(lastColon + 1)
        val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val rawAddr = token.substring(0, lastColon).trim()
        val cleanAddr = when {
            rawAddr.isEmpty() || rawAddr == "*" || rawAddr == "0.0.0.0" || rawAddr == "::" || rawAddr == "[::]" -> "0.0.0.0"
            rawAddr == "[::1]" || rawAddr == "::1" -> "127.0.0.1"
            rawAddr.startsWith("[") && rawAddr.endsWith("]") -> rawAddr.substring(1, rawAddr.length - 1)
            else -> rawAddr.substringBefore('%')
        }
        return cleanAddr to port
    }

    private fun shouldReplace(existing: DiscoveredRemotePort, candidate: DiscoveredRemotePort): Boolean {
        if (existing.processName == null && candidate.processName != null) return true
        if (existing.bindAddress != "127.0.0.1" && candidate.bindAddress == "127.0.0.1") return true
        return false
    }
}
