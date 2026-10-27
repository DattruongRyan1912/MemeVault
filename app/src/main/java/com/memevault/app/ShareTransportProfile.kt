package com.memevault.app

/**
 * The only production transport is the public Android MIME/content-URI contract. Native sticker
 * catalogues are owned by the recipient app and are deliberately not emulated here.
 */
enum class ShareTransportProfile {
    STANDARD,
}
