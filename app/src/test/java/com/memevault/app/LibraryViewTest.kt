package com.memevault.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryViewTest {
    private fun asset(
        id: String,
        type: AssetType = AssetType.MEME,
        name: String = "$id.jpg",
        favorite: Boolean = false,
        createdAt: Long = 0L,
        lastUsedAt: Long? = null,
        useCount: Int = 0,
        tags: List<String> = emptyList(),
        archived: Boolean = false,
    ) = Asset(
        id = id,
        originalPath = "/tmp/$id",
        mimeType = if (type == AssetType.GIF) "image/gif" else "image/jpeg",
        displayName = name,
        type = type,
        hash = id,
        width = 10,
        height = 10,
        durationMs = 0L,
        favorite = favorite,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        useCount = useCount,
        tags = tags,
        archived = archived,
    )

    @Test
    fun archivedSourcesOnlyShowUnderTheSourcesFilter() {
        val library = listOf(
            asset("meme", tags = listOf("dog")),
            asset("source", archived = true, favorite = true, tags = listOf("dog", "cat")),
            asset("sticker", type = AssetType.STICKER),
        )
        AssetFilter.values().filter { it != AssetFilter.SOURCES }.forEach { filter ->
            val ids = LibraryView.apply(library, filter, TagScope.Any, LibrarySort.ADDED).map { it.id }
            assertEquals("filter $filter", false, "source" in ids)
        }
        assertEquals(
            listOf("source"),
            LibraryView.apply(library, AssetFilter.SOURCES, TagScope.Any, LibrarySort.ADDED).map { it.id },
        )
        val counts = LibraryView.countsByFilter(library)
        assertEquals(2, counts[AssetFilter.ALL])
        assertEquals(0, counts[AssetFilter.FAVORITES])
        assertEquals(1, counts[AssetFilter.SOURCES])
        // Tag collections describe the visible library, so an archived source adds nothing.
        assertEquals(
            listOf(LibraryView.tagKey("dog") to 1),
            LibraryView.topTags(library, limit = 10).map { it.key to it.count },
        )
    }

    @Test
    fun addedSortKeepsNewImportsFirstEvenWhenOthersWereUsedRecently() {
        val old = asset("old", createdAt = 1, lastUsedAt = 1_000)
        val new = asset("new", createdAt = 500)
        val sorted = LibraryView.apply(listOf(old, new), AssetFilter.ALL, TagScope.Any, LibrarySort.ADDED)
        assertEquals(listOf("new", "old"), sorted.map { it.id })
    }

    @Test
    fun recentlyUsedSortPutsNeverUsedLast() {
        val never = asset("never", createdAt = 900)
        val used = asset("used", createdAt = 1, lastUsedAt = 10)
        val sorted = LibraryView.apply(listOf(never, used), AssetFilter.ALL, TagScope.Any, LibrarySort.RECENTLY_USED)
        assertEquals(listOf("used", "never"), sorted.map { it.id })
    }

    @Test
    fun leastUsedSortSurfacesCleanupCandidates() {
        val sorted = LibraryView.apply(
            listOf(asset("a", useCount = 5), asset("b", useCount = 0), asset("c", useCount = 2)),
            AssetFilter.ALL,
            TagScope.Any,
            LibrarySort.LEAST_USED,
        )
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun nameSortIsCaseAndAccentAwareForVietnamese() {
        val sorted = LibraryView.apply(
            listOf(asset("1", name = "Ốc.jpg"), asset("2", name = "an.jpg"), asset("3", name = "Bò.jpg")),
            AssetFilter.ALL,
            TagScope.Any,
            LibrarySort.NAME,
        )
        assertEquals(listOf("an.jpg", "Bò.jpg", "Ốc.jpg"), sorted.map { it.displayName })
    }

    @Test
    fun typeTagAndExclusionFiltersCombine() {
        val all = listOf(
            asset("gif-cat", AssetType.GIF, tags = listOf("Cat")),
            asset("meme-cat", AssetType.MEME, tags = listOf("cat")),
            asset("gif-dog", AssetType.GIF, tags = listOf("dog")),
            asset("gif-cat-deleted", AssetType.GIF, tags = listOf("cat")),
        )
        val scope = TagScope.Tag("cat", "cat")
        val visible = LibraryView.apply(all, AssetFilter.GIFS, scope, LibrarySort.ADDED, setOf("gif-cat-deleted"))
        assertEquals(listOf("gif-cat"), visible.map { it.id })
    }

    @Test
    fun untaggedScopeFindsAssetsThatNeedOrganizing() {
        val all = listOf(asset("tagged", tags = listOf("a")), asset("bare"))
        val visible = LibraryView.apply(all, AssetFilter.ALL, TagScope.Untagged, LibrarySort.ADDED)
        assertEquals(listOf("bare"), visible.map { it.id })
    }

    @Test
    fun countsIgnorePendingDeletion() {
        val all = listOf(
            asset("a", AssetType.STICKER, favorite = true),
            asset("b", AssetType.VIDEO),
            asset("c", AssetType.GIF),
        )
        val counts = LibraryView.countsByFilter(all, setOf("c"))
        assertEquals(2, counts[AssetFilter.ALL])
        assertEquals(1, counts[AssetFilter.FAVORITES])
        assertEquals(1, counts[AssetFilter.STICKERS])
        assertEquals(1, counts[AssetFilter.VIDEOS])
        assertEquals(0, counts[AssetFilter.GIFS])
    }

    @Test
    fun topTagsMergeCanonicalKeysAndRankByUsage() {
        val all = listOf(
            asset("1", tags = listOf("Mèo", "reaction")),
            asset("2", tags = listOf("mèo")),
            asset("3", tags = listOf("#mèo", "work")),
        )
        val top = LibraryView.topTags(all, limit = 2)
        assertEquals(2, top.size)
        assertEquals(3, top.first().count)
        assertEquals(LibraryView.tagKey("mèo"), top.first().key)
    }

    @Test
    fun densityCyclesThroughSupportedColumns() {
        assertEquals(3, LibraryPreferences.nextColumns(2))
        assertEquals(4, LibraryPreferences.nextColumns(3))
        assertEquals(2, LibraryPreferences.nextColumns(4))
        assertEquals(3, LibraryPreferences.nextColumns(7))
    }

    @Test
    fun spanKeepsPreferenceOnPhonesAndWidensInLandscape() {
        assertEquals(2, LibraryPreferences.spanFor(2, 383))
        assertEquals(3, LibraryPreferences.spanFor(3, 383))
        assertEquals(4, LibraryPreferences.spanFor(4, 383))
        assertEquals(5, LibraryPreferences.spanFor(2, 880))
        assertEquals(9, LibraryPreferences.spanFor(4, 880))
    }
}

class DisplayNamePolicyTest {
    @Test
    fun renameKeepsOriginalExtension() {
        assertEquals("con mèo.gif", DisplayNamePolicy.rename("cat.gif", "con mèo"))
    }

    @Test
    fun renameDoesNotDuplicateTypedExtension() {
        assertEquals("party.png", DisplayNamePolicy.rename("x.png", "party.PNG"))
    }

    @Test
    fun renameRejectsBlankNames() {
        assertNull(DisplayNamePolicy.rename("cat.gif", "   "))
    }

    @Test
    fun sanitizeStripsPathSeparatorsAndControlCharacters() {
        assertEquals("_.._x_y.png", DisplayNamePolicy.sanitize("/../x\\y.png"))
        assertEquals("a_b", DisplayNamePolicy.sanitize("a\u0000b"))
    }

    @Test
    fun sanitizeCapsLength() {
        assertEquals(DisplayNamePolicy.MAX_LENGTH, DisplayNamePolicy.sanitize("a".repeat(500)).length)
    }

    @Test
    fun baseNameAndExtensionSplitOnlyRealExtensions() {
        assertEquals("meme", DisplayNamePolicy.baseName("meme.jpeg"))
        assertEquals(".jpeg", DisplayNamePolicy.extensionOf("meme.jpeg"))
        assertEquals("", DisplayNamePolicy.extensionOf("no extension"))
        assertEquals("v1.2 final", DisplayNamePolicy.baseName("v1.2 final"))
    }
}

class ByteFormatTest {
    @Test
    fun formatsBinaryUnits() {
        assertEquals("512 B", ByteFormat.format(512))
        assertEquals("1.0 KB", ByteFormat.format(1024))
        assertEquals("1.5 MB", ByteFormat.format(1024L * 1024L * 3 / 2))
        assertEquals("120 MB", ByteFormat.format(120L * 1024L * 1024L))
    }
}
