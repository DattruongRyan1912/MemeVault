# Optional CyberOS integration

This project is prepared to coexist with the CyberOS surface used by `Dev-English`, but CyberOS
is not vendored in this initial scaffold.

- If `.cyberos/AGENT-ENTRY.md` exists, read and follow it for task lifecycle, machine gates,
  memory, and human-acceptance requirements.
- If it is absent, use `AGENTS.md` and the `.agents/` rules as the active project contract.
- Do not copy or hand-edit a generated `.cyberos/` machine to make the pointer look complete.
- Do not run `.cyberos` commands until the referenced file exists and its target project scope
  is confirmed.

