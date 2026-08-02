package io.github.rhythmcache.fastboot

import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Path

class FastbootClient(
    private val connection: FastbootConnection,
) : Closeable {
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

    // Variables

    suspend fun getVar(key: String): String = connection.rawCommand(Commands.getVar(key)).response

    suspend fun getAllVars(): Map<String, String> {
        val vars = LinkedHashMap<String, String>()
        connection.rawCommand(Commands.getVar("all"), onInfo = { line ->
            val idx = line.indexOf(':')
            if (idx > 0) vars[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        })
        return vars
    }

    // Flashing ->> the main user facing entry points. download() is internal.

    /** Flash [partition] from an in memory image. */
    suspend fun flash(
        partition: String,
        image: ByteArray,
        onInfo: ((String) -> Unit)? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ): FastbootResult {
        download(image.size.toLong(), byteArraySource(image), onProgress)
        return connection.rawCommand(Commands.flash(partition), onInfo = onInfo)
    }

    /** Flash [partition] by streaming directly from [file] */
    suspend fun flash(
        partition: String,
        file: File,
        onInfo: ((String) -> Unit)? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ): FastbootResult {
        RandomAccessFile(file, "r").use { raf ->
            download(raf.length(), randomAccessFileSource(raf), onProgress)
        }
        return connection.rawCommand(Commands.flash(partition), onInfo = onInfo)
    }

    /** Flash [partition] from [path]. */
    suspend fun flash(
        partition: String,
        path: Path,
        onInfo: ((String) -> Unit)? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ): FastbootResult = flash(partition, path.toFile(), onInfo, onProgress)

    /** Flash [partition] from a FileDescriptor (let say resolved from a SAF content:// URI). Reads sequentially. */
    suspend fun flash(
        partition: String,
        fd: FileDescriptor,
        size: Long,
        onInfo: ((String) -> Unit)? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ): FastbootResult {
        FileInputStream(fd).use { stream ->
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

    /** Stages an image without flashing/booting it -> shared by flash() and boot() overloads. */
    private suspend fun download(
        size: Long,
        source: (offset: Long, dest: ByteArray, destOffset: Int, length: Int) -> Int,
        onProgress: ((Progress) -> Unit)?,
    ) {
        if (size <= 0) throw FastbootException.BadArgument("Image size must be greater than 0 bytes")
        connection.executeDownload(size, source, onProgress = onProgress)
    }

    // Upload / Fetch

    suspend fun upload(onProgress: ((Progress) -> Unit)? = null): ByteArray =
        connection.executeUpload(Commands.UPLOAD, onProgress = onProgress).first

    suspend fun fetch(
        partition: String,
        offset: Long? = null,
        size: Long? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ): ByteArray = connection.executeUpload(Commands.fetch(partition, offset, size), onProgress = onProgress).first

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
