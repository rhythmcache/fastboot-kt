package io.github.rhythmcache.fastboot

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [FastbootClient] - verifies that the higher-level API
 * produces the correct wire commands and processes results properly.
 */
class FastbootClientTest {
    private fun buildClient(transport: MockTransport): FastbootClient {
        return FastbootClient(FastbootConnection(transport))
    }

    //
    //  getVar
    //

    @Test
    fun `getVar version`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("getvar:version")
            transport.enqueueRead("OKAY0.4")

            val client = buildClient(transport)
            val version = client.getVar("version")

            assertEquals("0.4", version)
            transport.assertAllConsumed()
        }

    @Test
    fun `getVar max-download-size`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("getvar:max-download-size")
            transport.enqueueRead("OKAY0x10000000")

            val client = buildClient(transport)
            assertEquals("0x10000000", client.getVar("max-download-size"))
            transport.assertAllConsumed()
        }

    //
    //  getAllVars
    //

    @Test
    fun `getAllVars parses INFO lines into map`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("getvar:all")
            transport.enqueueRead("INFOversion: 0.4")
            transport.enqueueRead("INFOproduct: walleye")
            transport.enqueueRead("INFOserialno: ABC123")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            val vars = client.getAllVars()

            assertEquals("0.4", vars["version"])
            assertEquals("walleye", vars["product"])
            assertEquals("ABC123", vars["serialno"])
            transport.assertAllConsumed()
        }

    //
    //  flash (in-memory)
    //

    @Test
    fun `flash sends download then flash command`() =
        runTest {
            val image = ByteArray(64) { it.toByte() }
            val transport = MockTransport()
            transport.expectWrite("getvar:max-download-size")
            transport.enqueueRead("OKAY0x10000000")
            // download phase: command → DATA ack → (image bytes are written) → OKAY
            transport.expectWrite("download:00000040") // 64 = 0x40
            transport.enqueueRead("DATA00000040")
            transport.enqueueRead("OKAY")
            // flash phase: command → OKAY
            // Don't use expectWrite here since the image data write happens
            // between the download command and the flash command.
            // We'll verify the flash command was sent by checking writtenChunks.
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            val result = client.flash("boot", image)

            assertEquals("", result.response)
            // Verify the wire traffic: [getvar:max-download-size] [download cmd] [image data] [flash cmd]
            val chunks = transport.writtenChunks
            assertEquals("getvar:max-download-size", chunks[0].toString(Charsets.UTF_8))
            // Second chunk = download command
            assertEquals("download:00000040", chunks[1].toString(Charsets.UTF_8))
            // Last chunk = flash command
            assertEquals("flash:boot", chunks.last().toString(Charsets.UTF_8))
            // Middle chunk(s) = image data
            val dataChunks = chunks.subList(2, chunks.size - 1)
            val sentData = dataChunks.fold(byteArrayOf()) { acc, b -> acc + b }
            assertTrue(image.contentEquals(sentData))
        }

    //
    //  erase
    //

    @Test
    fun `erase sends correct command`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("erase:userdata")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.erase("userdata")
            transport.assertAllConsumed()
        }

    //
    //  boot (no staging)
    //

    @Test
    fun `boot without image sends boot command`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("boot")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.boot()
            transport.assertAllConsumed()
        }

    //
    //  stage (in-memory)
    //

    @Test
    fun `stage sends download command and payload without trailing boot or flash`() =
        runTest {
            val payload = ByteArray(32) { (it + 1).toByte() }
            val transport = MockTransport()
            transport.expectWrite("download:00000020") // 32 = 0x20
            transport.enqueueRead("DATA00000020")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            val result = client.stage(payload)

            assertEquals("", result.response)

            val chunks = transport.writtenChunks
            assertEquals("download:00000020", chunks[0].toString(Charsets.UTF_8))
            val sentData = chunks.subList(1, chunks.size).fold(byteArrayOf()) { acc, b -> acc + b }
            assertTrue(payload.contentEquals(sentData))
            transport.assertAllConsumed()
        }

    //
    //  reboot variants
    //

    @Test
    fun `reboot sends reboot command`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("reboot")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.reboot()
            transport.assertAllConsumed()
        }

    @Test
    fun `reboot to bootloader`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("reboot-bootloader")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.reboot(FastbootClient.RebootTarget.BOOTLOADER)
            transport.assertAllConsumed()
        }

    @Test
    fun `reboot to recovery`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("reboot-recovery")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.reboot(FastbootClient.RebootTarget.RECOVERY)
            transport.assertAllConsumed()
        }

    @Test
    fun `reboot to fastboot`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("reboot-fastboot")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.reboot(FastbootClient.RebootTarget.FASTBOOT)
            transport.assertAllConsumed()
        }

    @Test
    fun `reboot to custom target`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("reboot-sideload")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.reboot("sideload")
            transport.assertAllConsumed()
        }

    //
    //  continue / shutdown
    //

    @Test
    fun `continueBoot sends continue`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("continue")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.continueBoot()
            transport.assertAllConsumed()
        }

    @Test
    fun `shutdown sends shutdown`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("shutdown")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.shutdown()
            transport.assertAllConsumed()
        }

    //
    //  setActive
    //

    @Test
    fun `setActive sends correct slot command`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("set_active:b")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.setActive("b")
            transport.assertAllConsumed()
        }

    //
    //  Logical partitions
    //

    @Test
    fun `createLogicalPartition sends correct command`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("create-logical-partition:my_part:1048576")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.createLogicalPartition("my_part", 1048576L)
            transport.assertAllConsumed()
        }

    @Test
    fun `deleteLogicalPartition sends correct command`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("delete-logical-partition:my_part")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.deleteLogicalPartition("my_part")
            transport.assertAllConsumed()
        }

    @Test
    fun `resizeLogicalPartition sends correct command`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("resize-logical-partition:my_part:2097152")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.resizeLogicalPartition("my_part", 2097152L)
            transport.assertAllConsumed()
        }

    //
    //  OEM / GSI
    //

    @Test
    fun `oem command uses space separator`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("oem unlock")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.oemCommand("unlock")
            transport.assertAllConsumed()
        }

    @Test
    fun `gsi command uses space separator`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("gsi disable")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.gsiCommand("disable")
            transport.assertAllConsumed()
        }

    //
    //  Snapshot update
    //

    @Test
    fun `snapshotUpdate cancel`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("snapshot-update:cancel")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.snapshotUpdate(FastbootClient.SnapshotCommand.Cancel)
            transport.assertAllConsumed()
        }

    @Test
    fun `snapshotUpdate merge`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("snapshot-update:merge")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.snapshotUpdate(FastbootClient.SnapshotCommand.Merge)
            transport.assertAllConsumed()
        }

    //
    //  updateSuper
    //

    @Test
    fun `updateSuper without wipe`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("update-super:super")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.updateSuper("super")
            transport.assertAllConsumed()
        }

    @Test
    fun `updateSuper with wipe`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("update-super:super:wipe")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.updateSuper("super", wipe = true)
            transport.assertAllConsumed()
        }

    //
    //  Flashing lock/unlock
    //

    @Test
    fun `flashing unlock sends correct command`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("flashing unlock")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.setLockMode(FastbootClient.LockMode.UNLOCK)
            transport.assertAllConsumed()
        }

    @Test
    fun `flashing lock sends correct command`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("flashing lock")
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            client.setLockMode(FastbootClient.LockMode.LOCK)
            transport.assertAllConsumed()
        }

    @Test
    fun `flashingGetUnlockAbility returns true when device responds 1`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("flashing get_unlock_ability")
            transport.enqueueRead("OKAY1")

            val client = buildClient(transport)
            assertTrue(client.flashingGetUnlockAbility())
            transport.assertAllConsumed()
        }

    //
    //  upload
    //

    @Test
    fun `upload returns device data`() =
        runTest {
            val expected = byteArrayOf(0x10, 0x20, 0x30, 0x40)
            val transport = MockTransport()
            transport.expectWrite("upload")
            transport.enqueueRead("DATA00000004")
            transport.enqueueRead(expected)
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            val buffer = java.io.ByteArrayOutputStream()
            client.upload(sink = { data, length -> buffer.write(data, 0, length) })

            assertTrue(expected.contentEquals(buffer.toByteArray()))
            transport.assertAllConsumed()
        }
    //
    //  fetch
    //

    @Test
    fun `fetch with offset and size`() =
        runTest {
            val expected = ByteArray(8) { 0xFF.toByte() }
            val transport = MockTransport()
            transport.expectWrite("fetch:boot:0x00001000:0x00000008")
            transport.enqueueRead("DATA00000008")
            transport.enqueueRead(expected)
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            val buffer = java.io.ByteArrayOutputStream()
            client.fetch("boot", sink = { data, length -> buffer.write(data, 0, length) }, offset = 0x1000L, size = 8L)

            assertTrue(expected.contentEquals(buffer.toByteArray()))
            transport.assertAllConsumed()
        }

    @Test
    fun `fetch without offset`() =
        runTest {
            val expected = ByteArray(4) { 0xAA.toByte() }
            val transport = MockTransport()
            transport.expectWrite("fetch:system")
            transport.enqueueRead("DATA00000004")
            transport.enqueueRead(expected)
            transport.enqueueRead("OKAY")

            val client = buildClient(transport)
            val buffer = java.io.ByteArrayOutputStream()
            client.fetch("system", sink = { data, length -> buffer.write(data, 0, length) })

            assertTrue(expected.contentEquals(buffer.toByteArray()))
            transport.assertAllConsumed()
        }

    //
    //  rawCommand escape hatch
    //

    @Test
    fun `rawCommand sends arbitrary string`() =
        runTest {
            val transport = MockTransport()
            transport.expectWrite("oem some-vendor-command arg1 arg2")
            transport.enqueueRead("OKAYresult")

            val client = buildClient(transport)
            val result = client.rawCommand("oem some-vendor-command arg1 arg2")

            assertEquals("result", result.response)
            transport.assertAllConsumed()
        }
}
