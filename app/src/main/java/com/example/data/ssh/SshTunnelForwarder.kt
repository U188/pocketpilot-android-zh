package com.example.data.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Opens a loopback-only local TCP listener and forwards traffic over the SSH tunnel
 * to 127.0.0.1:<remotePort> on the remote server so the OpenCode port stays private.
 */
class SshTunnelForwarder private constructor(
    val localPort: Int,
    private val serverSocket: ServerSocket,
    private val forwarder: LocalPortForwarder,
    private val listenJob: Job,
) : Closeable {

    override fun close() {
        runCatching { forwarder.close() }
        runCatching { serverSocket.close() }
        listenJob.cancel()
    }

    companion object {
        fun start(
            sshClient: SSHClient,
            remotePort: Int,
            scope: CoroutineScope,
            remoteHost: String = "127.0.0.1",
            preferredLocalPort: Int = 0,
        ): SshTunnelForwarder {
            val loopback = InetAddress.getByName("127.0.0.1")
            val requestedPort = preferredLocalPort.takeIf { it in 1..65535 } ?: 0
            val serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(loopback, requestedPort))
            }
            val localPort = serverSocket.localPort
            val params = Parameters(
                "127.0.0.1",
                localPort,
                remoteHost,
                remotePort,
            )
            val forwarder = sshClient.newLocalPortForwarder(params, serverSocket)
            val listenJob = scope.launch(Dispatchers.IO) {
                runCatching {
                    forwarder.listen()
                }
            }
            return SshTunnelForwarder(
                localPort = localPort,
                serverSocket = serverSocket,
                forwarder = forwarder,
                listenJob = listenJob,
            )
        }

        fun probeTunnelEndpoint(localPort: Int, timeoutMs: Int = 1500): Boolean = runCatching {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress("127.0.0.1", localPort), timeoutMs)
                // Send a minimal HTTP HEAD probe; if the remote port is closed, SSHJ immediately
                // tears down the direct-tcpip channel and read/write throws or returns -1 cleanly.
                socket.getOutputStream().apply {
                    write("HEAD / HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                    flush()
                }
                val firstByte = socket.getInputStream().read()
                firstByte != -1
            }
        }.getOrDefault(false)
    }
}
