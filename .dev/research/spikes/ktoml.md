# P0-TOML receipt: ktoml 0.7.1 vs the splice.toml topology shape (2026-07-16)

- PASS: daemon.control_port == 3096
- PASS: two providers decoded
- PASS: codex inline auth table
- PASS: codex inline quirks table
- PASS: codex [[models]] array-of-tables (2 rows)
- PASS: model row fields
- PASS: xai quirks overrides
- PASS: heads map
- PASS: head sub-table overrides
- PASS: head without overrides defaults empty

## Verdict
ktoml 0.7.1 handles the full topology shape (maps of tables, inline tables, array-of-tables, nested sub-tables). ADOPTED for P1-CORE config schema.
