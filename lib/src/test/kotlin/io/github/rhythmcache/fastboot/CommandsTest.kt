package io.github.rhythmcache.fastboot

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [Commands] - verifies that every command formatter produces
 * wire strings that match the official AOSP constants.h definitions.
 */
class CommandsTest {
    @Test
    fun `getVar formats correctly`() {
        assertEquals("getvar:version", Commands.getVar("version"))
        assertEquals("getvar:max-download-size", Commands.getVar("max-download-size"))
        assertEquals("getvar:all", Commands.getVar("all"))
    }

    @Test
    fun `download formats 8-digit hex`() {
        assertEquals("download:00000000", Commands.download(0))
        assertEquals("download:00000100", Commands.download(256))
        assertEquals("download:00001000", Commands.download(4096))
        assertEquals("download:ffffffff", Commands.download(0xFFFFFFFFL))
    }

    @Test
    fun `flash formats correctly`() {
        assertEquals("flash:boot", Commands.flash("boot"))
        assertEquals("flash:system", Commands.flash("system"))
    }

    @Test
    fun `erase formats correctly`() {
        assertEquals("erase:userdata", Commands.erase("userdata"))
    }

    @Test
    fun `setActive formats correctly`() {
        assertEquals("set_active:a", Commands.setActive("a"))
        assertEquals("set_active:b", Commands.setActive("b"))
    }

    @Test
    fun `createLogicalPartition formats correctly`() {
        assertEquals(
            "create-logical-partition:my_part:1048576",
            Commands.createLogicalPartition("my_part", 1048576L),
        )
    }

    @Test
    fun `deleteLogicalPartition formats correctly`() {
        assertEquals(
            "delete-logical-partition:my_part",
            Commands.deleteLogicalPartition("my_part"),
        )
    }

    @Test
    fun `resizeLogicalPartition formats correctly`() {
        assertEquals(
            "resize-logical-partition:my_part:2097152",
            Commands.resizeLogicalPartition("my_part", 2097152L),
        )
    }

    @Test
    fun `updateSuper without wipe`() {
        assertEquals("update-super:super", Commands.updateSuper("super", false))
    }

    @Test
    fun `updateSuper with wipe`() {
        assertEquals("update-super:super:wipe", Commands.updateSuper("super", true))
    }

    @Test
    fun `snapshotUpdate formats correctly`() {
        assertEquals("snapshot-update:cancel", Commands.snapshotUpdate("cancel"))
        assertEquals("snapshot-update:merge", Commands.snapshotUpdate("merge"))
    }

    @Test
    fun `oem uses space not colon`() {
        assertEquals("oem unlock", Commands.oem("unlock"))
        assertEquals("oem my-command arg1", Commands.oem("my-command arg1"))
    }

    @Test
    fun `gsi uses space not colon`() {
        assertEquals("gsi disable", Commands.gsi("disable"))
    }

    @Test
    fun `rebootTo formats correctly`() {
        assertEquals("reboot-bootloader", Commands.rebootTo("bootloader"))
        assertEquals("reboot-recovery", Commands.rebootTo("recovery"))
        assertEquals("reboot-fastboot", Commands.rebootTo("fastboot"))
    }

    @Test
    fun `fetch without offset`() {
        assertEquals("fetch:system", Commands.fetch("system", null, null))
    }

    @Test
    fun `fetch with offset only`() {
        assertEquals("fetch:system:0x00001000", Commands.fetch("system", 0x1000L, null))
    }

    @Test
    fun `fetch with offset and size`() {
        assertEquals(
            "fetch:system:0x00001000:0x00000100",
            Commands.fetch("system", 0x1000L, 0x100L),
        )
    }

    @Test
    fun `constant commands match official strings`() {
        assertEquals("boot", Commands.BOOT)
        assertEquals("continue", Commands.CONTINUE)
        assertEquals("reboot", Commands.REBOOT)
        assertEquals("shutdown", Commands.SHUTDOWN)
        assertEquals("upload", Commands.UPLOAD)
    }

    @Test
    fun `flashing commands match official format`() {
        assertEquals("flashing unlock", Commands.FLASHING_UNLOCK)
        assertEquals("flashing lock", Commands.FLASHING_LOCK)
        assertEquals("flashing unlock_critical", Commands.FLASHING_UNLOCK_CRITICAL)
        assertEquals("flashing lock_critical", Commands.FLASHING_LOCK_CRITICAL)
        assertEquals("flashing get_unlock_ability", Commands.FLASHING_GET_UNLOCK_ABILITY)
    }
}
