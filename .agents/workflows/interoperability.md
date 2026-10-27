# Workflow: Interoperability Adapter

Use this workflow for keyboard, Sharesheet, clipboard, Messenger, Zalo, or TikTok work.

## Step 1 — Define the destination contract

Write down the host app, OS/device version, media types, MIME types, URI source, permission grant,
and expected user-visible result.

## Step 2 — Prepare variants

Resolve the best existing variant. If conversion is needed, preserve the original, validate the
output, and make conversion failure visible before launching the host app.

## Step 3 — Use the public seam

Use Android Sharesheet/Intents, `FileProvider`, clipboard, `commitContent()`, or the official
TikTok SDK. Do not use private endpoints, UI automation, undocumented package internals, or
permanent broad storage permissions.

## Step 4 — Provide fallback

If the host app is missing, cancels, rejects the MIME type, or does not advertise rich-content
support, keep the user in a recoverable state and offer the next available path.

## Step 5 — Test the matrix

Test supported and unsupported formats, single and multiple assets where relevant, cancellation,
permission denial, app-not-installed, rotation/background return, and a cold restart.

## Step 6 — Record the boundary

Classify the result as `VERIFIED`, `PRODUCT_DECISION`, `GAP`, or `REGRESSION`; include versions
and evidence in the handoff.

