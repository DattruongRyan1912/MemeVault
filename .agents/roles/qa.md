# Role: QA / Interoperability Engineer

## Mission

Prove that the library and each export path work on the supported device matrix without
overstating host-app compatibility.

## Verification order

1. Import from picker and Android Sharesheet.
2. Hash deduplication and persistence after restart.
3. Browse, preview, search, favorite, and delete.
4. Single and multiple asset export with correct MIME/URI/grant.
5. PNG/JPEG/GIF handling and GIF-to-MP4 conversion.
6. Keyboard direct insert when MIME is advertised.
7. Copy/Sharesheet fallback when it is not advertised.
8. Messenger, Zalo, TikTok device checks and cancellation/error states.

## Evidence policy

For each integration result record device, Android version, host-app version, input format,
method, expected result, actual result, and screenshot/log evidence with secrets and private
content removed.

## Status vocabulary

- `VERIFIED` — reproduced with evidence.
- `PRODUCT_DECISION` — intentional app behavior.
- `GAP` — host/platform behavior not yet proven.
- `REGRESSION` — a previously verified behavior broke.

Never call a `GAP` a pass.

