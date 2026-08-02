package io.github.rhythmcache.fastboot

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests ported from / inspired by the official AOSP fastboot_driver_test.cpp.
 *
 * Each test sets up a [MockTransport], scripts the expected wire traffic,
 * runs the code under test through [FastbootConnection] (the protocol engine),
 * and verifies both the result and that all scripted traffic was consumed.
 */
class FastbootConnectionTest {
    //
    //  GetVar - mirrors the official DriverTest.GetVar
    //

    @Test
    fun `getVar sends correct command and parses OKAY payload`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("getvar:version")
            transport.enqueueRead("OKAY0.4")

            val conn = FastbootConnection(transport)
            val result = conn.rawCommand("getvar:version")

            assertEquals("0.4", result.response)
            assertTrue(result.info.isEmpty())
            transport.assertAllConsumed()
        }

    @Test
    fun `getVar with empty OKAY payload`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("getvar:serialno")
            transport.enqueueRead("OKAY")

            val conn = FastbootConnection(transport)
            val result = conn.rawCommand("getvar:serialno")

            assertEquals("", result.response)
            transport.assertAllConsumed()
        }

    //
    //  INFO messages - mirrors the official DriverTest.InfoMessage
    //

    @Test
    fun `INFO messages are collected and passed to callback`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("oem dmesg")
            transport.enqueueRead("INFOthis is an info line")
            transport.enqueueRead("OKAY")

            val conn = FastbootConnection(transport)
            val callbackLines = mutableListOf<String>()
            val result = conn.rawCommand("oem dmesg", onInfo = { callbackLines.add(it) })

            assertEquals(1, result.info.size)
            assertEquals("this is an info line", result.info[0])
            assertEquals(listOf("this is an info line"), callbackLines)
            transport.assertAllConsumed()
        }

    @Test
    fun `multiple INFO messages before OKAY`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("oem long-op")
            transport.enqueueRead("INFOline one")
            transport.enqueueRead("INFOline two")
            transport.enqueueRead("INFOline three")
            transport.enqueueRead("OKAY")

            val conn = FastbootConnection(transport)
            val result = conn.rawCommand("oem long-op")

            assertEquals(3, result.info.size)
            assertEquals("line one", result.info[0])
            assertEquals("line two", result.info[1])
            assertEquals("line three", result.info[2])
            transport.assertAllConsumed()
        }

    //
    //  TEXT messages - mirrors the official DriverTest.TextMessage
    //

    @Test
    fun `TEXT messages are passed to onText callback`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("oem trusty runtest trusty.hwaes.bench")
            transport.enqueueRead("TEXTthis is a text line")
            transport.enqueueRead("TEXT, albeit very long and split over multiple TEXT messages.")
            transport.enqueueRead("TEXT Indeed we can do that now with a TEXT message whenever we feel like it.")
            transport.enqueueRead("TEXT Isn't that truly super cool?")
            transport.enqueueRead("OKAY")

            val conn = FastbootConnection(transport)
            val textAccumulator = StringBuilder()
            conn.rawCommand(
                "oem trusty runtest trusty.hwaes.bench",
                onText = { textAccumulator.append(it) },
            )

            assertEquals(
                "this is a text line" +
                    ", albeit very long and split over multiple TEXT messages." +
                    " Indeed we can do that now with a TEXT message whenever we feel like it." +
                    " Isn't that truly super cool?",
                textAccumulator.toString(),
            )
            transport.assertAllConsumed()
        }

    //
    //  FAIL responses
    //

    @Test
    fun `FAIL response throws DeviceFail with message`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("erase:system")
            transport.enqueueRead("FAILpartition not found")

            val conn = FastbootConnection(transport)
            val ex =
                assertFailsWith<FastbootException.DeviceFail> {
                    conn.rawCommand("erase:system")
                }
            assertTrue(ex.message!!.contains("partition not found"))
            transport.assertAllConsumed()
        }

    @Test
    fun `FAIL with empty message`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("flash:boot")
            transport.enqueueRead("FAIL")

            val conn = FastbootConnection(transport)
            val ex =
                assertFailsWith<FastbootException.DeviceFail> {
                    conn.rawCommand("flash:boot")
                }
            assertTrue(ex.message!!.contains("remote:"))
            transport.assertAllConsumed()
        }

    //
    //  Unknown response - protocol error
    //

    @Test
    fun `unknown status code throws Protocol exception`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("getvar:version")
            transport.enqueueRead("WHAT???")

            val conn = FastbootConnection(transport)
            val ex =
                assertFailsWith<FastbootException.Protocol> {
                    conn.rawCommand("getvar:version")
                }
            assertTrue(ex.message!!.contains("unknown status code"))
            transport.assertAllConsumed()
        }

    //
    //  DATA in rawCommand - protocol error (our bug fix)
    //

    @Test
    fun `DATA response in rawCommand throws Protocol exception`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("upload")
            transport.enqueueRead("DATA00001000")

            val conn = FastbootConnection(transport)
            val ex =
                assertFailsWith<FastbootException.Protocol> {
                    conn.rawCommand("upload")
                }
            assertTrue(ex.message!!.contains("Unexpected DATA"))
            transport.assertAllConsumed()
        }

    //
    //  Command size validation
    //

    @Test
    fun `oversized command is rejected`() =
        runTest {
            val transport = MockTransport()
            val conn = FastbootConnection(transport)

            val oversized = "x".repeat(FastbootConst.MAX_COMMAND_SIZE + 1)
            assertFailsWith<FastbootException.BadArgument> {
                conn.rawCommand(oversized)
            }
        }

    @Test
    fun `max-size command is accepted`() =
        runTest {
            val transport = MockTransport()
            val cmd = "x".repeat(FastbootConst.MAX_COMMAND_SIZE)
            transport.expectWrite(cmd)
            transport.enqueueRead("OKAY")

            val conn = FastbootConnection(transport)
            conn.rawCommand(cmd) // should not throw
            transport.assertAllConsumed()
        }

    //
    //  Download protocol - full handshake
    //

    @Test
    fun `download sends correct command, streams data, and waits for OKAY`() =
        runTest {
            val payload = ByteArray(256) { it.toByte() }
            val transport = MockTransport()
            // download command: "download:00000100" (256 = 0x100)
            transport.expectWrite("download:00000100")
            // device responds with DATA + same size
            transport.enqueueRead("DATA00000100")
            // after data is sent, device responds OKAY
            transport.enqueueRead("OKAYdone")

            val conn = FastbootConnection(transport)
            val progress = mutableListOf<Progress>()
            val result =
                conn.executeDownload(
                    size = 256,
                    source = { offset, dest, destOffset, length ->
                        System.arraycopy(payload, offset.toInt(), dest, destOffset, length)
                        length
                    },
                    chunkSize = 1024,
                    onProgress = { progress.add(it) },
                )

            assertEquals("done", result.response)
            // Verify the payload was actually written
            // writtenChunks[0] is the command, writtenChunks[1] is the data
            val dataChunks = transport.writtenChunks.drop(1) // skip command
            val sentData = dataChunks.fold(byteArrayOf()) { acc, b -> acc + b }
            assertTrue(payload.contentEquals(sentData))
            // progress reported
            assertTrue(progress.isNotEmpty())
            assertEquals(256L, progress.last().current)
            assertEquals(256L, progress.last().total)
            transport.assertAllConsumed()
        }

    @Test
    fun `download with large chunked data`() =
        runTest {
            // 3000 bytes, chunk size 1024 → 3 writes
            val size = 3000
            val payload = ByteArray(size) { (it % 256).toByte() }
            val hexSize = "%08x".format(size)
            val transport = MockTransport()
            transport.expectWrite("download:$hexSize")
            transport.enqueueRead("DATA$hexSize")
            transport.enqueueRead("OKAY")

            val conn = FastbootConnection(transport)
            conn.executeDownload(
                size = size.toLong(),
                source = { offset, dest, destOffset, length ->
                    System.arraycopy(payload, offset.toInt(), dest, destOffset, length)
                    length
                },
                chunkSize = 1024,
            )

            val dataChunks = transport.writtenChunks.drop(1)
            val sentData = dataChunks.fold(byteArrayOf()) { acc, b -> acc + b }
            assertTrue(payload.contentEquals(sentData))
            assertEquals(3, dataChunks.size) // 1024 + 1024 + 952
            transport.assertAllConsumed()
        }

    @Test
    fun `download handles null-byte padded DATA size response`() =
        runTest {
            val payload = ByteArray(16) { 0xAA.toByte() }
            val transport = MockTransport()
            transport.expectWrite("download:00000010")
            // Real USB devices send 64-byte responses padded with null bytes
            val paddedDataResp = "DATA00000010".toByteArray(Charsets.UTF_8) + ByteArray(52) { 0x00 }
            transport.enqueueRead(paddedDataResp)
            transport.enqueueRead("OKAY")

            val conn = FastbootConnection(transport)
            conn.executeDownload(
                size = 16,
                source = { offset, dest, destOffset, length ->
                    System.arraycopy(payload, offset.toInt(), dest, destOffset, length)
                    length
                },
            )

            transport.assertAllConsumed()
        }

    @Test
    fun `download FAIL response throws DeviceFail`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("download:00000100")
            transport.enqueueRead("FAILnot enough space")

            val conn = FastbootConnection(transport)
            val ex =
                assertFailsWith<FastbootException.DeviceFail> {
                    conn.executeDownload(
                        size = 256,
                        source = { _, dest, destOffset, length -> length },
                    )
                }
            assertTrue(ex.message!!.contains("not enough space"))
            transport.assertAllConsumed()
        }

    @Test
    fun `download rejects zero size`() =
        runTest {
            val transport = MockTransport()
            val conn = FastbootConnection(transport)

            assertFailsWith<FastbootException.BadArgument> {
                conn.executeDownload(
                    size = 0,
                    source = { _, _, _, length -> length },
                )
            }
        }

    @Test
    fun `download rejects negative size`() =
        runTest {
            val transport = MockTransport()
            val conn = FastbootConnection(transport)

            assertFailsWith<FastbootException.BadArgument> {
                conn.executeDownload(
                    size = -1,
                    source = { _, _, _, length -> length },
                )
            }
        }

    //
    //  Upload / Fetch protocol - full handshake
    //

    @Test
    fun `upload receives data and terminal OKAY`() =
        runTest {
            val expected = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
            val transport = MockTransport()
            transport.expectWrite("upload")
            transport.enqueueRead("DATA00000008")
            transport.enqueueRead(expected)
            transport.enqueueRead("OKAY")

            val conn = FastbootConnection(transport)
            val buffer = java.io.ByteArrayOutputStream()
            val result = conn.executeUpload("upload", sink = { data, length -> buffer.write(data, 0, length) })

            assertTrue(expected.contentEquals(buffer.toByteArray()))
            assertEquals("", result.response)
            transport.assertAllConsumed()
        }

    @Test
    fun `fetch sends correct command format`() =
        runTest {
            val expected = ByteArray(16) { 0xAB.toByte() }
            val transport = MockTransport()
            transport.expectWrite("fetch:system:0x00000000:0x00000010")
            transport.enqueueRead("DATA00000010")
            transport.enqueueRead(expected)
            transport.enqueueRead("OKAY")

            val conn = FastbootConnection(transport)
            val cmd = Commands.fetch("system", 0L, 16L)
            val buffer = java.io.ByteArrayOutputStream()
            conn.executeUpload(cmd, sink = { data, length -> buffer.write(data, 0, length) })

            assertTrue(expected.contentEquals(buffer.toByteArray()))
            transport.assertAllConsumed()
        }

    @Test
    fun `upload FAIL throws DeviceFail`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("upload")
            transport.enqueueRead("FAILnot supported")

            val conn = FastbootConnection(transport)
            assertFailsWith<FastbootException.DeviceFail> {
                conn.executeUpload("upload", sink = { _, _ -> })
            }
            transport.assertAllConsumed()
        }

    @Test
    fun `upload with zero size throws Protocol exception`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("upload")
            transport.enqueueRead("DATA00000000")

            val conn = FastbootConnection(transport)
            assertFailsWith<FastbootException.Protocol> {
                conn.executeUpload("upload", sink = { _, _ -> })
            }
            transport.assertAllConsumed()
        }
    //
    //  INFO during download terminal phase
    //

    @Test
    fun `download terminal phase collects INFO lines`() =
        runTest {
            val payload = ByteArray(4) { 0x00 }
            val transport = MockTransport()
            transport.expectWrite("download:00000004")
            transport.enqueueRead("DATA00000004")
            // After data is sent, device sends INFO before OKAY
            transport.enqueueRead("INFOflashing...")
            transport.enqueueRead("INFOverifying...")
            transport.enqueueRead("OKAYdone")

            val conn = FastbootConnection(transport)
            val result =
                conn.executeDownload(
                    size = 4,
                    source = { offset, dest, destOffset, length ->
                        System.arraycopy(payload, offset.toInt(), dest, destOffset, length)
                        length
                    },
                )

            assertEquals("done", result.response)
            assertEquals(2, result.info.size)
            assertEquals("flashing...", result.info[0])
            assertEquals("verifying...", result.info[1])
            transport.assertAllConsumed()
        }
}
