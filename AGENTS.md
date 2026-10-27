# MemeVault agent instructions

`.agents/` is the project operating contract for coding agents. Read these files before
planning or changing code:

1. `.agents/PROJECT_CONTEXT.md`
2. The relevant files in `.agents/rules/`
3. The role and workflow that match the task

This repository is an Android-first, local-first media vault for memes, stickers, and GIFs.
The product must provide a reliable export path to other apps; it must not claim that it can
inject content into another app's private sticker catalogue.

Keep changes small and evidence-based. Preserve unrelated work. Never print secrets or media
payloads in logs. Never push, deploy, merge, or delete data without an explicit user request.

If a `.cyberos/AGENT-ENTRY.md` file is later installed, follow it for CyberOS lifecycle and
human-acceptance gates in addition to these project rules. Until then, do not invoke `.cyberos`
commands that are not present.

