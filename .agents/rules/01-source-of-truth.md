# Rule: Evidence and Compatibility Claims

## Evidence labels

- `VERIFIED`: reproduced on a named device/app version or confirmed by an official API contract.
- `PRODUCT_DECISION`: an intentional MemeVault behavior, not a claim about a host app.
- `INFERRED`: a reasonable design recommendation that still needs validation.
- `UNKNOWN`: insufficient evidence.
- `BLOCKED`: verification is unavailable because of permissions, device, or external state.

## Conflict resolution

When sources disagree:

1. Prefer direct, current device evidence for host-app behavior.
2. Prefer official platform documentation for API semantics and security requirements.
3. Prefer a newer explicit product decision over an older recommendation.
4. Record the conflict and the chosen behavior in the task handoff.
5. Do not silently widen support based on a single successful device.

## Compatibility rule

Every integration claim must state:

- source or API used;
- media type and MIME type;
- device/OS/host-app version when tested;
- success and fallback behavior;
- remaining gaps.

“The app supports Messenger/Zalo/TikTok” is not sufficient evidence by itself.

## No-fabrication rule

If behavior is `UNKNOWN` or `BLOCKED`, write `Assumption pending verification` or
`Blocked pending device test`. Do not label it `VERIFIED` or `MATCH`.

