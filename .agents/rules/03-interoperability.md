# Rule: Media Interoperability

## Export contract

Every export operation resolves a destination profile before opening another app:

1. Select original or a generated variant.
2. Validate the variant's MIME type, size, and destination constraints.
3. Expose it through a `content://` URI owned by a controlled `FileProvider`.
4. Grant read access only for the receiving action and only for the selected URI.
5. Record the local method (`direct`, `share`, or `copy`) without recording private chat data.
6. Show a recoverable fallback if the destination rejects or cancels the operation.

## Android keyboard

- Read `EditorInfo.contentMimeTypes` at input start.
- Call `commitContent()` only for a type the editor advertises.
- Use a provider with grantable, non-exported content access.
- Do not commit rich content while text composition is active unless the implementation has
  verified that the host editor preserves composition and focus.
- If capability is absent, offer Copy or Sharesheet and explain the next user action.

## Host-app adapters

- Messenger and Zalo use the system Sharesheet/clipboard as the baseline; do not depend on
  private package names or undocumented deep links.
- TikTok may use its official Share Kit after app registration and review. A generic Android
  Intent remains a fallback where the installed TikTok build accepts it.
- Never automate taps, scrape private APIs, or bypass host-app permissions and policies.
- Treat host-app updates as compatibility changes and rerun the device matrix.

