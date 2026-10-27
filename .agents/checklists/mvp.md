# MemeVault MVP Readiness Checklist

## Library and data

- [ ] Import works from picker and Android Sharesheet.
- [ ] Original bytes are preserved.
- [ ] Hash-based duplicate import is idempotent.
- [ ] Metadata and favorites survive process death/restart.
- [ ] Thumbnail/cache regeneration does not lose originals.
- [ ] Delete behavior removes the intended asset and variants.

## Media

- [ ] PNG transparency is preserved where supported.
- [ ] JPEG/PNG previews do not block grid scrolling.
- [ ] GIF playback and malformed-file errors are handled.
- [ ] GIF-to-MP4 conversion has a bounded size/duration and visible failure state.
- [ ] MIME type is derived/validated consistently.

## Export and platform

- [ ] `content://` URIs are used for external reads.
- [ ] URI grants are temporary and least-privilege.
- [ ] Single and multiple export paths use the correct action and MIME type.
- [ ] Copy and Sharesheet fallback are available.
- [ ] Keyboard calls `commitContent()` only for advertised MIME types.
- [ ] Keyboard unsupported state gives a clear next action.
- [ ] Keyboard has an in-keyboard control to return to the previous IME or open the system picker.

## Host-app evidence

- [ ] Messenger result recorded on a real device.
- [ ] Zalo result recorded on a real device.
- [ ] TikTok image result recorded on a real device.
- [ ] TikTok MP4 result recorded on a real device.
- [ ] Cancel, missing-app, permission, and unsupported-format paths are checked.
- [ ] Host-app compatibility claims include app and OS versions.

## Quality and safety

- [ ] Unit tests cover hashing, metadata, MIME routing, and state transitions.
- [ ] UI/device checks cover loading, empty, error, and accessibility states.
- [ ] No secrets, chat text, contact data, or private media appear in logs or fixtures.
- [ ] Only MVP-scoped files changed.
- [ ] Handoff is complete and unresolved gaps are explicit.
