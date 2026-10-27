# Rule: Engineering Standards

## Architecture

- Keep UI, domain/media logic, persistence, and platform adapters separated.
- Prefer small explicit modules over framework magic.
- Model import, conversion, sharing, and keyboard states explicitly.
- Make durable metadata independent from regenerable thumbnails and variants.
- Keep external integration behind interfaces so a host-app change does not leak into the
  library or database layer.

## Storage and privacy

- Keep originals and generated variants under app-controlled storage.
- Expose only temporary, least-privilege `content://` grants to recipient apps.
- Deduplicate by a cryptographic content hash, not by filename or URL.
- Do not log image bytes, GIF frames, chat text, recipient identities, tokens, or secrets.
- Deleting an asset must define whether its variants and metadata are removed together.
- Do not upload media in MVP.

## Media handling

- Validate MIME type from bytes/decoder where possible; do not trust only a filename extension.
- Preserve the original before any conversion.
- Conversion failures are visible errors with a retry or fallback path.
- Keep animation timing and transparency when the destination format permits it.
- Bound image dimensions, GIF frame count, duration, and output size to protect memory and disk.

## UI and accessibility

- Provide loading, success, empty, error, permission-denied, and unsupported states.
- Use semantic labels for icon-only controls and make critical actions keyboard/accessibility
  service operable.
- Avoid loading full-resolution animations in a scrolling grid.
- Confirm destructive deletion and make the result reversible where practical.

## Testing and change discipline

- Test the happy path plus one empty/error/unsupported state for every changed flow.
- Unit-test metadata, hashing, MIME routing, variant selection, and state transitions.
- Use instrumented/device tests for FileProvider, Sharesheet, keyboard, and real host apps.
- Do not add dependencies unless the task explains why existing APIs are insufficient.
- Do not mix unrelated refactors, generated outputs, or deployment changes into a feature task.

