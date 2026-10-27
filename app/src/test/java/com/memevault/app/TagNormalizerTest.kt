package com.memevault.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagNormalizerTest {
    @Test
    fun normalizesUnicodeHashWhitespaceAndCase() {
        val tag = TagNormalizer.normalize("  #\u00A0 Mèo\t CƯNG  ")

        assertEquals("mèo cưng", tag?.key)
        assertEquals("Mèo CƯNG", tag?.displayName)
    }

    @Test
    fun deDuplicatesAndCapsTagList() {
        val tags = TagNormalizer.normalizeAll(
            listOf("Dog", " dog ", "#DOG", "Cat"),
        )

        assertEquals(listOf("Dog", "Cat"), tags.map { it.displayName })
    }

    @Test
    fun rejectsEmptyAndOverlongTags() {
        assertNull(TagNormalizer.normalize("   "))
        assertNull(TagNormalizer.normalize("a".repeat(TagNormalizer.MAX_TAG_LENGTH + 1)))
        assertTrue(TagNormalizer.normalize("a".repeat(TagNormalizer.MAX_TAG_LENGTH)) != null)
    }
}
