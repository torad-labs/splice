---
name: db-migrator
description: >-
  This skill should be used when the user asks to "run migrations", "apply
  database changes", "migrate the schema", "run pending migrations", or
  "roll back a migration". It applies Drizzle ORM migrations in the correct
  sequence with validation and rollback support.
user-invocable: true
argument-hint: "[environment: dev|staging|prod]"
---

# Database Migrator

Applies Drizzle ORM migrations in sequence with pre-flight validation.

## Process

1. Read [`references/orchestration.md`](references/orchestration.md) for the
   dispatch table, gate conditions, and rollback protocol.
2. Execute the pipeline phases per the dispatch table.
3. Report each migration's status in the output format from
   [`assets/migration-report.md`](assets/migration-report.md).

## Phases

| Phase | Purpose |
|-------|---------|
| Validate | Check for schema conflicts, missing dependencies |
| Apply | Execute pending migrations in version order |
| Verify | Confirm schema matches expected state |

## References

- [`references/orchestration.md`](references/orchestration.md) — full dispatch
  table with model routing, inputs, gates, and rollback protocol
- [`assets/migration-report.md`](assets/migration-report.md) — output format template
