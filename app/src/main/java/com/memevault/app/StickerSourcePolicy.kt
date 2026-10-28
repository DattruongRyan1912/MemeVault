package com.memevault.app

import android.content.Context

/** What Sticker Studio does with the source image once a sticker has been saved. */
internal enum class SourceAfterSave {
    /** Leave the source where it is (library grid, or "Ảnh nguồn" if already archived). */
    KEEP,

    /** Move it under "Ảnh nguồn": out of the grid and keyboard, still re-editable. */
    ARCHIVE,

    /** Delete it; the sticker stays sendable but can no longer be re-edited. */
    DELETE,
}

internal object StickerSourcePolicy {
    /**
     * Delete is offered only for an image brought in just to make this sticker, before any other
     * sticker derives from it. Anything else is an existing library item or the re-edit source of
     * another sticker, and deleting it from Studio would be a silent side effect.
     */
    fun canDelete(importedNow: Boolean, otherDerived: Int): Boolean = importedNow && otherDerived == 0

    /** The remembered choice, downgraded to [SourceAfterSave.KEEP] when delete is not allowed here. */
    fun effective(preferred: SourceAfterSave, canDelete: Boolean): SourceAfterSave =
        if (preferred == SourceAfterSave.DELETE && !canDelete) SourceAfterSave.KEEP else preferred

    /**
     * Re-checked right after the save: the new sticker is the only derived asset allowed to
     * exist, otherwise the delete is downgraded to a keep.
     */
    fun canDeleteAfterSave(importedNow: Boolean, derivedAfterSave: Int): Boolean =
        importedNow && derivedAfterSave <= 1
}

/** Remembers the last source choice per device; written with apply() so it never blocks the UI. */
internal class StickerSourcePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("sticker_studio", Context.MODE_PRIVATE)

    var afterSave: SourceAfterSave
        get() = prefs.getString(KEY_AFTER_SAVE, null)
            ?.let { runCatching { SourceAfterSave.valueOf(it) }.getOrNull() }
            ?: SourceAfterSave.KEEP
        set(value) = prefs.edit().putString(KEY_AFTER_SAVE, value.name).apply()

    private companion object {
        const val KEY_AFTER_SAVE = "source_after_save"
    }
}
