package io.github.rhythmcache.fastboot

/**
 * A scriptable mock transport for testing — mirrors the official AOSP
 * MockTransport / CopyData / RawData pattern from mock_transport.h.
 *
 * Usage:
 *   1. Call [expectWrite] to enqueue an expected write (asserted at write-time).
 *   2. Call [enqueuRead] to enqueue a response that will be returned on [read].
 *   3. Run the code under test.
 *   4. Call [assertAllConsumed] to verify everything was used.
 */
class MockTransport : FastbootTransport {
    //  write side
    private val expectedWrites = ArrayDeque<ByteArray>()

    /** Enqueue an expected write payload (exact byte match). */
    fun expectWrite(data: String) {
        expectedWrites.addLast(data.toByteArray(Charsets.UTF_8))
    }

    fun expectWrite(data: ByteArray) {
        expectedWrites.addLast(data)
    }

    //  read side
    private val readQueue = ArrayDeque<ByteArray>()

    /** Enqueue a response that the device "sends". */
    fun enqueueRead(data: String) {
        readQueue.addLast(data.toByteArray(Charsets.UTF_8))
    }

    fun enqueueRead(data: ByteArray) {
        readQueue.addLast(data)
    }

    //  actual write log (for flexible assertions)
    private val _writtenChunks = mutableListOf<ByteArray>()

    /** Every raw byte array that was written, in order. */
    val writtenChunks: List<ByteArray> get() = _writtenChunks

    /** All written bytes concatenated — handy for data-phase assertions. */
    val allWrittenBytes: ByteArray get() = _writtenChunks.fold(byteArrayOf()) { acc, b -> acc + b }

    //  Transport implementation

    override fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val payload = buffer.copyOfRange(offset, offset + length)
        _writtenChunks.add(payload)

        if (expectedWrites.isNotEmpty()) {
            val expected = expectedWrites.removeFirst()
            if (!expected.contentEquals(payload)) {
                throw AssertionError(
                    "MockTransport write mismatch:\n" +
                        "  expected: ${expected.toString(Charsets.UTF_8)}\n" +
                        "  actual:   ${payload.toString(Charsets.UTF_8)}",
                )
            }
        }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (readQueue.isEmpty()) {
            throw AssertionError("MockTransport: read() called but no data was enqueued")
        }
        val data = readQueue.removeFirst()
        val toCopy = minOf(data.size, length)
        System.arraycopy(data, 0, buffer, offset, toCopy)
        return toCopy
    }

    override fun reset() {}

    override fun waitForDisconnect() {}

    override fun close() {}

    //  verification

    fun assertAllConsumed() {
        check(expectedWrites.isEmpty()) {
            "MockTransport: ${expectedWrites.size} expected write(s) were never consumed"
        }
        check(readQueue.isEmpty()) {
            "MockTransport: ${readQueue.size} enqueued read(s) were never consumed"
        }
    }
}
