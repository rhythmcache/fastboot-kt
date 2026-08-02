package io.github.rhythmcache.fastboot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

class FastbootConnection(
    private val transport: FastbootTransport,
) : Closeable {
    // fastboot is  half-duplex/lockstep; serialize concurrent callers
    // so command and response bytes never interleave on the wire.
    private val mutex = Mutex()

    @Volatile
    private var closed = false

    val isClosed: Boolean get() = closed

    suspend fun rawCommand(
        cmd: String,
        onInfo: ((String) -> Unit)? = null,
        onText: ((String) -> Unit)? = null,
    ): FastbootResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                writeCommand(cmd)
                val infoLines = mutableListOf<String>()
                val buf = ByteArray(FastbootConst.RESPONSE_MAX_SIZE)
                while (true) {
                    when (val resp = readOneResponse(buf)) {
                        is RawResponse.Info -> {
                            onInfo?.invoke(resp.message)
                            infoLines += resp.message
                        }
                        is RawResponse.Text -> onText?.invoke(resp.message)
                        is RawResponse.Okay -> return@withContext FastbootResult(resp.message, infoLines)
                        is RawResponse.Fail -> throw FastbootException.DeviceFail("remote: '${resp.message}'")
                        is RawResponse.Data -> throw FastbootException.Protocol(
                            "Unexpected DATA response in rawCommand (no data phase handler)",
                        )
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                throw FastbootException.Protocol("Unreachable")
            }
        }

    /**
     * Runs a full DATA-phase send cycle under a single lock: command, ack, bytes, terminal.
     * Locking the whole cycle (not sub-calls) is what prevents interleaving with other callers.
     */
    internal suspend fun executeDownload(
        size: Long,
        source: (offset: Long, dest: ByteArray, destOffset: Int, length: Int) -> Int,
        chunkSize: Int = 1024 * 1024,
        onProgress: ((Progress) -> Unit)? = null,
    ): FastbootResult {
        if (size <= 0 || size.toULong() > FastbootConst.MAX_DOWNLOAD_SIZE.toULong()) {
            throw FastbootException.BadArgument(
                "Download size out of range (got $size, max ${FastbootConst.MAX_DOWNLOAD_SIZE})",
            )
        }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                writeCommand(Commands.download(size))
                val buf = ByteArray(FastbootConst.RESPONSE_MAX_SIZE)
                when (val resp = readOneResponse(buf)) {
                    is RawResponse.Data -> { /* proceed */ }
                    is RawResponse.Fail -> throw FastbootException.DeviceFail("remote: '${resp.message}'")
                    else -> throw FastbootException.Protocol("Expected DATA response to download")
                }
                sendChunked(size, source, chunkSize, onProgress)
                readTerminalLocked(buf)
            }
        }
    }

    /**
     * Runs a full DATA-phase receive cycle: command, size ack, bytes, terminal - under one lock.
     */
    internal suspend fun executeUpload(
        cmd: String,
        sink: (data: ByteArray, length: Int) -> Unit,
        chunkSize: Int = 1024 * 1024,
        onProgress: ((Progress) -> Unit)? = null,
    ): FastbootResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                writeCommand(cmd)
                val buf = ByteArray(FastbootConst.RESPONSE_MAX_SIZE)
                val size =
                    when (val resp = readOneResponse(buf)) {
                        is RawResponse.Data -> resp.size
                        is RawResponse.Fail -> throw FastbootException.DeviceFail("remote: '${resp.message}'")
                        else -> throw FastbootException.Protocol("Expected DATA response to $cmd")
                    }
                if (size <= 0) {
                    throw FastbootException.Protocol("$cmd request failed, device reports $size bytes available")
                }
                recvChunked(size, chunkSize, sink, onProgress)
                readTerminalLocked(buf)
            }
        }

    private fun recvChunked(
        size: Long,
        chunkSize: Int,
        sink: (data: ByteArray, length: Int) -> Unit,
        onProgress: ((Progress) -> Unit)?,
    ) {
        val chunk = ByteArray(chunkSize)
        var received = 0L
        while (received < size) {
            val want = minOf(chunkSize.toLong(), size - received).toInt()
            val n =
                try {
                    transport.read(chunk, 0, want)
                } catch (e: Exception) {
                    throw FastbootException.Io("Read from device failed in recvChunked()", e)
                }
            if (n <= 0) throw FastbootException.Io("Unexpected EOF while reading data phase")
            sink(chunk, n)
            received += n
            onProgress?.invoke(Progress(received, size))
        }
    }

    private fun writeCommand(cmd: String) {
        val bytes = cmd.toByteArray(Charsets.UTF_8)
        if (bytes.size > FastbootConst.MAX_COMMAND_SIZE) {
            throw FastbootException.BadArgument("Command exceeds max size (${FastbootConst.MAX_COMMAND_SIZE} bytes)")
        }
        try {
            transport.write(bytes, 0, bytes.size)
        } catch (e: Exception) {
            throw FastbootException.Io("Write to device failed", e)
        }
    }

    private suspend fun readTerminalLocked(buf: ByteArray): FastbootResult {
        val infoLines = mutableListOf<String>()
        while (true) {
            when (val resp = readOneResponse(buf)) {
                is RawResponse.Info -> infoLines += resp.message
                is RawResponse.Text -> { /* ignore */ }
                is RawResponse.Okay -> return FastbootResult(resp.message, infoLines)
                is RawResponse.Fail -> throw FastbootException.DeviceFail("remote: '${resp.message}'")
                is RawResponse.Data -> throw FastbootException.Protocol("Unexpected DATA while awaiting terminal status")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw FastbootException.Protocol("Unreachable")
    }

    private suspend fun readOneResponse(buf: ByteArray): RawResponse {
        val n =
            try {
                withTimeoutOrNull(FastbootConst.RESPONSE_TIMEOUT_MS) {
                    transport.read(buf, 0, FastbootConst.RESPONSE_MAX_SIZE)
                } ?: throw FastbootException.Timeout()
            } catch (e: FastbootException) {
                throw e
            } catch (e: Exception) {
                throw FastbootException.Io("Status read failed", e)
            }
        if (n <= 0) throw FastbootException.Io("Status read failed: EOF")

        val text = buf.decodeToString(0, n).substringBefore('\u0000')

        return when {
            text.startsWith("INFO") -> RawResponse.Info(text.substring(4))
            text.startsWith("OKAY") -> RawResponse.Okay(text.substring(4))
            text.startsWith("FAIL") -> RawResponse.Fail(text.substring(4))
            text.startsWith("TEXT") -> RawResponse.Text(text.substring(4))
            text.startsWith("DATA") -> {
                val hex = text.substring(4).trim()
                val size =
                    hex.toLongOrNull(16)
                        ?: throw FastbootException.Protocol("Invalid DATA size field: '$hex'")
                if (size > FastbootConst.MAX_DOWNLOAD_SIZE) throw FastbootException.Protocol("Data size too large ($size)")
                RawResponse.Data(size)
            }
            else -> throw FastbootException.Protocol("Device sent unknown status code: $text")
        }
    }

    /** Sends [total] bytes pulled from [source] into a single reusable chunk buffer no per chunk allocation. */
    private fun sendChunked(
        total: Long,
        source: (offset: Long, dest: ByteArray, destOffset: Int, length: Int) -> Int,
        chunkSize: Int,
        onProgress: ((Progress) -> Unit)?,
    ) {
        val chunk = ByteArray(chunkSize)
        var sent = 0L
        while (sent < total) {
            val want = minOf(chunkSize.toLong(), total - sent).toInt()
            val n = source(sent, chunk, 0, want)
            if (n <= 0) throw FastbootException.Io("Source produced no data at offset $sent")
            try {
                transport.write(chunk, 0, n)
            } catch (e: Exception) {
                throw FastbootException.Io("Write to device failed in sendChunked()", e)
            }
            sent += n
            onProgress?.invoke(Progress(sent, total))
        }
    }

    fun waitForDisconnect() = transport.waitForDisconnect()

    override fun close() {
        closed = true
        transport.close()
    }
}
