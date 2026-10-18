package io.github.rhythmcache.fastboot

import io.github.rhythmcache.fastboot.sparse.ByteArraySource
import io.github.rhythmcache.fastboot.sparse.FileChannelSource
import io.github.rhythmcache.fastboot.sparse.ImageAnalyzer
import io.github.rhythmcache.fastboot.sparse.RandomAccessSource
import io.github.rhythmcache.fastboot.sparse.SparseFormatException
import io.github.rhythmcache.fastboot.sparse.SparsePlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Path

class FastbootClient(
    private val connection: FastbootConnection,
) : Closeable {
    constructor(transport: FastbootTransport) : this(FastbootConnection(transport))
    enum class RebootTarget(val wire: String?) {
        SYSTEM(null),
        BOOTLOADER("bootloader"),
        RECOVERY("recovery"),
        FASTBOOT("fastboot"),
    }

    enum class LockMode(val wire: String) {
        LOCK(Commands.FLASHING_LOCK),
        UNLOCK(Commands.FLASHING_UNLOCK),
        LOCK_CRITICAL(Commands.FLASHING_LOCK_CRITICAL),
        UNLOCK_CRITICAL(Commands.FLASHING_UNLOCK_CRITICAL),
    }

    enum class SnapshotCommand(val wire: String) { Cancel("cancel"), Merge("merge") }

    companion object {
        /** Connects to a Fastboot over TCP device and returns a [FastbootClient]. */
        fun connectTcp(
            host: String,
            port: Int = TcpFastbootTransport.DEFAULT_PORT,
            timeoutMs: Int = TcpFastbootTransport.DEFAULT_HANDSHAKE_TIMEOUT_MS,
        ): FastbootClient =
            FastbootClient(
                TcpFastbootTransport.connect(
                    host = host,
                    port = port,
                    connectTimeoutMs = timeoutMs,
                    handshakeTimeoutMs = timeoutMs,
                ),
            )

        /** Largest piece ever sent in one download, matching AOSP fastboot's RESPARSE_LIMIT (1 GiB). */
        const val MAX_CHUNK_SIZE: Long = 1L shl 30

        /** Piece size used when the device does not report a usable `max-download-size` (AOSP would not split at all). */
        const val DEFAULT_CHUNK_SIZE: Long = 256L shl 20
    }

    // Variables

    suspend fun getVar(key: String): String = connection.rawCommand(Commands.getVar(key)).response

    /**
     * The device's `max-download-size`, or null if it is missing, unparsable or the query fails with FAIL.
     * Parsed like AOSP fastboot: surrounding whitespace is ignored, a `0x` prefix means hex, anything else decimal.
     */
    suspend fun getMaxDownloadSize(): Long? =
        try {
            parseDeviceSize(getVar("max-download-size"))
        } catch (e: FastbootException.DeviceFail) {
            null
        }

    suspend fun getAllVars(): Map<String, String> {
        val vars = LinkedHashMap<String, String>()
        connection.rawCommand(Commands.getVar("all"), onInfo = { line ->
            val idx = line.indexOf(':')
            if (idx > 0) vars[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        })
        return vars
    }

    // Flashing ->> the main user facing entry points. download() is internal.
    //
    // Every flash() overload asks the device for max-download-size first (unless [maxChunkSize] is given,
    // like `fastboot -S`). An image that fits is sent as is. A larger one is converted/resplit into
    // self-contained Android sparse pieces that each fit, and every piece is downloaded then flashed in order.

    /** Flash [partition] from an in memory image. */
    suspend fun flash(
        partition: String,
        image: ByteArray,
        onInfo: ((String) -> Unit)? = null,
        onProgress: ((Progress) -> Unit)? = null,
        maxChunkSize: Long? = null,
    ): FastbootResult {
        val limit = resolveChunkLimit(maxChunkSize)
        if (image.size.toLong() > limit) {
            return flashSplit(partition, ByteArraySource(image), limit, onInfo, onProgress)
        }
        download(image.size.toLong(), byteArraySource(image), onProgress)
        return connection.rawCommand(Commands.flash(partition), onInfo = onInfo)
    }

    /** Flash [partition] by streaming directly from [file] */
    suspend fun flash(
        partition: String,
        file: File,
        onInfo: ((String) -> Unit)? = null,
        onProgress: ((Progress) -> Unit)? = null,
        maxChunkSize: Long? = null,
    ): FastbootResult {
        val limit = resolveChunkLimit(maxChunkSize)
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            if (size > limit) {
                return flashSplit(partition, FileChannelSource(raf.channel, size), limit, onInfo, onProgress)
            }
            download(size, randomAccessFileSource(raf), onProgress)
        }
        return connection.rawCommand(Commands.flash(partition), onInfo = onInfo)
    }

    /** Flash [partition] from [path]. */
    suspend fun flash(
        partition: String,
        path: Path,
        onInfo: ((String) -> Unit)? = null,
        onProgress: ((Progress) -> Unit)? = null,
        maxChunkSize: Long? = null,
    ): FastbootResult = flash(partition, path.toFile(), onInfo, onProgress, maxChunkSize)

    /**
     * Flash [partition] from a FileDescriptor (let say resolved from a SAF content:// URI).
     * An image that fits in one download is read sequentially (works on pipes too). A larger image needs
     * positional reads, so the descriptor must be seekable; otherwise this throws [FastbootException.BadArgument].
     */
    suspend fun flash(
        partition: String,
        fd: FileDescriptor,
        size: Long,
        onInfo: ((String) -> Unit)? = null,
        onProgress: ((Progress) -> Unit)? = null,
        maxChunkSize: Long? = null,
    ): FastbootResult {
        val limit = resolveChunkLimit(maxChunkSize)
        FileInputStream(fd).use { stream ->
            if (size > limit) {
                return flashSplit(partition, seekableSource(stream.channel, size), limit, onInfo, onProgress)
            }
            download(size, streamSource(stream), onProgress)
        }
        return connection.rawCommand(Commands.flash(partition), onInfo = onInfo)
    }

    // Boot stages an image and boots it without flashing. Mirrors 'fastboot boot <image>'.

    /** Boots whatever image is currently staged on the device (no local image supplied). */
    suspend fun boot(onInfo: ((String) -> Unit)? = null): FastbootResult = connection.rawCommand(Commands.BOOT, onInfo = onInfo)

    /** Stages [image] and boots it without flashing... equivalent to 'fastboot boot <image>'. */
    suspend fun boot(
        image: ByteArray,
        onInfo: ((String) -> Unit)? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ): FastbootResult {
        download(image.size.toLong(), byteArraySource(image), onProgress)
        return connection.rawCommand(Commands.BOOT, onInfo = onInfo)
    }

    /** Stages [file] and boots it without flashing. */
    suspend fun boot(
        file: File,
        onInfo: ((String) -> Unit)? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ): FastbootResult {
        RandomAccessFile(file, "r").use { raf ->
            download(raf.length(), randomAccessFileSource(raf), onProgress)
        }
        return connection.rawCommand(Commands.BOOT, onInfo = onInfo)
    }

    /** Stages [path] and boots it without flashing. */
    suspend fun boot(
        path: Path,
        onInfo: ((String) -> Unit)? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ): FastbootResult = boot(path.toFile(), onInfo, onProgress)

    /** Stages an image from a FileDescriptor (SAF) and boots it without flashing. */
    suspend fun boot(
        fd: FileDescriptor,
        size: Long,
        onInfo: ((String) -> Unit)? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ): FastbootResult {
        FileInputStream(fd).use { stream ->
            download(size, streamSource(stream), onProgress)
        }
        return connection.rawCommand(Commands.BOOT, onInfo = onInfo)
    }

    // Stage uploads an image to the device's RAM buffer without flashing or booting. Mirrors 'fastboot stage <image>'.

    /** Stages [image] into device RAM without flashing or booting. */
    suspend fun stage(
        image: ByteArray,
        onProgress: ((Progress) -> Unit)? = null,
    ): FastbootResult = download(image.size.toLong(), byteArraySource(image), onProgress)

    /** Stages [file] into device RAM without flashing or booting. */
    suspend fun stage(
        file: File,
        onProgress: ((Progress) -> Unit)? = null,
    ): FastbootResult {
        return RandomAccessFile(file, "r").use { raf ->
            download(raf.length(), randomAccessFileSource(raf), onProgress)
        }
    }

    /** Stages [path] into device RAM without flashing or booting. */
    suspend fun stage(
        path: Path,
        onProgress: ((Progress) -> Unit)? = null,
    ): FastbootResult = stage(path.toFile(), onProgress)

    /** Stages an image from a FileDescriptor (SAF) into device RAM without flashing or booting. */
    suspend fun stage(
        fd: FileDescriptor,
        size: Long,
        onProgress: ((Progress) -> Unit)? = null,
    ): FastbootResult {
        return FileInputStream(fd).use { stream ->
            download(size, streamSource(stream), onProgress)
        }
    }

    // Internal source adapters + staging

    private fun byteArraySource(image: ByteArray): (Long, ByteArray, Int, Int) -> Int =
        { offset, dest, destOffset, length ->
            System.arraycopy(image, offset.toInt(), dest, destOffset, length)
            length
        }

    /** Sequential read via RandomAccessFile - no seek() since sendChunked() always reads in increasing order. */
    private fun randomAccessFileSource(raf: RandomAccessFile): (Long, ByteArray, Int, Int) -> Int =
        { _, dest, destOffset, length -> raf.read(dest, destOffset, length) }

    private fun streamSource(stream: InputStream): (Long, ByteArray, Int, Int) -> Int =
        { _, dest, destOffset, length -> readFully(stream, dest, destOffset, length) }

    /** Reads exactly [length] bytes looping since a single stream.read() may return fewer. Throws on EOF before [length] is reached. */
    private fun readFully(
        stream: InputStream,
        dest: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        var total = 0
        while (total < length) {
            val n = stream.read(dest, offset + total, length - total)
            if (n < 0) {
                throw FastbootException.Io(
                    "Unexpected EOF while reading source data (got $total of $length bytes)",
                )
            }
            total += n
        }
        return total
    }

    /** Per-piece limit: the caller's override if given, else the device's max-download-size; always capped at [MAX_CHUNK_SIZE]. */
    private suspend fun resolveChunkLimit(override: Long?): Long {
        if (override != null) {
            if (override <= 0) throw FastbootException.BadArgument("maxChunkSize must be greater than 0")
            return minOf(override, MAX_CHUNK_SIZE)
        }
        return minOf(getMaxDownloadSize() ?: DEFAULT_CHUNK_SIZE, MAX_CHUNK_SIZE)
    }

    private fun seekableSource(
        channel: FileChannel,
        size: Long,
    ): RandomAccessSource {
        try {
            FileChannelSource.requireSeekable(channel)
        } catch (e: IOException) {
            throw FastbootException.BadArgument(
                "This image is larger than the device's download limit and has to be flashed in pieces, " +
                    "but the file is not seekable (${e.message}). Copy it to local storage and try again.",
            )
        }
        return FileChannelSource(channel, size)
    }

    /**
     * Converts/resplits [src] into sparse pieces of at most [limit] bytes and flashes them in order.
     * A failure aborts the loop, like AOSP fastboot; pieces already flashed stay on the device.
     */
    private suspend fun flashSplit(
        partition: String,
        src: RandomAccessSource,
        limit: Long,
        onInfo: ((String) -> Unit)?,
        onProgress: ((Progress) -> Unit)?,
    ): FastbootResult {
        onInfo?.invoke("Scanning image for chunked flash of '$partition' (max ${limit / 1024} KB per chunk)...")
        val pieces =
            try {
                withContext(Dispatchers.IO) { SparsePlanner.plan(ImageAnalyzer.analyze(src), limit) }
            } catch (e: SparseFormatException) {
                throw FastbootException.BadArgument("Invalid sparse image: ${e.message}")
            } catch (e: IllegalArgumentException) {
                throw FastbootException.BadArgument(e.message ?: "Chunk limit too small")
            } catch (e: IOException) {
                throw FastbootException.Io("Failed to read image: ${e.message}", e)
            }

        val total = pieces.sumOf { it.size }
        var done = 0L
        var last = FastbootResult("", emptyList())
        for ((i, piece) in pieces.withIndex()) {
            val n = i + 1
            onInfo?.invoke("Sending sparse '$partition' $n/${pieces.size} (${piece.size / 1024} KB)")
            val base = done
            connection.executeDownload(
                piece.size,
                { offset, dest, destOffset, length ->
                    try {
                        piece.read(src, offset, dest, destOffset, length)
                    } catch (e: IOException) {
                        throw FastbootException.Io("Failed to read image data at $offset: ${e.message}", e)
                    }
                },
                onProgress = { p -> onProgress?.invoke(Progress(base + p.current, total)) },
            )
            done += piece.size
            onInfo?.invoke("Writing '$partition' $n/${pieces.size}")
            last = connection.rawCommand(Commands.flash(partition), onInfo = onInfo)
        }
        return last
    }

    /** Stages an image without flashing/booting it -> shared by flash(), boot(), and stage() overloads. */
    private suspend fun download(
        size: Long,
        source: (offset: Long, dest: ByteArray, destOffset: Int, length: Int) -> Int,
        onProgress: ((Progress) -> Unit)?,
    ): FastbootResult {
        if (size <= 0) throw FastbootException.BadArgument("Image size must be greater than 0 bytes")
        return connection.executeDownload(size, source, onProgress = onProgress)
    }

    // Upload / Fetch

    suspend fun upload(
        sink: (data: ByteArray, length: Int) -> Unit,
        onProgress: ((Progress) -> Unit)? = null,
    ) {
        connection.executeUpload(Commands.UPLOAD, sink, onProgress = onProgress)
    }

    suspend fun fetch(
        partition: String,
        sink: (data: ByteArray, length: Int) -> Unit,
        offset: Long? = null,
        size: Long? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ) {
        connection.executeUpload(Commands.fetch(partition, offset, size), sink, onProgress = onProgress)
    }

    // Erase

    suspend fun erase(
        partition: String,
        onInfo: ((String) -> Unit)? = null,
    ): FastbootResult = connection.rawCommand(Commands.erase(partition), onInfo = onInfo)

    // Boot control (non-staging)

    suspend fun continueBoot(onInfo: ((String) -> Unit)? = null): FastbootResult = connection.rawCommand(Commands.CONTINUE, onInfo = onInfo)

    suspend fun shutdown(onInfo: ((String) -> Unit)? = null): FastbootResult = connection.rawCommand(Commands.SHUTDOWN, onInfo = onInfo)

    suspend fun reboot(
        target: RebootTarget = RebootTarget.SYSTEM,
        onInfo: ((String) -> Unit)? = null,
    ): FastbootResult {
        val cmd = target.wire?.let { Commands.rebootTo(it) } ?: Commands.REBOOT
        return connection.rawCommand(cmd, onInfo = onInfo)
    }

    /** For any reboot target not covered by RebootTarget — vendor modes, or future standard targets. */
    suspend fun reboot(
        target: String,
        onInfo: ((String) -> Unit)? = null,
    ): FastbootResult = connection.rawCommand(Commands.rebootTo(target), onInfo = onInfo)

    // Slots / logical partitions

    suspend fun setActive(
        slot: String,
        onInfo: ((String) -> Unit)? = null,
    ): FastbootResult = connection.rawCommand(Commands.setActive(slot), onInfo = onInfo)

    suspend fun createLogicalPartition(
        partition: String,
        size: Long,
    ): FastbootResult = connection.rawCommand(Commands.createLogicalPartition(partition, size))

    suspend fun deleteLogicalPartition(partition: String): FastbootResult =
        connection.rawCommand(Commands.deleteLogicalPartition(partition))

    suspend fun resizeLogicalPartition(
        partition: String,
        size: Long,
    ): FastbootResult = connection.rawCommand(Commands.resizeLogicalPartition(partition, size))

    suspend fun updateSuper(
        partition: String,
        wipe: Boolean = false,
        onInfo: ((String) -> Unit)? = null,
    ): FastbootResult = connection.rawCommand(Commands.updateSuper(partition, wipe), onInfo = onInfo)

    // Snapshot update

    suspend fun snapshotUpdate(
        command: SnapshotCommand,
        onInfo: ((String) -> Unit)? = null,
    ): FastbootResult = connection.rawCommand(Commands.snapshotUpdate(command.wire), onInfo = onInfo)

    // OEM / GSI

    suspend fun oemCommand(
        cmd: String,
        onInfo: ((String) -> Unit)? = null,
    ): FastbootResult = connection.rawCommand(Commands.oem(cmd), onInfo = onInfo)

    suspend fun gsiCommand(
        cmd: String,
        onInfo: ((String) -> Unit)? = null,
    ): FastbootResult = connection.rawCommand(Commands.gsi(cmd), onInfo = onInfo)

    // Lock state

    suspend fun setLockMode(mode: LockMode): FastbootResult = connection.rawCommand(mode.wire)

    suspend fun flashingGetUnlockAbility(): Boolean = connection.rawCommand(Commands.FLASHING_GET_UNLOCK_ABILITY).response.trim() == "1"

    // Escape hatch

    suspend fun rawCommand(
        cmd: String,
        onInfo: ((String) -> Unit)? = null,
    ): FastbootResult = connection.rawCommand(cmd, onInfo = onInfo)

    /** Blocks until the device disconnects */
    fun waitForDisconnect() = connection.waitForDisconnect()

    val isClosed: Boolean get() = connection.isClosed

    override fun close() = connection.close()
}
