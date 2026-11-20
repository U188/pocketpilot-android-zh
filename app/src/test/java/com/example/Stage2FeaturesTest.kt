package com.example

import com.example.data.ssh.PortForwardParser
import com.example.ui.editor.CodeEditorViewModel
import com.example.ui.terminal.TerminalViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage2FeaturesTest {

    @Test
    fun parseListeningPorts_parsesSsIpv4AndIpv6AndFiltersSsh() {
        val ssOutput = """
            State   Recv-Q Send-Q Local Address:Port   Peer Address:Port Process
            LISTEN  0      128    0.0.0.0:22           0.0.0.0:*         users:(("sshd",pid=500,fd=3))
            LISTEN  0      511    127.0.0.1:3000       0.0.0.0:*         users:(("node",pid=1234,fd=21))
            LISTEN  0      511    [::]:3000            [::]:*            users:(("node",pid=1234,fd=22))
            LISTEN  0      4096   [::1]:5173           [::]:*            users:(("vite",pid=2468,fd=19))
            LISTEN  0      128    127.0.0.1:4096       0.0.0.0:*         users:(("opencode",pid=777,fd=10))
            LISTEN  0      100    *:8080               *:*
        """.trimIndent()

        val ports = PortForwardParser.parseListeningPorts(ssOutput, openCodePort = 4096)

        // Port 22 must be excluded; port 3000 deduplicated once; OpenCode sorted last
        assertEquals(listOf(3000, 5173, 8080, 4096), ports.map { it.port })

        val nodePort = ports.first { it.port == 3000 }
        assertEquals("node", nodePort.processName)
        assertEquals(1234, nodePort.pid)
        assertEquals("127.0.0.1", nodePort.bindAddress)

        val vitePort = ports.first { it.port == 5173 }
        assertEquals("vite", vitePort.processName)
        assertEquals("127.0.0.1", vitePort.bindAddress)

        val openCode = ports.first { it.port == 4096 }
        assertTrue(openCode.isOpenCodePort)
        assertEquals("OpenCode", openCode.displayProcess)
    }

    @Test
    fun parseListeningPorts_parsesNetstatOutput() {
        val netstatOutput = """
            Active Internet connections (only servers)
            Proto Recv-Q Send-Q Local Address           Foreign Address         State       PID/Program name
            tcp        0      0 0.0.0.0:22              0.0.0.0:*               LISTEN      400/sshd
            tcp        0      0 127.0.0.1:8000          0.0.0.0:*               LISTEN      4321/python3
            tcp6       0      0 :::9000                 :::*                    LISTEN      999/uvicorn
        """.trimIndent()

        val ports = PortForwardParser.parseListeningPorts(netstatOutput)
        assertEquals(listOf(8000, 9000), ports.map { it.port })
        assertEquals("python3", ports[0].processName)
        assertEquals(4321, ports[0].pid)
        assertEquals("uvicorn", ports[1].processName)
        assertEquals("0.0.0.0", ports[1].bindAddress)
    }

    @Test
    fun portForwardValidation_acceptsValidAndRejectsUnsafeInputs() {
        assertEquals(3000, PortForwardParser.validatePort(" 3000 "))
        assertEquals(65535, PortForwardParser.validatePort("65535"))
        assertNull(PortForwardParser.validatePort("0"))
        assertNull(PortForwardParser.validatePort("70000"))
        assertNull(PortForwardParser.validatePort("abc"))

        assertEquals("127.0.0.1", PortForwardParser.validateRemoteHost(""))
        assertEquals("localhost", PortForwardParser.validateRemoteHost(" localhost "))
        assertEquals("10.0.0.5", PortForwardParser.validateRemoteHost("10.0.0.5"))
        assertNull(PortForwardParser.validateRemoteHost("127.0.0.1; rm -rf /"))
        assertNull(PortForwardParser.validateRemoteHost("host with spaces"))
    }

    @Test
    fun terminalSearch_findsMatchingLinesCaseInsensitively() {
        val lines = listOf(
            "$ npm run dev",
            "VITE v5.4.0  ready in 220 ms",
            "  ➜  Local:   http://localhost:5173/",
            "Error: Port 5173 already in use",
            "ERROR: exiting process",
        )

        assertEquals(listOf(3, 4), TerminalViewModel.findMatchingLineIndices(lines, "error"))
        assertEquals(listOf(1), TerminalViewModel.findMatchingLineIndices(lines, "vite"))
        assertTrue(TerminalViewModel.findMatchingLineIndices(lines, "   ").isEmpty())
    }

    @Test
    fun codeEditorHelpers_computePositionOffsetAndDuplicateLine() {
        val code = "val a = 1\nval b = 2\nval c = 3"

        assertEquals(1 to 1, CodeEditorViewModel.computeLineAndColumn(code, 0))
        assertEquals(2 to 1, CodeEditorViewModel.computeLineAndColumn(code, 10))
        assertEquals(2 to 5, CodeEditorViewModel.computeLineAndColumn(code, 14))
        assertEquals(3 to 10, CodeEditorViewModel.computeLineAndColumn(code, code.length))

        assertEquals(0, CodeEditorViewModel.computeOffsetForLine(code, 1))
        assertEquals(10, CodeEditorViewModel.computeOffsetForLine(code, 2))
        assertEquals(20, CodeEditorViewModel.computeOffsetForLine(code, 3))
        // Out of range clamps to start of last line
        assertEquals(20, CodeEditorViewModel.computeOffsetForLine(code, 99))

        // Duplicate middle line
        val (dupMiddle, cursorMiddle) = CodeEditorViewModel.duplicateLineAtCursor(code, 14)
        assertEquals("val a = 1\nval b = 2\nval b = 2\nval c = 3", dupMiddle)
        assertEquals(3 to 10, CodeEditorViewModel.computeLineAndColumn(dupMiddle, cursorMiddle))

        // Duplicate first line at offset 0
        val (dupFirst, _) = CodeEditorViewModel.duplicateLineAtCursor(code, 0)
        assertEquals("val a = 1\nval a = 1\nval b = 2\nval c = 3", dupFirst)
    }
}
