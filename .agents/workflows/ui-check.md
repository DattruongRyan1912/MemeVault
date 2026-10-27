# Workflow: UI / Device Check

## Before the run

- Seed a small fixture set: transparent PNG, JPEG meme, animated GIF, malformed file, and a
  converted MP4.
- Record device, Android version, orientation, font scale, and installed host-app versions.
- Ensure test media is synthetic or licensed.

## Check order

1. Empty library and first-import onboarding.
2. Import progress, duplicate import, and malformed-media error.
3. Grid scroll, preview, favorite, tag, search, and delete.
4. Export success, cancel, unsupported format, and permission error.
5. Keyboard setup, direct insertion, Copy fallback, and return from Sharesheet.

## Evidence

Capture only the app state and synthetic fixtures. Do not capture private conversations, contact
names, tokens, or unrelated notifications. Report exact reproduction steps for every failure.

