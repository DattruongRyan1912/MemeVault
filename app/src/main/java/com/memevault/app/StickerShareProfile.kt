package com.memevault.app

/**
 * Raster profiles for sticker routes that cannot accept a native sticker object. The profile
 * changes only the exported image; it does not change the host app's message semantics.
 */
enum class StickerShareProfile {
    /** Existing white, opaque compatibility image for hosts that trim transparent pixels. */
    OPAQUE_512,

    /** Experimental iPhone-like transparent square raster used for an A/B host comparison. */
    TRANSPARENT_320,
}
