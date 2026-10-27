package com.memevault.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaloSyncManagerTest {
    @Test
    fun acceptsPngMimeAndFallbackExtensionOnlyForUnknownMime() {
        assertTrue(ZaloSyncManager.isPngDocument("sticker.bin", "image/png"))
        assertTrue(ZaloSyncManager.isPngDocument("sticker.PNG", "application/octet-stream"))
        assertFalse(ZaloSyncManager.isPngDocument("sticker.png", "image/jpeg"))
        assertFalse(ZaloSyncManager.isPngDocument("sticker.jpg", null))
        assertFalse(ZaloSyncManager.isPngDocument(".memevault-id.part", "image/png"))
    }

    @Test
    fun stableExportNameRoundTripsUuid() {
        val id = "01234567-89ab-cdef-0123-456789abcdef"
        val name = ZaloSyncManager.exportFileName(id)

        assertEquals("memevault-$id.png", name)
        assertEquals(id, ZaloSyncManager.managedAssetId(name))
        assertNull(ZaloSyncManager.managedAssetId("memevault-other.png"))
    }
}
