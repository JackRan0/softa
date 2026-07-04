# Framework-Contributor Prompts

Prompt templates for AI agents (and humans) working **inside this monorepo** on
Softa itself. They carry monorepo-internal detail — `sys_*` reconciliation,
DDL auto-execute policy, and in-repo test/verification recipes — that
downstream SDK consumers don't need.

**Building a downstream app instead?** Use [`../authoring/`](../authoring/) — the
same authoring guidance with the internals stripped out.

| Prompt | Covers | Downstream how-to |
|---|---|---|
| [annotation-lane.md](annotation-lane.md) | how the scanner processes the 5 annotations: `sys_*` reconciliation, DDL policy, verification recipe, manual migrations | [../authoring/entities.md](../authoring/entities.md) |

App-config and seed-data have **no separate contributor doc** — their authoring
lives in [../authoring/config.md](../authoring/config.md) and
[../authoring/seed-data.md](../authoring/seed-data.md), and the few in-repo
specifics are folded into [annotation-lane.md](annotation-lane.md) under
"Framework-internal notes & pitfalls".

The Studio no-code lane prompt lives one level up:
[../studio-no-code.md](../studio-no-code.md).
