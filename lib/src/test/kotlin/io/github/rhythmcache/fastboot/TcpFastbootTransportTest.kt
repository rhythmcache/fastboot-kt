package io.github.rhythmcache.fastboot

import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests ported from and inspired by the official AOSP tcp_test.cpp.
 *
 * Verifies:
 * - Handshake protocol version negotiation (FB01, newer version FB99, failure cases).
 * - Message framing with 8-byte big-endian 64-bit length header.
 * - Fragmented stream reads across TCP packet boundaries.
 * - Large 1 MiB data transfers.
 * - End-to-end [FastbootClient] commands over TCP.
 * - Transport closing semantics.
 */
class TcpFastbootTransportTest {

    private inline fun withMockServer(
        block: (server: ServerSocket, port: Int) -> Unit,
    ) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        try {
            block(server, server.localPort)
        } finally {
            server.close()
        }
    }

    @Test
    fun `handshake succeeds with exact FB01`() {
        withMockServer { server, port ->
            val serverThread = thread {
                val sock = server.accept()
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                val handshake = ByteArray(4)
                var r = 0
                while (r < 4) r += input.read(handshake, r, 4 - r)
                assertEquals("FB01", handshake.decodeToString())

                output.write("FB01".toByteArray(Charsets.US_ASCII))
                output.flush()
                sock.close()
            }

            val transport = TcpFastbootTransport.connect("127.0.0.1", port)
            transport.close()
            serverThread.join(2000)
        }
    }

    @Test
    fun `handshake succeeds with newer protocol version FB99`() {
        withMockServer { server, port ->
            val serverThread = thread {
                val sock = server.accept()
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                val handshake = ByteArray(4)
                var r = 0
                while (r < 4) r += input.read(handshake, r, 4 - r)
                assertEquals("FB01", handshake.decodeToString())

                output.write("FB99".toByteArray(Charsets.US_ASCII))
                output.flush()
                sock.close()
            }

            val transport = TcpFastbootTransport.connect("127.0.0.1", port)
            transport.close()
            serverThread.join(2000)
        }
    }

    @Test
    fun `handshake fails on unrecognized prefix`() {
        withMockServer { server, port ->
            val serverThread = thread {
                val sock = server.accept()
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                val handshake = ByteArray(4)
                var r = 0
                while (r < 4) r += input.read(handshake, r, 4 - r)

                output.write("XX01".toByteArray(Charsets.US_ASCII))
                output.flush()
                sock.close()
            }

            val ex = assertFailsWith<FastbootException.Protocol> {
                TcpFastbootTransport.connect("127.0.0.1", port)
            }
            assertTrue(ex.message!!.contains("Unrecognized initialization message"))
            serverThread.join(2000)
        }
    }

    @Test
    fun `handshake fails on unknown version below host version`() {
        withMockServer { server, port ->
            val serverThread = thread {
                val sock = server.accept()
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                val handshake = ByteArray(4)
                var r = 0
                while (r < 4) r += input.read(handshake, r, 4 - r)

                output.write("FB00".toByteArray(Charsets.US_ASCII))
                output.flush()
                sock.close()
            }

            val ex = assertFailsWith<FastbootException.Protocol> {
                TcpFastbootTransport.connect("127.0.0.1", port)
            }
            assertTrue(ex.message!!.contains("Unknown TCP protocol version 00 (host version 01)"))
            serverThread.join(2000)
        }
    }

    @Test
    fun `handshake fails on server EOF`() {
        withMockServer { server, port ->
            val serverThread = thread {
                val sock = server.accept()
                sock.close()
            }

            val ex = assertFailsWith<FastbootException.Io> {
                TcpFastbootTransport.connect("127.0.0.1", port)
            }
            assertTrue(ex.message!!.contains("No initialization message received"))
            serverThread.join(2000)
        }
    }

    @Test
    fun `write sends 8-byte length prefix and payload`() {
        withMockServer { server, port ->
            val serverThread = thread {
                val sock = server.accept()
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                // Handshake
                val hs = ByteArray(4)
                var r = 0
                while (r < 4) r += input.read(hs, r, 4 - r)
                output.write("FB01".toByteArray(Charsets.US_ASCII))
                output.flush()

                // Read 8-byte length header
                val header = ByteArray(8)
                r = 0
                while (r < 8) r += input.read(header, r, 8 - r)
                val length = TcpFastbootTransport.extractLength(header)
                assertEquals(3L, length)

                // Read payload "foo"
                val payload = ByteArray(3)
                r = 0
                while (r < 3) r += input.read(payload, r, 3 - r)
                assertEquals("foo", payload.decodeToString())
                sock.close()
            }

            val transport = TcpFastbootTransport.connect("127.0.0.1", port)
            val data = "foo".toByteArray(Charsets.UTF_8)
            transport.write(data, 0, data.size)
            transport.close()
            serverThread.join(2000)
        }
    }

    @Test
    fun `read extracts 8-byte length prefix and payload`() {
        withMockServer { server, port ->
            val serverThread = thread {
                val sock = server.accept()
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                // Handshake
                val hs = ByteArray(4)
                var r = 0
                while (r < 4) r += input.read(hs, r, 4 - r)
                output.write("FB01".toByteArray(Charsets.US_ASCII))
                output.flush()

                // Send 8-byte length (7) + "OKAY0.4"
                val msg = "OKAY0.4".toByteArray(Charsets.US_ASCII)
                val header = ByteArray(8)
                TcpFastbootTransport.encodeLength(msg.size.toLong(), header)
                output.write(header)
                output.write(msg)
                output.flush()
                sock.close()
            }

            val transport = TcpFastbootTransport.connect("127.0.0.1", port)
            val buf = ByteArray(256)
            val n = transport.read(buf, 0, buf.size)
            assertEquals(7, n)
            assertEquals("OKAY0.4", buf.decodeToString(0, n))
            transport.close()
            serverThread.join(2000)
        }
    }

    @Test
    fun `read handles fragmented incoming chunks`() {
        withMockServer { server, port ->
            val serverThread = thread {
                val sock = server.accept()
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                // Handshake
                val hs = ByteArray(4)
                var r = 0
                while (r < 4) r += input.read(hs, r, 4 - r)
                output.write("FB01".toByteArray(Charsets.US_ASCII))
                output.flush()

                // Send header in two 4-byte pieces
                output.write(byteArrayOf(0, 0, 0, 0))
                output.flush()
                Thread.sleep(10)
                output.write(byteArrayOf(0, 0, 0, 3))
                output.flush()
                Thread.sleep(10)

                // Send payload in 1-byte pieces: "f", "o", "o"
                output.write("f".toByteArray(Charsets.US_ASCII))
                output.flush()
                Thread.sleep(10)
                output.write("o".toByteArray(Charsets.US_ASCII))
                output.flush()
                Thread.sleep(10)
                output.write("o".toByteArray(Charsets.US_ASCII))
                output.flush()
                sock.close()
            }

            val transport = TcpFastbootTransport.connect("127.0.0.1", port)
            val buf = ByteArray(16)
            val n = transport.read(buf, 0, buf.size)
            assertEquals(3, n)
            assertEquals("foo", buf.decodeToString(0, n))
            transport.close()
            serverThread.join(2000)
        }
    }

    @Test
    fun `large payload write and read 1 MiB`() {
        val size = 1024 * 1024 // 1 MiB
        val testData = ByteArray(size) { (it % 251).toByte() }

        withMockServer { server, port ->
            val serverThread = thread {
                val sock = server.accept()
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                // Handshake
                val hs = ByteArray(4)
                var r = 0
                while (r < 4) r += input.read(hs, r, 4 - r)
                output.write("FB01".toByteArray(Charsets.US_ASCII))
                output.flush()

                // Read 8-byte length
                val header = ByteArray(8)
                r = 0
                while (r < 8) r += input.read(header, r, 8 - r)
                val length = TcpFastbootTransport.extractLength(header)
                assertEquals(size.toLong(), length)

                // Read 1 MiB payload
                val received = ByteArray(size)
                r = 0
                while (r < size) r += input.read(received, r, size - r)
                assertContentEquals(testData, received)

                // Echo back with header
                output.write(header)
                output.write(received)
                output.flush()
                sock.close()
            }

            val transport = TcpFastbootTransport.connect("127.0.0.1", port)
            transport.write(testData, 0, testData.size)

            val echoBuf = ByteArray(size)
            var totalRead = 0
            while (totalRead < size) {
                val n = transport.read(echoBuf, totalRead, size - totalRead)
                assertTrue(n > 0)
                totalRead += n
            }
            assertEquals(size, totalRead)
            assertContentEquals(testData, echoBuf)

            transport.close()
            serverThread.join(5000)
        }
    }

    @Test
    fun `end-to-end fastboot client over TCP`() = runTest {
        withMockServer { server, port ->
            val serverThread = thread {
                val sock = server.accept()
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                // Handshake
                val hs = ByteArray(4)
                var r = 0
                while (r < 4) r += input.read(hs, r, 4 - r)
                output.write("FB01".toByteArray(Charsets.US_ASCII))
                output.flush()

                fun reply(msg: String) {
                    val bytes = msg.toByteArray(Charsets.US_ASCII)
                    val hdr = ByteArray(8)
                    TcpFastbootTransport.encodeLength(bytes.size.toLong(), hdr)
                    output.write(hdr)
                    output.write(bytes)
                    output.flush()
                }

                fun readCommand(): String {
                    val hdr = ByteArray(8)
                    var readLen = 0
                    while (readLen < 8) readLen += input.read(hdr, readLen, 8 - readLen)
                    val len = TcpFastbootTransport.extractLength(hdr).toInt()
                    val payload = ByteArray(len)
                    readLen = 0
                    while (readLen < len) readLen += input.read(payload, readLen, len - readLen)
                    return payload.decodeToString()
                }

                assertEquals("getvar:version", readCommand())
                reply("OKAY0.4")

                assertEquals("getvar:all", readCommand())
                reply("INFOversion: 0.4")
                reply("INFOproduct: emulator")
                reply("OKAY")

                sock.close()
            }

            val client = FastbootClient.connectTcp("127.0.0.1", port)
            assertEquals("0.4", client.getVar("version"))

            val allVars = client.getAllVars()
            assertEquals("0.4", allVars["version"])
            assertEquals("emulator", allVars["product"])

            client.close()
            serverThread.join(2000)
        }
    }

    @Test
    fun `transport close behavior`() {
        withMockServer { server, port ->
            val serverThread = thread {
                val sock = server.accept()
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                val hs = ByteArray(4)
                var r = 0
                while (r < 4) r += input.read(hs, r, 4 - r)
                output.write("FB01".toByteArray(Charsets.US_ASCII))
                output.flush()
                sock.close()
            }

            val transport = TcpFastbootTransport.connect("127.0.0.1", port)
            transport.close()

            val buf = ByteArray(16)
            assertEquals(-1, transport.read(buf, 0, buf.size))

            assertFailsWith<FastbootException.Io> {
                transport.write(buf, 0, 4)
            }

            serverThread.join(2000)
        }
    }

    @Test
    fun `connect failure on closed port wraps in FastbootException Io`() {
        // Pick an unused port by briefly opening and closing a server socket
        val unusedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        val ex = assertFailsWith<FastbootException.Io> {
            TcpFastbootTransport.connect("127.0.0.1", unusedPort, connectTimeoutMs = 500)
        }
        assertTrue(ex.message!!.contains("Failed to connect"))
    }

    @Test
    fun `read timeout closes transport and throws FastbootException Timeout`() {
        withMockServer { server, port ->
            val serverThread = thread {
                val sock = server.accept()
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                // Handshake
                val hs = ByteArray(4)
                var r = 0
                while (r < 4) r += input.read(hs, r, 4 - r)
                output.write("FB01".toByteArray(Charsets.US_ASCII))
                output.flush()

                // Sleep without sending anything
                Thread.sleep(1000)
                sock.close()
            }

            val transport = TcpFastbootTransport.connect("127.0.0.1", port, socketTimeoutMs = 100)
            val buf = ByteArray(16)

            assertFailsWith<FastbootException.Timeout> {
                transport.read(buf, 0, buf.size)
            }

            // Transport should now be closed
            assertEquals(-1, transport.read(buf, 0, buf.size))

            serverThread.join(2000)
        }
    }
}
