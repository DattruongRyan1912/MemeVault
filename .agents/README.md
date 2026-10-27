# MemeVault Agent Operating System

`.agents/` is the shared, model-agnostic operating contract for Codex, Claude, Gemini, and
other coding agents working on MemeVault. It is adapted from the lightweight project surface
used by `Dev-English`, with product-specific media and mobile rules.

## Structure

- `PROJECT_CONTEXT.md` — mission, MVP boundary, platform assumptions, and known gaps.
- `rules/` — constraints that apply to every agent.
- `roles/` — responsibilities for planning, Android/platform, media/domain, UX, and QA work.
- `workflows/` — repeatable delivery and interoperability procedures.
- `templates/` — task specification and handoff formats.
- `checklists/` — release/readiness checks for the MVP.

## Source-of-truth hierarchy

1. Directly reproducible behavior on the target Android device/app version.
2. Official Android, Apple, TikTok, Messenger, or Zalo documentation for the relevant API.
3. Product decisions recorded in `PROJECT_CONTEXT.md` or the current task spec.
4. Tests and fixtures in this repository.
5. Explicitly labeled `INFERRED` recommendations.

`UNKNOWN` and `BLOCKED` are not implementation facts. Do not turn them into compatibility
claims.

## Interoperability principle

The stable core is `media library -> MIME/URI export -> system Sharesheet or clipboard`.
Android `Commit Content API` and per-app SDKs are optional adapters. They must never be the
only path for a user to use an asset.

The `.cyberos/` machine and its generated skill links are intentionally not copied from
`Dev-English`. If CyberOS is installed later, its generated files remain machine-owned.

