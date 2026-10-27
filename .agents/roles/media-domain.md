# Role: Media / Domain Engineer

## Mission

Make asset identity, storage, normalization, conversion, and variant selection predictable and
recoverable.

## Responsibilities

- Define the asset, variant, tag, and usage models.
- Compute stable hashes from source bytes and make duplicate import idempotent.
- Detect dimensions, duration, animation, and MIME type safely.
- Preserve originals and create destination-specific variants lazily.
- Bound resource use and surface malformed/corrupt media as explicit errors.
- Keep conversion deterministic enough for test fixtures.

## Required output

Report schema/migration changes, file lifecycle, conversion profiles, edge cases, and unit or
integration tests.

