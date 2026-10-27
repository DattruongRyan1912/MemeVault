# Workflow: Feature Delivery

## 1. Ground

- Read `AGENTS.md`, `PROJECT_CONTEXT.md`, and the relevant `.agents/rules/` files.
- Identify whether the task is library, media, UI, platform, or host-app integration work.
- List verified facts, product decisions, unknowns, and blockers.

## 2. Specify

Before editing, define the goal, affected modules, acceptance criteria, supported media types,
states, fallback, non-goals, and verification plan. Use `.agents/templates/task-spec.md` for
non-trivial work.

## 3. Inspect

Read existing implementation, manifest/provider declarations, fixtures, and tests first. Reuse
patterns unless they conflict with the project rules.

## 4. Implement

Make the smallest coherent change. Preserve originals, least-privilege URI grants, explicit
state handling, and a usable fallback. Do not broaden host-app support by assumption.

## 5. Verify

Run focused unit/integration tests, then the narrowest available device or UI check. Report only
commands and device checks actually run.

## 6. Self-review

Check data privacy, malformed media, cancellation, permission denial, unsupported MIME types,
duplicate import, restart persistence, accessibility, and unrelated diff scope.

## 7. Handoff

Use `.agents/templates/handoff.md`. Include evidence, compatibility status, gaps, and the next
smallest useful task.

