package io.github.rhythmcache.fastboot.sparse

import java.io.ByteArrayOutputStream
import java.util.BitSet
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for ImageAnalyzer + SparsePlanner. Every test checks the end result by applying the produced
 * pieces, in order, to a simulated partition with an independent decoder ([applySparse]) and comparing it
 * with what the image should look like.
 */
class SparseSplitTests {
    private val bs = 4096
    private val sentinel: Byte = 0x5A

    private fun u16(
        b: ByteArray,
        o: Int,
    ): Int {
        return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(
        b: ByteArray,
        o: Int,
    ): Long {
        return (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
    }

    // ---- independent decoder -------------------------------------------------------------

    /** Applies a sparse image to [partition] like a bootloader would. Returns total_blks from the header. */
    private fun applySparse(
        img: ByteArray,
        partition: ByteArray,
    ): Long {
        assertEquals(0xED26FF3AL, u32(img, 0), "magic")
        assertEquals(1, u16(img, 4))
        assertEquals(28, u16(img, 8))
        assertEquals(12, u16(img, 10))
        val blk = u32(img, 12).toInt()
        val totalBlks = u32(img, 16)
        val chunks = u32(img, 20)
        var pos = 28
        var block = 0L
        for (c in 0 until chunks) {
            val type = u16(img, pos)
            val blocks = u32(img, pos + 4)
            val total = u32(img, pos + 8)
            when (type) {
                0xCAC1 -> {
                    assertEquals(12 + blocks * blk, total, "raw chunk size")
                    System.arraycopy(img, pos + 12, partition, (block * blk).toInt(), (blocks * blk).toInt())
                }
                0xCAC2 -> {
                    assertEquals(16L, total, "fill chunk size")
                    for (i in 0 until blocks * blk) partition[(block * blk + i).toInt()] = img[pos + 12 + (i % 4).toInt()]
                }
                0xCAC3 -> assertEquals(12L, total, "dont-care chunk size")
                else -> fail("unexpected chunk type $type")
            }
            block += blocks
            pos += total.toInt()
        }
        assertEquals(img.size, pos, "chunk sizes must add up to the file size")
        assertEquals(totalBlks, block, "chunks must cover exactly total_blks")
        return totalBlks
    }

    private fun readPiece(
        piece: SparsePiece,
        src: RandomAccessSource,
        chunk: Int,
    ): ByteArray {
        val out = ByteArray(piece.size.toInt())
        var pos = 0
        val buf = ByteArray(chunk)
        while (pos < out.size) {
            val n = piece.read(src, pos.toLong(), buf, 0, minOf(chunk, out.size - pos))
            assertTrue(n > 0, "read made no progress at $pos")
            System.arraycopy(buf, 0, out, pos, n)
            pos += n
        }
        return out
    }

    private fun flashAll(
        pieces: List<SparsePiece>,
        src: RandomAccessSource,
        totalBlocks: Long,
        chunk: Int,
    ): ByteArray {
        val part = ByteArray((totalBlocks * bs).toInt()) { sentinel }
        for (p in pieces) assertEquals(totalBlocks, applySparse(readPiece(p, src, chunk), part))
        return part
    }

    /** Mixed image: random data, zero runs, repeated-word runs, optional unaligned tail. */
    private fun makeRaw(
        blocks: Int,
        tail: Int,
        seed: Long,
    ): ByteArray {
        val r = Random(seed)
        val out = ByteArray(blocks * bs + tail)
        var b = 0
        while (b < blocks) {
            val run = 1 + r.nextInt(40)
            val end = minOf(blocks, b + run)
            when (r.nextInt(3)) {
                0 -> {} // zeros
                1 -> {
                    val w = ByteArray(4)
                    r.nextBytes(w)
                    w[0] = 0x11
                    for (i in b * bs until end * bs) out[i] = w[i % 4]
                }
                else -> {
                    val chunk = ByteArray((end - b) * bs)
                    r.nextBytes(chunk)
                    System.arraycopy(chunk, 0, out, b * bs, chunk.size)
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

    private fun expectedPartition(
        raw: ByteArray,
        totalBlocks: Long,
    ): ByteArray {
        val p = ByteArray((totalBlocks * bs).toInt())
        System.arraycopy(raw, 0, p, 0, raw.size)
        return p
    }

    // ---- tests -----------------------------------------------------------------------------

    @Test
    fun testRawRoundTripAcrossLimitsAndReadSizes() {
        val raw = makeRaw(blocks = 300, tail = 1005, seed = 1)
        val src = ByteArraySource(raw)
        val layout = ImageAnalyzer.analyze(src)
        assertEquals(301L, layout.totalBlocks)
        assertTrue(!layout.inputWasSparse)
        val expected = expectedPartition(raw, layout.totalBlocks)
        val limits =
            longArrayOf(
                SparsePlanner.minimumLimit(bs),
                10_000,
                65_536,
                100_001,
                500_000,
                1_048_576,
                5_000_000,
            )
        for (limit in limits) {
            val pieces = SparsePlanner.plan(layout, limit)
            for (p in pieces) assertTrue(p.size <= limit, "piece ${p.size} > limit $limit")
            for (chunk in intArrayOf(1, 7, 4096, 65_536, 1 shl 20)) {
                assertContentEquals(expected, flashAll(pieces, src, layout.totalBlocks, chunk), "limit=$limit read=$chunk")
            }
        }
    }

    @Test
    fun testSmallestPossibleLimitHoldsOneBlockPerPiece() {
        val raw = ByteArray(bs * 5) { (it * 31 + it / 7).toByte() }
        val layout = ImageAnalyzer.analyze(ByteArraySource(raw))
        val pieces = SparsePlanner.plan(layout, SparsePlanner.minimumLimit(bs))
        assertEquals(5, pieces.size)
        assertContentEquals(raw, flashAll(pieces, ByteArraySource(raw), 5, 4096))
    }

    @Test
    fun testLimitBelowMinimumIsRejected() {
        val layout = ImageAnalyzer.analyze(ByteArraySource(ByteArray(bs * 2) { it.toByte() }))
        assertFailsWith<IllegalArgumentException> { SparsePlanner.plan(layout, SparsePlanner.minimumLimit(bs) - 1) }
    }

    @Test
    fun testLimitEqualToImageSizeStillRespectsLimit() {
        // A raw image exactly as large as the limit does not fit as a sparse piece (headers add overhead).
        val raw = ByteArray(bs * 64) { (it xor (it ushr 8)).toByte() }
        val layout = ImageAnalyzer.analyze(ByteArraySource(raw))
        val pieces = SparsePlanner.plan(layout, raw.size.toLong())
        assertEquals(2, pieces.size)
        for (p in pieces) assertTrue(p.size <= raw.size)
        assertContentEquals(raw, flashAll(pieces, ByteArraySource(raw), 64, 65_536))
    }

    @Test
    fun testAllZeroImageIsOneTinyPiece() {
        val raw = ByteArray(bs * 100_000 / 100) // 4096 * 1000
        val layout = ImageAnalyzer.analyze(ByteArraySource(raw))
        assertEquals(1, layout.extents.size)
        assertTrue(layout.extents[0] is FillExtent)
        val pieces = SparsePlanner.plan(layout, 1_000_000)
        assertEquals(1, pieces.size)
        assertTrue(pieces[0].size < 100, "fill-only image should be a few dozen bytes, was ${pieces[0].size}")
        assertContentEquals(raw, flashAll(pieces, ByteArraySource(raw), layout.totalBlocks, 4096))
    }

    @Test
    fun testCrcChunkIsSkippedAndGapsBecomeDontCare() {
        fun le(
            v: Long,
            n: Int,
        ): ByteArray = ByteArray(n) { (v ushr (8 * it)).toByte() }

        val body = ByteArrayOutputStream()
        var count = 0

        fun chunk(
            type: Int,
            blocks: Long,
            total: Long,
            payload: ByteArray = ByteArray(0),
        ) {
            body.write(le(type.toLong(), 2))
            body.write(le(0, 2))
            body.write(le(blocks, 4))
            body.write(le(total, 4))
            body.write(payload)
            count++
        }
        chunk(0xCAC3, 2, 12) // blocks 0..1 untouched
        chunk(0xCAC1, 1, 12 + bs.toLong(), ByteArray(bs) { 7 }) // block 2
        chunk(0xCAC4, 0, 16, byteArrayOf(9, 9, 9, 9)) // CRC32, covers no blocks
        chunk(0xCAC2, 3, 16, byteArrayOf(1, 2, 3, 4)) // blocks 3..5
        val file = ByteArrayOutputStream()
        file.write(le(0xED26FF3AL, 4))
        file.write(le(1, 2))
        file.write(le(0, 2))
        file.write(le(28, 2))
        file.write(le(12, 2))
        file.write(le(bs.toLong(), 4))
        file.write(le(6, 4))
        file.write(le(count.toLong(), 4))
        file.write(le(0, 4))
        file.write(body.toByteArray())
        val layout = ImageAnalyzer.analyze(ByteArraySource(file.toByteArray()))
        assertEquals(6L, layout.totalBlocks)
        assertEquals(2, layout.extents.size)
        assertEquals(2L, layout.extents[0].startBlock)
        assertEquals(3L, layout.extents[1].startBlock)
        assertEquals(3L, layout.extents[1].blockCount)
    }

    @Test
    fun testSparseInputResplitMatchesOriginal() {
        // Same layout as above, but without the CRC chunk so the reference decoder can apply the original too.
        val r = Random(11)
        val d1 = ByteArray(bs * 3).also { r.nextBytes(it) }
        val d2 = ByteArray(bs * 10).also { r.nextBytes(it) }

        fun le(
            v: Long,
            n: Int,
        ): ByteArray = ByteArray(n) { (v ushr (8 * it)).toByte() }

        val body = ByteArrayOutputStream()
        var count = 0

        fun chunk(
            type: Int,
            blocks: Long,
            total: Long,
            payload: ByteArray = ByteArray(0),
        ) {
            body.write(le(type.toLong(), 2))
            body.write(le(0, 2))
            body.write(le(blocks, 4))
            body.write(le(total, 4))
            body.write(payload)
            count++
        }
        chunk(0xCAC1, 3, 12 + d1.size.toLong(), d1)
        chunk(0xCAC3, 5, 12)
        chunk(0xCAC2, 4, 16, byteArrayOf(1, 2, 3, 4))
        chunk(0xCAC1, 10, 12 + d2.size.toLong(), d2)
        chunk(0xCAC3, 3, 12)
        val file = ByteArrayOutputStream()
        file.write(le(0xED26FF3AL, 4))
        file.write(le(1, 2))
        file.write(le(0, 2))
        file.write(le(28, 2))
        file.write(le(12, 2))
        file.write(le(bs.toLong(), 4))
        file.write(le(25, 4))
        file.write(le(count.toLong(), 4))
        file.write(le(0, 4))
        file.write(body.toByteArray())
        val sparse = file.toByteArray()

        val src = ByteArraySource(sparse)
        val layout = ImageAnalyzer.analyze(src)
        assertTrue(layout.inputWasSparse)
        assertEquals(25L, layout.totalBlocks)

        val expected = ByteArray(25 * bs) { sentinel }
        applySparse(sparse, expected)
        for (limit in longArrayOf(SparsePlanner.minimumLimit(bs), 20_000, 33_000, 1_000_000)) {
            val pieces = SparsePlanner.plan(layout, limit)
            for (p in pieces) assertTrue(p.size <= limit, "piece ${p.size} > $limit")
            // DONT_CARE blocks (3..7 and 22..24) must keep the sentinel: no piece may touch them.
            assertContentEquals(expected, flashAll(pieces, src, 25, 4096), "limit=$limit")
        }
    }

    @Test
    fun testMalformedSparseIsRejectedWithClearErrors() {
        val ok = ByteArray(64)
        ok[0] = 0x3A
        ok[1] = 0xFF.toByte()
        ok[2] = 0x26
        ok[3] = 0xED.toByte()
        ok[4] = 2 // major version 2
        assertFailsWith<SparseFormatException> { ImageAnalyzer.analyze(ByteArraySource(ok)) }

        ok[4] = 1
        ok[8] = 28
        ok[10] = 12
        ok[12] = 0
        ok[13] = 0x10 // block size 4096
        ok[16] = 1 // total_blks
        ok[20] = 1 // one chunk declared
        ok[28] = 0xC1.toByte()
        ok[29] = 0xCA.toByte()
        ok[32] = 1
        ok[36] = 0xFF.toByte()
        ok[37] = 0x1F // claims 8 KiB, file is 64 bytes
        assertFailsWith<SparseFormatException> { ImageAnalyzer.analyze(ByteArraySource(ok)) }
    }

    @Test
    fun testPieceReadsAreStatelessAndRandomAccess() {
        val raw = makeRaw(blocks = 120, tail = 77, seed = 3)
        val src = ByteArraySource(raw)
        val layout = ImageAnalyzer.analyze(src)
        val piece = SparsePlanner.plan(layout, 80_000)[1]
        val whole = readPiece(piece, src, 1 shl 20)
        val r = Random(5)
        repeat(200) {
            val off = r.nextInt(whole.size)
            val len = 1 + r.nextInt(minOf(9000, whole.size - off))
            val buf = ByteArray(len)
            var got = 0
            while (got < len) got += piece.read(src, (off + got).toLong(), buf, got, len - got)
            assertContentEquals(whole.copyOfRange(off, off + len), buf, "off=$off len=$len")
        }
        // restart after a "failed" transfer: reading from 0 again yields identical bytes
        assertContentEquals(whole, readPiece(piece, src, 12_345))
    }

    /** 3.2 GB virtual image: checks >2 GiB arithmetic, piece sizes and block coverage without allocating it. */
    @Test
    fun testThreePointTwoGigabyteImageIsSplitWithinLimitAndCoversEveryBlockOnce() {
        val size = 3_435_973_836L // the size from the bug report
        val src =
            object : RandomAccessSource {
                override val size = size

                override fun read(
                    position: Long,
                    dest: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (position >= size) return -1
                    val n = minOf(length.toLong(), size - position).toInt()
                    java.util.Arrays.fill(dest, offset, offset + n, 0)
                    // word 0 of every block = block index + 1 => never uniform, never FILL
                    val end = position + n
                    var b = (position + bs - 1) / bs * bs
                    while (b < end) {
                        val v = b / bs + 1
                        for (i in 0 until 4) {
                            val at = b + i
                            if (at in position until end) dest[offset + (at - position).toInt()] = (v ushr (8 * i)).toByte()
                        }
                        b += bs
                    }
                    return n
                }
            }
        val layout = ImageAnalyzer.analyze(src)
        assertEquals((size + bs - 1) / bs, layout.totalBlocks)
        val limit = 512L * 1024 * 1024
        val pieces = SparsePlanner.plan(layout, limit)
        assertEquals(7, pieces.size) // 3.2 GB / 512 MiB = 6.4 => 7
        var covered = 0L
        val seen = BitSet(layout.totalBlocks.toInt())
        for ((i, p) in pieces.withIndex()) {
            assertTrue(p.size <= limit, "piece $i is ${p.size}")
            assertTrue(i == pieces.size - 1 || p.size > limit - 3 * bs, "piece $i should be nearly full")
            // walk chunk headers without reading payloads
            val h = ByteArray(28)
            p.read(src, 0, h, 0, 28)
            val declaredBlocks = u32(h, 16)
            assertEquals(layout.totalBlocks, declaredBlocks)
            var pos = 28L
            val ch = ByteArray(12)
            var block = 0L
            while (pos < p.size) {
                p.read(src, pos, ch, 0, 12)
                val type = u16(ch, 0)
                val blocks = u32(ch, 4)
                val total = u32(ch, 8)
                if (type == 0xCAC1) {
                    for (k in block until block + blocks) {
                        assertTrue(!seen.get(k.toInt()), "block $k written twice")
                        seen.set(k.toInt())
                    }
                    covered += blocks
                }
                block += blocks
                pos += total
            }
            assertEquals(layout.totalBlocks, block, "piece $i must declare the full image")
            assertEquals(p.size, pos)
        }
        assertEquals(layout.totalBlocks, covered)
        // spot check payload bytes of a piece in the middle against the source
        val mid = pieces[3]
        val probe = ByteArray(4096)
        mid.read(src, 28 + 12 + 12 + 4096L * 1000, probe, 0, 4096)
        val expectedProbe = ByteArray(4096)
        // locate which source block that is: piece 3 starts after 3 full pieces; verify via word 0 instead
        val word0 = u32(probe, 0)
        src.read((word0 - 1) * bs, expectedProbe, 0, 4096)
        assertContentEquals(expectedProbe, probe)
    }
}
