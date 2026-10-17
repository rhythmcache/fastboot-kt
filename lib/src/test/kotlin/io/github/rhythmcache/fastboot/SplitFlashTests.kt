package io.github.rhythmcache.fastboot

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests: FastbootClient -> real FastbootConnection -> FakeBootloader.
 */
class SplitFlashTests {
    private val bs = 4096

    /** [dense] = mostly incompressible data, so the sparse form is about as big as the image. */
    private fun image(
        blocks: Int,
        tail: Int = 0,
        seed: Long = 1,
        dense: Boolean = false,
    ): ByteArray {
        val r = Random(seed)
        val out = ByteArray(blocks * bs + tail)
        var b = 0
        while (b < blocks) {
            val end = minOf(blocks, b + 1 + r.nextInt(30))
            when (if (dense && r.nextInt(10) != 0) 2 else r.nextInt(3)) {
                0 -> {}
                1 -> for (i in b * bs until end * bs) out[i] = (0x30 + i % 4).toByte()
                else -> {
                    val c = ByteArray((end - b) * bs)
                    r.nextBytes(c)
                    System.arraycopy(c, 0, out, b * bs, c.size)
                }
            }
            b = end
        }
        if (tail > 0) {
            val t = ByteArray(tail)
            r.nextBytes(t)
            System.arraycopy(t, 0, out, blocks * bs, tail)
        }
        return out
    }

    private fun client(dev: FakeBootloader) = FastbootClient(FastbootConnection(dev))

    /**
     * What the partition should hold. A sparse flash writes whole blocks, so the unaligned tail of the last
     * block is zero padded ([sparse] = true), exactly like AOSP fastboot; a raw flash leaves the rest untouched.
     */
    private fun expectedPartition(
        img: ByteArray,
        size: Int,
        sparse: Boolean = false,
    ): ByteArray =
        ByteArray(size) { FakeBootloader.SENTINEL }.also {
            System.arraycopy(img, 0, it, 0, img.size)
            if (sparse) java.util.Arrays.fill(it, img.size, (img.size + bs - 1) / bs * bs, 0)
        }

    // ---- the reported bug ------------------------------------------------------------------

    @Test
    fun testUnpatchedSingleDownloadReproducesTheReportedError() {
        val dev = FakeBootloader(maxDownload = 0x100000, maxDownloadReply = "0x100000", partitionSize = 4 shl 20)
        val conn = FastbootConnection(dev)
        val e =
            assertFailsWith<FastbootException.DeviceFail> {
                runBlocking {
                    conn.executeDownload(3_145_728, { _, d, o, l ->
                        java.util.Arrays.fill(d, o, o + l, 1)
                        l
                    })
                }
            }
        assertEquals("remote: 'Requested download size is more than max allowed'", e.message)
    }

    // ---- fix: raw image larger than the device limit ---------------------------------------

    @Test
    fun testRawFileLargerThanLimitIsFlashedInPiecesAndPartitionMatches() {
        val img = image(blocks = 900, tail = 321, dense = true) // ~3.6 MB, limit 1 MiB
        val dev = FakeBootloader(maxDownload = 0x100000, maxDownloadReply = "0x100000", partitionSize = 4 shl 20)
        val f = File.createTempFile("img", ".bin")
        f.writeBytes(img)
        f.deleteOnExit()

        val infos = ArrayList<String>()
        val progress = ArrayList<Pair<Long, Long>>()
        runBlocking {
            client(dev).flash(
                partition = "system",
                file = f,
                onInfo = { infos += it },
                onProgress = { progress += it.current to it.total },
            )
        }

        assertTrue(dev.downloadSizes.size >= 3, "expected several pieces, got ${dev.downloadSizes}")
        assertTrue(dev.downloadSizes.all { it <= 0x100000 }, "a piece exceeded max-download-size: ${dev.downloadSizes}")
        assertTrue(dev.stagedWasSparse.all { it })
        assertEquals(dev.downloadSizes.size, dev.commands.count { it == "flash:system" })
        assertContentEquals(expectedPartition(img, dev.partition.size, sparse = true), dev.partition)

        assertTrue(infos.any { it.startsWith("Sending sparse 'system' 1/${dev.downloadSizes.size}") }, "infos=$infos")
        val total = dev.downloadSizes.sum()
        assertEquals(total, progress.last().second)
        assertEquals(total, progress.last().first)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.first >= a.first }, "progress must not go backwards")
    }

    @Test
    fun testFileDescriptorPathMatchesFileDescriptorFromSaf() {
        val img = image(blocks = 700, tail = 0, seed = 4, dense = true)
        val dev = FakeBootloader(0x80000, "0x80000", partitionSize = 4 shl 20)
        val f = File.createTempFile("img", ".bin")
        f.writeBytes(img)
        f.deleteOnExit()

        FileInputStream(f).use {
            runBlocking {
                client(dev).flash("system", it.fd, img.size.toLong())
            }
        }
        assertTrue(dev.downloadSizes.all { it <= 0x80000 })
        assertContentEquals(expectedPartition(img, dev.partition.size), dev.partition)
    }

    @Test
    fun testByteArrayPathAndDecimalMaxDownloadSize() {
        val img = image(blocks = 300, tail = 17, seed = 5, dense = true)
        val dev = FakeBootloader(262144, "262144", partitionSize = 2 shl 20) // decimal, not hex
        runBlocking { client(dev).flash("vendor", img) }
        assertTrue(dev.downloadSizes.size > 1)
        assertTrue(dev.downloadSizes.all { it <= 262144 })
        assertContentEquals(expectedPartition(img, dev.partition.size, sparse = true), dev.partition)
    }

    @Test
    fun testMaxChunkSizeOverrideSplitsEvenWhenTheDeviceWouldAcceptTheWholeImage() {
        val img = image(blocks = 200, seed = 6, dense = true)
        val dev = FakeBootloader(maxDownload = 64L shl 20, maxDownloadReply = "0x4000000", partitionSize = 2 shl 20)
        runBlocking { client(dev).flash("system", img, maxChunkSize = 200_000) }
        assertTrue(dev.downloadSizes.size > 1)
        assertTrue(dev.downloadSizes.all { it <= 200_000 })
        assertTrue(dev.commands.none { it == "getvar:max-download-size" }, "override must skip the query")
        assertContentEquals(expectedPartition(img, dev.partition.size), dev.partition)
    }

    // ---- images that must NOT be converted -------------------------------------------------

    @Test
    fun testImageThatFitsIsSentAsIsWithoutSparseConversion() {
        val img = image(blocks = 20, tail = 99, seed = 8) // like a boot.img
        val dev = FakeBootloader(0x100000, "0x100000", partitionSize = 1 shl 20)
        runBlocking { client(dev).flash("boot", img) }
        assertEquals(listOf(img.size.toLong()), dev.downloadSizes)
        assertEquals(listOf(false), dev.stagedWasSparse)
        assertContentEquals(expectedPartition(img, dev.partition.size), dev.partition)
    }

    @Test
    fun testImageExactlyEqualToLimitIsStillSentAsIs() {
        val img = image(blocks = 64, seed = 9)
        val dev = FakeBootloader(img.size.toLong(), "0x%x".format(img.size), partitionSize = 1 shl 20)
        runBlocking { client(dev).flash("boot", img) }
        assertEquals(listOf(img.size.toLong()), dev.downloadSizes)
        assertEquals(listOf(false), dev.stagedWasSparse)
    }

    @Test
    fun testMissingMaxDownloadSizeFallsBackToDefaultAndSmallImagesStillGoRaw() {
        val img = image(blocks = 50, seed = 10)
        val dev = FakeBootloader(Long.MAX_VALUE, maxDownloadReply = null, partitionSize = 1 shl 20)
        runBlocking { client(dev).flash("boot", img) }
        assertEquals(listOf(false), dev.stagedWasSparse)
        assertContentEquals(expectedPartition(img, dev.partition.size), dev.partition)
    }

    // ---- sparse input -----------------------------------------------------------------------

    @Test
    fun testSparseImageLargerThanLimitIsResplitNotWrappedInRaw() {
        // sparse file: 4 MiB RAW chunk of random data at blocks 0..1023, then 1 MiB DONT_CARE gap, then FILL.
        val r = Random(12)
        val data = ByteArray(1024 * bs).also { r.nextBytes(it) }

        fun le(
            v: Long,
            n: Int,
        ): ByteArray = ByteArray(n) { (v ushr (8 * it)).toByte() }

        val body = ByteArrayOutputStream()
        body.write(le(0xCAC1, 2))
        body.write(le(0, 2))
        body.write(le(1024, 4))
        body.write(le(12L + data.size, 4))
        body.write(data)

        body.write(le(0xCAC3, 2))
        body.write(le(0, 2))
        body.write(le(256, 4))
        body.write(le(12, 4))

        body.write(le(0xCAC2, 2))
        body.write(le(0, 2))
        body.write(le(64, 4))
        body.write(le(16, 4))
        body.write(byteArrayOf(9, 8, 7, 6))

        val file = ByteArrayOutputStream()
        file.write(le(0xED26FF3AL, 4))
        file.write(le(1, 2))
        file.write(le(0, 2))
        file.write(le(28, 2))
        file.write(le(12, 2))
        file.write(le(bs.toLong(), 4))
        file.write(le(1024 + 256 + 64, 4))
        file.write(le(3, 4))
        file.write(le(0, 4))
        file.write(body.toByteArray())
        val sparse = file.toByteArray()

        val dev = FakeBootloader(0x100000, "0x100000", partitionSize = (1024 + 256 + 64) * bs)
        val f = File.createTempFile("sparse", ".img")
        f.writeBytes(sparse)
        f.deleteOnExit()

        runBlocking { client(dev).flash("system", f) }

        assertTrue(dev.downloadSizes.size >= 4)
        assertTrue(dev.downloadSizes.all { it <= 0x100000 })
        val expected = ByteArray((1024 + 256 + 64) * bs) { FakeBootloader.SENTINEL }
        System.arraycopy(data, 0, expected, 0, data.size)
        for (i in 0 until 64 * bs) expected[(1280 * bs) + i] = byteArrayOf(9, 8, 7, 6)[i % 4]
        assertContentEquals(expected, dev.partition) // gap blocks 1024..1279 must stay untouched
    }

    @Test
    fun testMalformedSparseHeaderGivesBadArgument() {
        val bad = ByteArray(2 shl 20)
        bad[0] = 0x3A
        bad[1] = 0xFF.toByte()
        bad[2] = 0x26
        bad[3] = 0xED.toByte()
        bad[4] = 9 // unsupported major version
        val dev = FakeBootloader(0x100000, "0x100000", partitionSize = 4 shl 20)
        val e =
            assertFailsWith<FastbootException.BadArgument> {
                runBlocking { client(dev).flash("system", bad) }
            }
        assertTrue(e.message!!.contains("Invalid sparse image"), e.message)
        assertTrue(dev.downloadSizes.isEmpty(), "nothing must be sent for an invalid image")
    }

    // ---- failure behaviour ------------------------------------------------------------------

    @Test
    fun testNonSeekableDescriptorLargerThanLimitIsRejectedWithAClearMessage() {
        val fifo = File.createTempFile("fifo", "").also { it.delete() }
        val mk = ProcessBuilder("mkfifo", fifo.path).start().waitFor()
        if (mk != 0) return // not on Linux; nothing to check
        fifo.deleteOnExit()
        val writer =
            Thread {
                FileOutputStream(fifo).use { it.write(ByteArray(10)) }
            }.also {
                it.isDaemon = true
                it.start()
            }
        val dev = FakeBootloader(0x1000, "0x1000", partitionSize = 1 shl 20)
        FileInputStream(fifo).use { fis ->
            val e =
                assertFailsWith<FastbootException.BadArgument> {
                    runBlocking { client(dev).flash("system", fis.fd, 1_000_000L) }
                }
            assertTrue(e.message!!.contains("not seekable"), e.message)
        }
        writer.join(2000)
        assertTrue(dev.downloadSizes.isEmpty())
    }

    @Test
    fun testLimitSmallerThanOneBlockOfSparseOverheadIsReportedNotLooped() {
        val dev = FakeBootloader(1000, "1000", partitionSize = 1 shl 20)
        val e =
            assertFailsWith<FastbootException.BadArgument> {
                runBlocking { client(dev).flash("system", ByteArray(100_000) { it.toByte() }) }
            }
        assertTrue(e.message!!.contains("too small"), e.message)
    }

    @Test
    fun testDeviceFailureOnAPieceAbortsTheRemainingPieces() {
        class FailingSecondFlash(val inner: FakeBootloader) : FastbootTransport by inner {
            var flashes = 0
            val queued = ArrayDeque<ByteArray>()

            override fun write(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ) {
                val cmd = if (length < 64) String(buffer, offset, length) else ""
                if (cmd.startsWith("flash:") && ++flashes == 2) {
                    queued.addLast("FAILwrite error".toByteArray())
                    return
                }
                inner.write(buffer, offset, length)
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                val q = queued.removeFirstOrNull() ?: return inner.read(buffer, offset, length)
                System.arraycopy(q, 0, buffer, offset, q.size)
                return q.size
            }
        }
        val img = image(blocks = 800, seed = 13, dense = true)
        val dev = FakeBootloader(0x100000, "0x100000", partitionSize = 4 shl 20)
        val wrapper = FailingSecondFlash(dev)
        val e =
            assertFailsWith<FastbootException.DeviceFail> {
                runBlocking { FastbootClient(FastbootConnection(wrapper)).flash("system", img) }
            }
        assertEquals("remote: 'write error'", e.message)
        assertEquals(2, dev.downloadSizes.size, "third piece must not be attempted after the second flash failed")
    }

    // ---- max-download-size parsing -----------------------------------------------------------

    @Test
    fun testParseDeviceSize() {
        assertEquals(536870912L, parseDeviceSize("0x20000000"))
        assertEquals(536870912L, parseDeviceSize("0X20000000"))
        assertEquals(268435456L, parseDeviceSize("268435456")) // decimal must not be read as hex
        assertEquals(268435456L, parseDeviceSize("  0x10000000 \r\n"))
        assertNull(parseDeviceSize(""))
        assertNull(parseDeviceSize("   "))
        assertNull(parseDeviceSize("0x"))
        assertNull(parseDeviceSize("banana"))
        assertNull(parseDeviceSize("0"))
        assertNull(parseDeviceSize("-5"))
        assertEquals(0x80000000L, parseDeviceSize("0x80000000")) // >= 2 GiB stays positive
    }

    @Test
    fun testGetMaxDownloadSizeReturnsNullWhenDeviceFailsTheQuery() {
        val dev = FakeBootloader(0, maxDownloadReply = null, partitionSize = 4096)
        assertNull(runBlocking { client(dev).getMaxDownloadSize() })
    }
}
