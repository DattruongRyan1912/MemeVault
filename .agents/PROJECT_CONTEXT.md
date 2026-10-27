# MemeVault Project Context

## Mission

Build a production-quality Android app that stores a user's memes, stickers, and GIFs locally
and makes them easy to reuse in Messenger, Zalo, TikTok, and other apps through supported
operating-system integration points.

## Product boundary

MemeVault owns the library, metadata, conversion variants, and export experience. Messenger,
Zalo, TikTok, and other host apps own their compose fields and private sticker catalogues.
MemeVault must not promise to install a third-party sticker pack into those private catalogues.

## MVP platform decision

- Android first, native Kotlin. The first vertical slice uses platform Views to keep the
  dependency surface small; a Compose migration is optional after device validation.
- Local-first storage; no account, server, or cloud sync in MVP.
- Room (or an equivalent local database) stores metadata; app-private files store media.
- `FileProvider`/`content://` URIs are used for external reads; never expose raw file paths.
- A target app's runtime capability decides whether direct rich-content insertion is offered.
- Every direct path has a Sharesheet or clipboard fallback.
- iOS is a later phase using Share Extension, Share Sheet, and pasteboard patterns.

## MVP user flows

### Import

The user shares an image or GIF from another app, selects MemeVault, optionally adds tags, and
sees the asset in the local library. Files imported from the system picker follow the same
normalization and deduplication path.

### Browse and organize

The user browses a grid, previews an asset, searches by title/tag, favorites an asset, and
deletes it. Recent usage is recorded without storing chat text or recipient information.

### Export

The user chooses an asset and receives a correct MIME type and temporary read grant through the
Android Sharesheet. Copy is available for apps that support pasteboard insertion.

### Chat keyboard

The optional MemeVault keyboard shows Recent and Favorites. It checks the target editor's
advertised MIME types before calling `commitContent()`. If the editor does not advertise a
compatible type, the keyboard offers Copy or opens the Sharesheet. Its top bar also exposes
`Đổi bàn phím`: on modern Android it returns to the previous IME (then cycles to the next one if
needed), while older Android versions use the remembered IME and fall back to the system picker.

### TikTok

PNG/JPEG is shared as an image. GIF is converted to an MP4 variant before sharing. A TikTok
adapter may use the official Share Kit when configured, with an Intent fallback where supported.

## Media contract

| Purpose | Preferred representation | Notes |
|---|---|---|
| Original | Preserve source bytes and MIME type | Never overwrite the user's original during conversion |
| Static sticker | PNG | Preserve transparency |
| Static meme | JPEG or PNG | Use the source when compatible |
| Animation | GIF | Keep for compatible recipients |
| Animation fallback | MP4/H.264 | Use for TikTok or a recipient without GIF support |
| Grid preview | WebP/JPEG | Regenerable cache, not the source of truth |

Each asset should have a stable id, content hash, MIME type, dimensions, optional duration,
source path, timestamps, favorite state, usage count, and tags. Variants reference the asset
and record their own MIME type, path, size, and generation profile.

## Out of scope for MVP

- iOS implementation.
- Login, backend, cloud sync, shared libraries, or public discovery.
- Injecting items into Messenger/Zalo sticker drawers.
- Automated scraping of meme/GIF sites or bypassing access controls.
- AI moderation, semantic search, and automatic tag generation.
- TikTok Content Posting API or unattended publishing.

## Known gaps and risks

- Direct rich-content insertion depends on the exact Messenger/Zalo build and editor MIME
  support; prove it on real devices and record the result per version.
- GIF-to-MP4 conversion may change transparency, timing, or quality; keep the original and show
  conversion errors clearly.
- Media rights belong to the user or license holder; imported content must not be redistributed
  by default.
- Android background limits, scoped storage, and OEM keyboard behavior require device testing.

## Current status

- Bootstrap Android app exists under `app/` with target/compile SDK 36 and Java/Kotlin 17.
- Room metadata schema and app-private original/variant storage are implemented.
- Picker/share receiver import, grid/search/filter/favorite/delete UI, Sharesheet/clipboard export,
  an optional MIME-aware keyboard seam, an in-keyboard switch control, and a quick static-image
  Sticker Studio using the ML Kit adapter are implemented.
- UI P0 checkpoint is now redesigned with RecyclerView/ListAdapter library cards, detail actions,
  a checkerboard/compare Sticker Studio surface, and a thumbnail media keyboard with Recent,
  Favorites, All, and a fixed keyboard-switch control.
- UI async callbacks are lifecycle-guarded; DB/share work is drained before repository close, and
  Studio cancellation bookkeeping covers queued and running operations.
- TikTok/Zalo sticker export uses a separate opaque PNG compatibility variant to reduce alpha
  auto-cropping; transparent PNG remains the default for Messenger and MIME-aware keyboard insertion.
- The keyboard resolves TikTok/Zalo host packages before export, prepares GIF/opaque variants off
  the IME main thread, and drops stale results when the input session changes.
- Sticker Studio can re-render a segmented subject from its mask with an optional colored outline;
  thickness is resolution-aware, and the palette/custom HEX value is applied only to the derived
  sticker PNG.
- Sticker Vault storage-first v3 is implemented: source lineage uses `source_asset_id` with
  `SET NULL`, `sticker_recipes` persists renderer/export parameters, the pre-segmentation crop
  rectangle and a grayscale PNG mask,
  tag relations carry MANUAL/SYSTEM/AI attribution, and canonical tags are normalized/migrated.
- Sticker saves stage and validate PNG/mask bytes before promoting atomically, insert asset/recipe/
  tags in one Room transaction, clean promoted files on failure, and reconcile missing/orphan files
  and stale staging directories at repository startup. Since 0.1.20 reconcile rebases absolute
  paths written under an old app-data prefix, never removes unresolved rows, and skips row/orphan
  cleanup when more than 10 items or half the library would go (`StorageReconcilePolicy`).
- Library cards, detail, the non-exported AssetPickerActivity, Sticker Studio and the keyboard all
  expose quick sticker creation. The picker filters static images and Sticker assets; the keyboard
  has a Sticker tab, reloads on `onStartInputView()`, and hides media/create actions for sensitive
  password fields.
- Sticker Studio includes a dependency-free crop editor before background removal; it keeps the
  full decoded source in memory, applies crop to the working bitmap, and restores the crop recipe
  after rotation/process recreation.
- GIF-to-MP4 is an explicitly bounded best-effort adapter and still needs real-device evidence.
- No account, cloud sync, third-party sticker-pack injection in the Android product, or
  unattended TikTok publishing is implemented. A separate experimental Mac-only bridge under
  `tools/zalo_desktop_bridge.py` can copy PNG bytes and a manifest to/from the observed Zalo
  Desktop cache only with explicit opt-in; it does not edit Zalo indexes and makes no native-
  sticker compatibility claim. A separate ADB-only lab bridge under
  `tools/zalo_android_bridge.py` probes candidate `photo_sticker/recent_sticker` paths on a
  connected Android device and can copy PNGs only after explicit `--apply --yes`; scoped-storage
  and Zalo sandbox permissions may block the path, and no native-sticker result is claimed.
- The Android app now includes an experimental **Thử đồng bộ Zalo** screen under the overflow
  menu. It uses a user-granted Storage Access Framework tree URI for two-way PNG exchange and
  never assumes access to Zalo's private sandbox. If Android cannot expose the target folder, the
  screen reports the block and the ADB bridge remains the Mac-only fallback.

- Library management v2 (0.1.19): zinc design tokens, media-first grid with adaptive density,
  type/tag collections, multi-select bulk favorite/tag/delete with an 8-second undo window,
  full-screen detail (rename, inline tags, technical info) and library statistics. Third-party
  host exports (TikTok/Zalo/animated sticker) are grouped under "Xuất nâng cao (thử nghiệm)".
  The Tenor/Giphy link importer (user-pasted URLs only, host allowlist, no crawling) is the only
  network path and requires the `INTERNET` permission.

## Planned next direction

`docs/system-design-plan.md` defines the target Sticker Studio, replaceable on-device background
removal engine, Room V2 recipe/mask/job model, quality gates, and phased delivery plan. The
personal-production gates, device matrix, local release and rollback checklist are tracked in
`docs/production-roadmap.md`. The UI information architecture, component contract, screen states
and redesign sequence are tracked in `docs/ui-redesign-plan.md`. The current quick static-image
slice plus storage-first recipe/mask/tag foundation and personal release signing are implemented;
brush editing and real-device quality verification remain pending.
