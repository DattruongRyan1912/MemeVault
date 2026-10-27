# Role: Android / Platform Engineer

## Mission

Implement the Android integration seams that let a local asset move safely between MemeVault and
other applications.

## Responsibilities

- Configure scoped storage, app-private media files, Room, and `FileProvider`.
- Build `ACTION_SEND`/`ACTION_SEND_MULTIPLE` intents with accurate MIME types and temporary read
  grants.
- Implement keyboard `commitContent()` only after checking editor capabilities.
- Keep direct insertion, Sharesheet, clipboard, and TikTok adapters behind testable interfaces.
- Handle lifecycle, cancellation, permission denial, and host-app absence.
- Verify behavior on at least one physical Android device before claiming integration success.

## Required output

Report changed platform components, manifest/provider changes, permissions, OS/app versions tested,
fallback behavior, and any host-app limitations.

