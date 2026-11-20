package com.example.data.terminal

import com.example.data.ssh.SshConnectionManager
import com.example.domain.model.TerminalTabStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class SshTerminalSession(
    val id: String,
    val defaultTitle: String,
    private val connectionManager: SshConnectionManager,
    initialCols: Int = 80,
    initialRows: Int = 24,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buffer = AnsiTerminalBuffer(initialCols = initialCols, initialRows = initialRows)
    private val writeMutex = Mutex()

    private val _status = MutableStateFlow(TerminalTabStatus.CONNECTING)
    val status: StateFlow<TerminalTabStatus> = _status.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _snapshot = MutableStateFlow(buffer.snapshot())
    val snapshot: StateFlow<TerminalScreenSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var sshSession: Session? = null

    @Volatile
    private var shell: Session.Shell? = null

    @Volatile
    private var outputStream: OutputStream? = null

    private var readerJob: Job? = null
    private var currentCols = initialCols
    private var currentRows = initialRows

    fun start() {
        readerJob?.cancel()
        closeStreams()
        _status.value = TerminalTabStatus.CONNECTING
        _errorMessage.value = null

        readerJob = scope.launch {
            try {
                val client = connectionManager.getActiveSshClient()
                    ?: throw IllegalStateException("SSH-соединение не активно")

                val session = client.startSession()
                sshSession = session
                session.allocatePTY("xterm-256color", currentCols, currentRows, 0, 0, emptyMap())
                val openedShell = session.startShell()
                shell = openedShell
                outputStream = openedShell.outputStream
                _status.value = TerminalTabStatus.CONNECTED

                val input = openedShell.inputStream
                val rawBuf = ByteArray(4096)
                val decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                var leftoverBytes = ByteArray(0)
                var lastEmitTime = 0L

                while (isActive) {
                    val readCount = input.read(rawBuf)
                    if (readCount == -1) break
                    if (readCount <= 0) continue

                    val combined = if (leftoverBytes.isEmpty()) {
                        rawBuf.copyOf(readCount)
                    } else {
                        ByteArray(leftoverBytes.size + readCount).also { dst ->
                            System.arraycopy(leftoverBytes, 0, dst, 0, leftoverBytes.size)
                            System.arraycopy(rawBuf, 0, dst, leftoverBytes.size, readCount)
                        }
                    }

                    val byteBuffer = ByteBuffer.wrap(combined)
                    val charBuffer = CharBuffer.allocate(combined.size + 4)
                    decoder.decode(byteBuffer, charBuffer, false)
                    charBuffer.flip()
                    val decoded = charBuffer.toString()

                    leftoverBytes = if (byteBuffer.hasRemaining()) {
                        ByteArray(byteBuffer.remaining()).also { byteBuffer.get(it) }
                    } else {
                        ByteArray(0)
                    }

                    if (decoded.isNotEmpty()) {
                        buffer.feed(decoded)
                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime >= RENDER_THROTTLE_MS || input.available() == 0) {
                            _snapshot.value = buffer.snapshot()
                            lastEmitTime = now
                        }
                    }
                }

                _snapshot.value = buffer.snapshot()
                if (_status.value == TerminalTabStatus.CONNECTED) {
                    _status.value = TerminalTabStatus.CLOSED
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _errorMessage.value = t.message ?: "Ошибка SSH PTY"
                _status.value = TerminalTabStatus.ERROR
            } finally {
                closeStreams()
            }
        }
    }

    fun sendBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        scope.launch {
            writeMutex.withLock {
                val out = outputStream ?: return@withLock
                runCatching {
                    out.write(bytes)
                    out.flush()
                }.onFailure { err ->
                    _errorMessage.value = err.message ?: "Не удалось отправить ввод"
                }
            }
        }
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        sendBytes(text.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Sends long text in small chunks with brief pacing so remote PTY canonical
     * input buffers and SSH window control never drop or truncate pasted blocks.
     */
    suspend fun sendTextChunked(
        text: String,
        chunkSizeBytes: Int = PASTE_CHUNK_BYTES,
        delayBetweenChunksMs: Long = PASTE_CHUNK_DELAY_MS,
    ) = withContext(Dispatchers.IO) {
        if (text.isEmpty()) return@withContext
        val normalized = text.replace("\r\n", "\r").replace("\n", "\r")
        val allBytes = normalized.toByteArray(StandardCharsets.UTF_8)
        writeMutex.withLock {
            val out = outputStream ?: return@withLock
            var offset = 0
            while (offset < allBytes.size && isActive) {
                val end = (offset + chunkSizeBytes).coerceAtMost(allBytes.size)
                out.write(allBytes, offset, end - offset)
                out.flush()
                offset = end
                if (offset < allBytes.size) {
                    delay(delayBetweenChunksMs)
                }
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        val clampedCols = cols.coerceIn(20, 240)
        val clampedRows = rows.coerceIn(8, 120)
        if (clampedCols == currentCols && clampedRows == currentRows) return
        currentCols = clampedCols
        currentRows = clampedRows
        buffer.resize(clampedCols, clampedRows)
        _snapshot.value = buffer.snapshot()
        scope.launch {
            runCatching {
                shell?.changeWindowDimensions(clampedCols, clampedRows, 0, 0)
            }
        }
    }

    fun clearScreen() {
        buffer.clearAll()
        _snapshot.value = buffer.snapshot()
    }

    fun getPlainOutput(): String = _snapshot.value.plainLines.joinToString("\n")

    fun close() {
        readerJob?.cancel()
        readerJob = null
        closeStreams()
        scope.cancel()
    }

    private fun closeStreams() {
        outputStream = null
        val sh = shell
        shell = null
        runCatching { sh?.close() }
        val sess = sshSession
        sshSession = null
        runCatching { sess?.close() }
    }

    companion object {
        private const val RENDER_THROTTLE_MS = 32L
        const val PASTE_CHUNK_BYTES = 256
        const val PASTE_CHUNK_DELAY_MS = 16L
    }
}
