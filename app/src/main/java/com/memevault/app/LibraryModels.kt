package com.memevault.app

import android.content.Context
import java.text.Collator
import java.util.Locale

/** Library ordering. "Added" and "recently used" are separate so new imports never hide. */
internal enum class LibrarySort(val label: String) {
    ADDED("Mới thêm"),
    RECENTLY_USED("Dùng gần đây"),
    MOST_USED("Dùng nhiều"),
    LEAST_USED("Ít dùng"),
    NAME("Tên A–Z");

    fun comparator(): Comparator<Asset> = when (this) {
        ADDED -> compareByDescending<Asset> { it.createdAt }.thenBy { it.id }
        RECENTLY_USED -> compareByDescending<Asset> { it.lastUsedAt ?: Long.MIN_VALUE }
            .thenByDescending { it.createdAt }
        MOST_USED -> compareByDescending<Asset> { it.useCount }
            .thenByDescending { it.lastUsedAt ?: it.createdAt }
        LEAST_USED -> compareBy<Asset> { it.useCount }.thenBy { it.createdAt }
        NAME -> {
            val collator = Collator.getInstance(Locale.forLanguageTag("vi")).apply {
                strength = Collator.SECONDARY
            }
            Comparator<Asset> { a, b -> collator.compare(a.displayName, b.displayName) }
                .thenBy { it.id }
        }
    }
}

/** Type chips shown in the library, in display order. */
internal enum class LibraryTypeFilter(val label: String, val filter: AssetFilter) {
    ALL("Tất cả", AssetFilter.ALL),
    FAVORITES("Yêu thích", AssetFilter.FAVORITES),
    MEMES("Meme", AssetFilter.MEMES),
    STICKERS("Sticker", AssetFilter.STICKERS),
    GIFS("GIF", AssetFilter.GIFS),
    VIDEOS("Video", AssetFilter.VIDEOS),
}

/** Tag scope: everything, one tag (by canonical key), or assets that still need tags. */
internal sealed class TagScope {
    object Any : TagScope()
    object Untagged : TagScope()
    data class Tag(val key: String, val label: String) : TagScope()
}

internal data class TagCount(val key: String, val label: String, val count: Int)

/** Pure, unit-tested projection of the loaded library onto the current view criteria. */
internal object LibraryView {
    fun matches(asset: Asset, filter: AssetFilter): Boolean = when (filter) {
        AssetFilter.ALL -> true
        AssetFilter.FAVORITES -> asset.favorite
        AssetFilter.ANIMATED -> asset.isAnimatedMedia
        AssetFilter.GIFS -> asset.type == AssetType.GIF
        AssetFilter.STICKERS -> asset.type == AssetType.STICKER
        AssetFilter.MEMES -> asset.type == AssetType.MEME
        AssetFilter.VIDEOS -> asset.type == AssetType.VIDEO
    }

    fun matches(asset: Asset, scope: TagScope): Boolean = when (scope) {
        TagScope.Any -> true
        TagScope.Untagged -> asset.tags.isEmpty()
        is TagScope.Tag -> asset.tags.any { tagKey(it) == scope.key }
    }

    fun apply(
        all: List<Asset>,
        filter: AssetFilter,
        scope: TagScope,
        sort: LibrarySort,
        excludedIds: Set<String> = emptySet(),
    ): List<Asset> = all.asSequence()
        .filter { it.id !in excludedIds && matches(it, filter) && matches(it, scope) }
        .sortedWith(sort.comparator())
        .toList()

    fun countsByFilter(all: List<Asset>, excludedIds: Set<String> = emptySet()): Map<AssetFilter, Int> {
        val visible = all.filter { it.id !in excludedIds }
        return LibraryTypeFilter.values().associate { chip ->
            chip.filter to visible.count { matches(it, chip.filter) }
        }
    }

    /** Most-used tags across the loaded assets, used as browsable collections. */
    fun topTags(all: List<Asset>, limit: Int, excludedIds: Set<String> = emptySet()): List<TagCount> {
        val counts = linkedMapOf<String, TagCount>()
        all.asSequence().filter { it.id !in excludedIds }.forEach { asset ->
            asset.tags.mapNotNull { TagNormalizer.normalize(it) }.distinctBy { it.key }.forEach { tag ->
                val previous = counts[tag.key]
                counts[tag.key] = TagCount(tag.key, previous?.label ?: tag.displayName, (previous?.count ?: 0) + 1)
            }
        }
        return counts.values
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.label })
            .take(limit)
    }

    fun tagKey(raw: String): String? = TagNormalizer.normalize(raw)?.key
}

/** Per-device library view preferences. Written with apply(); never blocks the UI thread. */
internal class LibraryPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("library_view", Context.MODE_PRIVATE)

    var sort: LibrarySort
        get() = prefs.getString(KEY_SORT, null)
            ?.let { runCatching { LibrarySort.valueOf(it) }.getOrNull() }
            ?: LibrarySort.ADDED
        set(value) = prefs.edit().putString(KEY_SORT, value.name).apply()

    var columns: Int
        get() = prefs.getInt(KEY_COLUMNS, DEFAULT_COLUMNS).takeIf { it in COLUMN_OPTIONS } ?: DEFAULT_COLUMNS
        set(value) = prefs.edit().putInt(KEY_COLUMNS, value).apply()

    companion object {
        val COLUMN_OPTIONS = listOf(2, 3, 4)
        const val DEFAULT_COLUMNS = 3
        private const val KEY_SORT = "sort"
        private const val KEY_COLUMNS = "columns"

        fun nextColumns(current: Int): Int =
            COLUMN_OPTIONS[(COLUMN_OPTIONS.indexOf(current).coerceAtLeast(0) + 1) % COLUMN_OPTIONS.size]

        /**
         * The preference is a density level, not a fixed count: wide windows (landscape,
         * tablets) get more columns so tiles keep roughly the same physical size.
         */
        fun spanFor(preferred: Int, availableWidthDp: Int): Int {
            val minTileDp = when (preferred) {
                2 -> 170
                3 -> 120
                else -> 90
            }
            return maxOf(preferred, availableWidthDp / minTileDp).coerceAtMost(12)
        }
    }
}
