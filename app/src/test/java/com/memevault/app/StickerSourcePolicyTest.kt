package com.memevault.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerSourcePolicyTest {
    @Test
    fun deleteOnlyForAFreshImportNothingElseUses() {
        assertTrue(StickerSourcePolicy.canDelete(importedNow = true, otherDerived = 0))
        assertFalse(StickerSourcePolicy.canDelete(importedNow = false, otherDerived = 0))
        assertFalse(StickerSourcePolicy.canDelete(importedNow = true, otherDerived = 1))
    }

    @Test
    fun rememberedDeleteFallsBackToKeepWhereItIsNotAllowed() {
        assertEquals(SourceAfterSave.DELETE, StickerSourcePolicy.effective(SourceAfterSave.DELETE, canDelete = true))
        assertEquals(SourceAfterSave.KEEP, StickerSourcePolicy.effective(SourceAfterSave.DELETE, canDelete = false))
        assertEquals(SourceAfterSave.ARCHIVE, StickerSourcePolicy.effective(SourceAfterSave.ARCHIVE, canDelete = false))
    }

    @Test
    fun postSaveCheckAllowsOnlyTheNewSticker() {
        assertTrue(StickerSourcePolicy.canDeleteAfterSave(importedNow = true, derivedAfterSave = 1))
        assertFalse(StickerSourcePolicy.canDeleteAfterSave(importedNow = true, derivedAfterSave = 2))
        assertFalse(StickerSourcePolicy.canDeleteAfterSave(importedNow = false, derivedAfterSave = 1))
    }
}
