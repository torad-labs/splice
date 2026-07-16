# Orchestration

Dispatch table for the db-migrator pipeline.

## Contents

- [Dispatch Table](#dispatch-table)
- [Gate Conditions](#gate-conditions)
- [Rollback Protocol](#rollback-protocol)

## Dispatch Table

| Phase | Model | Input | Reads | Writes | Gate |
|-------|-------|-------|-------|--------|------|
| Validate | haiku | env, migration dir | `/tmp/db-migrator-pending.json` (script output) | `/tmp/db-migrator-validate.md` | VALIDATION PASSED present in response |
| Apply | sonnet | env, validate output | migration files, validate report | `/tmp/db-migrator-apply.md` | APPLIED: [N] migrations present |
| Verify | haiku | env, apply output | current schema, expected schema | — | SCHEMA MATCH: yes/no present |

## Gate Conditions

- **Validate gate**: If any migration has an unresolved dependency, halt.
  Output: `VALIDATION FAILED: [reason]`.
- **Apply gate**: Run only if Validate gate passed. Never skip.
- **Verify gate**: Run always. If schema mismatch detected, trigger rollback.

## Rollback Protocol

If Verify returns `SCHEMA MATCH: no`:

1. Read `/tmp/db-migrator-apply.md` for the list of applied migrations.
2. Reverse each migration in reverse order (last applied → first rolled back).
3. Re-run Verify after each rollback step.
4. Report rollback status.

Environment `prod`: Require explicit user confirmation before rollback.
Environment `dev`/`staging`: Proceed automatically.
