package io.github.rhythmcache.fastboot.sparse

import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Niche tests targeting silent data corruption risks:
 * 1. Odd unaligned file tails (1, 2, 3, 511, 4095 bytes) zero-padded to 4KB boundary without overwriting adjacent flash.
 * 2. Torture test with highly fragmented alternating RAW and FILL extents across small irregular chunk limits.
 * 3. Pre-sparse large DONT_CARE gaps: verifying unwritten blocks retain sentinel bytes and are never touched.
 * 4. Strict wire-format conformance against AOSP libsparse sparse_format.h constraints.
 * 5. Irregular stream read sizes (1-byte, 3-byte, 4095-byte, etc.) ensuring stateless read integrity.
 * 6. Off-by-one boundary splitting where wire size touches limit - 1, limit, limit + 1.
 */
class DataCorruptionTests {
    private val bs = 4096
    private val sentinel: Byte = 0xA5.toByte()

    /** Reference decoder simulating a bootloader's block device write logic. */
    private fun applySparseToPartition(
        img: ByteArray,
        partition: ByteArray,
    ): Long {
        fun u16(o: Int) = (img[o].toInt() and 0xFF) or ((img[o + 1].toInt() and 0xFF) shl 8)

        fun u32(o: Int) = (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)

        assertEquals(0xED26FF3AL, u32(0), "magic must match SPARSE_HEADER_MAGIC")
        assertEquals(1, u16(4), "major version")
        assertEquals(28, u16(8), "file_hdr_sz")
        assertEquals(12, u16(10), "chunk_hdr_sz")
        val blk = u32(12).toInt()
        val totalBlks = u32(16)
        val chunks = u32(20)

        var pos = 28
        var block = 0L
        for (c in 0 until chunks) {
            val type = u16(pos)
            val blocks = u32(pos + 4)
            val total = u32(pos + 8)

            when (type) {
                SparseFormat.CHUNK_RAW -> {
                    assertEquals(12 + blocks * blk, total, "raw chunk total_sz")
                    val offset = (block * blk).toInt()
                    val len = (blocks * blk).toInt()
                    System.arraycopy(img, pos + 12, partition, offset, len)
                }
                SparseFormat.CHUNK_FILL -> {
                    assertEquals(16L, total, "fill chunk total_sz")
                    val fillVal = img.copyOfRange(pos + 12, pos + 16)
                    for (i in 0 until blocks * blk) {
                        partition[(block * blk + i).toInt()] = fillVal[(i % 4).toInt()]
                    }
                }
                SparseFormat.CHUNK_DONT_CARE -> {
                    assertEquals(12L, total, "dont-care chunk total_sz")
                    // Bootloader lseeks past without touching blocks
                }
                else -> fail("Unexpected chunk type 0x${type.toString(16)}")
            }
            block += blocks
            pos += total.toInt()
        }
        assertEquals(img.size, pos, "Sum of chunk total_sz must equal wire image size")
        assertEquals(totalBlks, block, "Sum of chunk block counts must equal total_blks")
        return totalBlks
    }

    private fun readAllBytes(
        piece: SparsePiece,
        src: RandomAccessSource,
        readStep: Int,
    ): ByteArray {
        val out = ByteArray(piece.size.toInt())
        var pos = 0
        val buf = ByteArray(readStep)
        while (pos < out.size) {
            val n = piece.read(src, pos.toLong(), buf, 0, minOf(readStep, out.size - pos))
            assertTrue(n > 0, "SparsePiece.read made no progress at offset $pos")
            System.arraycopy(buf, 0, out, pos, n)
            pos += n
        }
        return out
    }

    // ----------------------------------------------------------------------------------------
    // 1. Unaligned tail tests
    // ----------------------------------------------------------------------------------------

    @Test
    fun testUnalignedTailLengthsZeroPaddedWithoutAdjacentFlashCorruption() {
        val tailSizes = intArrayOf(1, 2, 3, 7, 511, 1024, 2048, 4095)
        for (tail in tailSizes) {
            val blocks = 10
            val raw = ByteArray(blocks * bs + tail) { ((it * 17) xor (it ushr 3)).toByte() }
            val src = ByteArraySource(raw)
            val layout = ImageAnalyzer.analyze(src)

            assertEquals((blocks + 1).toLong(), layout.totalBlocks)

            // Split across small limits to force piece splits around the tail block
            for (limit in longArrayOf(SparsePlanner.minimumLimit(bs), 16_384, 65_536)) {
                val pieces = SparsePlanner.plan(layout, limit)
                val partition = ByteArray((layout.totalBlocks * bs).toInt() + bs) { sentinel }

                for (p in pieces) {
                    val bytes = readAllBytes(p, src, 4096)
                    applySparseToPartition(bytes, partition)
                }

                // Verify the payload bytes match exactly
                for (i in 0 until raw.size) {
                    assertEquals(raw[i], partition[i], "Mismatch at byte $i for tail $tail, limit $limit")
                }

                // Verify tail padding is strictly 0x00
                for (i in raw.size until (layout.totalBlocks * bs).toInt()) {
                    assertEquals(0.toByte(), partition[i], "Padding byte $i must be zero for tail $tail")
                }

                // Verify flash beyond totalBlocks is untouched (sentinel preserved)
                for (i in (layout.totalBlocks * bs).toInt() until partition.size) {
                    assertEquals(sentinel, partition[i], "Block device corruption beyond partition at byte $i")
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // 2. Highly fragmented alternating extents torture test
    // ----------------------------------------------------------------------------------------

    @Test
    fun testHighlyFragmentedAlternatingRawAndFillExtents() {
        val totalBlocks = 200
        val raw = ByteArray(totalBlocks * bs)
        val r = Random(42)

        // Alternate: block 0 = RAW, block 1 = FILL(0), block 2 = RAW, block 3 = FILL(0x55AA55AA)...
        for (b in 0 until totalBlocks) {
            when (b % 4) {
                0, 2 -> {
                    // RAW random data (never uniform)
                    val chunk = ByteArray(bs)
                    r.nextBytes(chunk)
                    chunk[0] = 0x01
                    chunk[1] = 0x02
                    System.arraycopy(chunk, 0, raw, b * bs, bs)
                }
                1 -> {
                    // FILL zeros
                    java.util.Arrays.fill(raw, b * bs, (b + 1) * bs, 0.toByte())
                }
                3 -> {
                    // FILL 0x55AA55AA
                    val pattern = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0xAA.toByte(), 0x55.toByte())
                    for (i in 0 until bs) raw[b * bs + i] = pattern[i % 4]
                }
            }
        }

        val src = ByteArraySource(raw)
        val layout = ImageAnalyzer.analyze(src)
        assertTrue(layout.extents.size > 100, "Expected hundreds of fragmented extents, got ${layout.extents.size}")

        // Plan with several tight limits
        for (limit in longArrayOf(16_384, 25_000, 32_768, 65_536)) {
            val pieces = SparsePlanner.plan(layout, limit)
            for (p in pieces) {
                assertTrue(p.size <= limit, "Piece size ${p.size} exceeded limit $limit")
            }

            val partition = ByteArray(totalBlocks * bs) { sentinel }
            for (p in pieces) {
                applySparseToPartition(readAllBytes(p, src, 1024), partition)
            }

            assertContentEquals(raw, partition, "Torture test failed for limit $limit")
        }
    }

    // ----------------------------------------------------------------------------------------
    // 3. Pre-sparse huge DONT_CARE gap preservation
    // ----------------------------------------------------------------------------------------

    @Test
    fun testPreSparseHugeDontCareGapNeverModifiedOnDevice() {
        val r = Random(99)
        val data1 = ByteArray(bs * 2).also { r.nextBytes(it) }
        val data2 = ByteArray(bs * 2).also { r.nextBytes(it) }

        fun le(
            v: Long,
            n: Int,
        ): ByteArray = ByteArray(n) { (v ushr (8 * it)).toByte() }

        val body = ByteArrayOutputStream()
        var chunkCount = 0

        fun addChunk(
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
            chunkCount++
        }

        // Chunk 1: 2 blocks of RAW data at block 0..1
        addChunk(SparseFormat.CHUNK_RAW, 2, 12 + data1.size.toLong(), data1)

        // Chunk 2: 1,000 blocks of DONT_CARE gap (approx 4 MB hole)
        val gapBlocks = 1_000L
        addChunk(SparseFormat.CHUNK_DONT_CARE, gapBlocks, 12)

        // Chunk 3: 2 blocks of RAW data at block 1,002..1,003
        addChunk(SparseFormat.CHUNK_RAW, 2, 12 + data2.size.toLong(), data2)

        val totalBlocksDeclared = 2 + gapBlocks + 2
        val fileStream = ByteArrayOutputStream()
        fileStream.write(le(0xED26FF3AL, 4))
        fileStream.write(le(1, 2))
        fileStream.write(le(0, 2))
        fileStream.write(le(28, 2))
        fileStream.write(le(12, 2))
        fileStream.write(le(bs.toLong(), 4))
        fileStream.write(le(totalBlocksDeclared, 4))
        fileStream.write(le(chunkCount.toLong(), 4))
        fileStream.write(le(0, 4))
        fileStream.write(body.toByteArray())

        val sparseImg = fileStream.toByteArray()
        val src = ByteArraySource(sparseImg)
        val layout = ImageAnalyzer.analyze(src)
        assertTrue(layout.inputWasSparse)
        assertEquals(totalBlocksDeclared, layout.totalBlocks)

        // Resplit into 12 KiB pieces (each 2-block RAW chunk is ~8 KiB)
        val limit = 12_000L
        val pieces = SparsePlanner.plan(layout, limit)
        assertTrue(pieces.size >= 2, "Expected multiple pieces, got ${pieces.size}")

        // Partition is filled with sentinel to detect unwanted writes
        val partition = ByteArray((totalBlocksDeclared * bs).toInt()) { sentinel }
        for (p in pieces) {
            applySparseToPartition(readAllBytes(p, src, 4096), partition)
        }

        // Data 1 at 0..8191
        for (i in 0 until data1.size) {
            assertEquals(data1[i], partition[i], "Mismatch in data1 at byte $i")
        }

        // Data 2 at (2 + 50000) * 4096 ..
        val data2Offset = (2 + gapBlocks).toInt() * bs
        for (i in 0 until data2.size) {
            assertEquals(data2[i], partition[data2Offset + i], "Mismatch in data2 at byte $i")
        }

        // The entire 50,000 blocks gap MUST REMAIN SENTINEL
        for (b in 2 until (2 + gapBlocks).toInt()) {
            val byteStart = b * bs
            if (partition[byteStart] != sentinel) {
                fail("DONT_CARE block $b was corrupted! First byte is 0x${partition[byteStart].toString(16)}")
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // 4. Strict wire format conformance against AOSP libsparse constraints
    // ----------------------------------------------------------------------------------------

    @Test
    fun testWireFormatConformsStrictlyToAospLibsparse() {
        val raw = ByteArray(bs * 50) { (it * 37).toByte() }
        val src = ByteArraySource(raw)
        val layout = ImageAnalyzer.analyze(src)
        val limit = 65_536L
        val pieces = SparsePlanner.plan(layout, limit)

        for ((idx, piece) in pieces.withIndex()) {
            val bytes = readAllBytes(piece, src, 4096)
            assertEquals(piece.size, bytes.size.toLong())
            assertTrue(bytes.size <= limit, "Piece $idx size ${bytes.size} exceeds limit $limit")

            // Header checks
            fun u16(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)

            fun u32(o: Int) = (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)

            assertEquals(0xED26FF3AL, u32(0), "magic in piece $idx")
            assertEquals(1, u16(4), "major_version in piece $idx")
            assertEquals(0, u16(6), "minor_version in piece $idx")
            assertEquals(28, u16(8), "file_hdr_sz in piece $idx")
            assertEquals(12, u16(10), "chunk_hdr_sz in piece $idx")
            assertEquals(bs, u32(12).toInt(), "blk_sz in piece $idx")
            assertEquals(layout.totalBlocks, u32(16), "total_blks declared in piece $idx")

            val totalChunks = u32(20)
            var pos = 28
            var blocksSum = 0L

            for (c in 0 until totalChunks) {
                val type = u16(pos)
                val blocks = u32(pos + 4)
                val total = u32(pos + 8)

                assertTrue(
                    type in intArrayOf(SparseFormat.CHUNK_RAW, SparseFormat.CHUNK_FILL, SparseFormat.CHUNK_DONT_CARE),
                    "Unknown chunk type 0x${type.toString(16)} in piece $idx chunk $c",
                )
                when (type) {
                    SparseFormat.CHUNK_RAW -> assertEquals(12 + blocks * bs, total)
                    SparseFormat.CHUNK_FILL -> assertEquals(16L, total)
                    SparseFormat.CHUNK_DONT_CARE -> assertEquals(12L, total)
                }

                blocksSum += blocks
                pos += total.toInt()
            }

            assertEquals(bytes.size, pos, "Sum of total_sz plus 28 must equal wire length in piece $idx")
            assertEquals(layout.totalBlocks, blocksSum, "Sum of chunk_sz must equal total_blks in piece $idx")
        }
    }

    // ----------------------------------------------------------------------------------------
    // 5. Irregular stream read sizes (stateless read verification)
    // ----------------------------------------------------------------------------------------

    @Test
    fun testPathologicalIrregularReadStepsProduceIdenticalWireBytes() {
        val raw = ByteArray(bs * 20 + 333) { (it xor 0x7F).toByte() }
        val src = ByteArraySource(raw)
        val layout = ImageAnalyzer.analyze(src)
        val piece = SparsePlanner.plan(layout, 32_768)[0]

        val baseline = readAllBytes(piece, src, piece.size.toInt())

        val steps = intArrayOf(1, 2, 3, 7, 13, 127, 4095, 4096, 4097, 8192)
        for (step in steps) {
            val stream = readAllBytes(piece, src, step)
            assertContentEquals(baseline, stream, "Mismatch when reading with step size $step")
        }
    }

    // ----------------------------------------------------------------------------------------
    // 6. Off-by-one boundary splitting
    // ----------------------------------------------------------------------------------------

    @Test
    fun testExactBoundarySplittingNeverExceedsLimitByEvenOneByte() {
        val overhead = SparseFormat.FILE_HEADER_SIZE + 2 * SparseFormat.CHUNK_HEADER_SIZE // 28 + 24 = 52
        val limit = overhead + 2 * bs // room for exactly 2 blocks of RAW data + trailing DONT_CARE

        fun nonUniform(blocks: Int) = ByteArray(blocks * bs) { (it % 251).toByte() }

        // 1 block of data fits easily in 1 piece
        val layout1 = ImageAnalyzer.analyze(ByteArraySource(nonUniform(1)))
        val pieces1 = SparsePlanner.plan(layout1, limit.toLong())
        assertEquals(1, pieces1.size)
        assertTrue(pieces1[0].size <= limit)

        // 2 blocks of data fits in exactly 1 piece
        val layout2 = ImageAnalyzer.analyze(ByteArraySource(nonUniform(2)))
        val pieces2 = SparsePlanner.plan(layout2, limit.toLong())
        assertEquals(1, pieces2.size)
        assertTrue(pieces2[0].size <= limit)

        // 3 blocks of data MUST split into 2 pieces without any piece exceeding limit
        val layout3 = ImageAnalyzer.analyze(ByteArraySource(nonUniform(3)))
        val pieces3 = SparsePlanner.plan(layout3, limit.toLong())
        assertEquals(2, pieces3.size)
        for (p in pieces3) {
            assertTrue(p.size <= limit, "Piece size ${p.size} exceeded boundary limit $limit")
        }
    }
}
