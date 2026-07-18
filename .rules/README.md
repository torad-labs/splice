# splice architecture walls

All write-time policy is ast-grep rules in `rules/`. There are NO per-rule
Python hooks — `.claude/hooks/orchestrator.py` is the single router (operator
design constraint, 2026-07-13).

## How the same rule runs twice (same-checker-twice)

1. **Write time** — the orchestrator (PreToolUse on Write/Edit/MultiEdit)
   computes the post-edit file content, mirrors it to a temp tree at its
   repo-relative path, and runs `ast-grep scan --config sgconfig.yml <relpath>`
   from the mirror. `files:`/`ignores:` globs bind against the scanned relative
   path, so path scoping lives in the RULE, never in Python. `severity: error`
   blocks the write; lower severities are stderr advisories.
2. **Gate** — `npm run gate:rules` = `ast-grep scan` (whole tree) +
   `ast-grep test` (rule red/green cases). Runs in CI. A weakened hook still
   fails the build. The Stop lifecycle also scans the tree and blocks ending a
   turn on a dirty tree.

## Rule inventory

| rule | scope | wall |
|---|---|---|
| l2-single-mirror-definition | server/src (not reasoning/mirror.mjs) | invariant L2: one mirrorInto |
| l3-sole-message-stop-emitter | server/src (not anthropic/sse.mjs) | invariant L3: one wire emitter |
| l3-end-turn-via-emitter | server/src (not sse/translate-response) | invariant L3: no fabricated end_turn |
| no-claudex-magic-props | server/** | pure {req, meta} contract |
| loopback-bind-only | server/** | listen() carries literal '127.0.0.1' |
| launcher-no-pkill | server/launcher/** | pgrep loop, never pkill self-match |
| webui-fetch-only-in-api(-tsx) | webui/src | FSD: UI strictly via state |
| webui-no-emdash-ui-text | webui/src *.tsx | locked copy gate |
| webui-css-tokens-only | webui/src *.css | --space/--text token scales only |

Kotlin walls (`.rules/kotlin-splice/`) mirror the above for the gateway port. The 2026-07-18
additions are the **preventive walls** distilled from that day's incidents:

| rule | scope | wall |
|---|---|---|
| kt-no-quality-suppress | gateway/*/src | no @Suppress of detekt structural rules — fix the code, not the gate |
| kt-no-stream-options-request | responses/chat dialect src/main | vendor-contract: stream_options 400s the backend (shipped, broke codex) |
| kt-no-request-body-gzip | provider-spi src/main | vendor-contract: gzipped request body 400s xAI (shipped, broke grok) |

The two vendor-contract walls are the **write-time half**; `checks/e2e/heads-e2e.sh` (live head
probes over real backends) is the **run-time half** — a mock suite cannot see a 400 the real
vendor returns, which is exactly how both shipped.

L1 (no reasoning replay) was RETIRED 2026-07-14: encrypted reasoning replay is
now a supported, default-on, config-gated behavior (`replayReasoning`), paired
with `prompt_cache_key` for Codex-parity prompt-cache warmth. The mirror (L2)
remains the load-bearing thesis — reasoning still surfaces as visible text.

Invariant L4 (no fake summaries) and the L2 both-paths-call assertion are
test-plane invariants (`server/test/`), not lintable shapes.

## Authoring doctrine

- Prefer structural matching: `pattern` (with `context`/`selector` for
  ambiguous snippets), `kind`, `constraints` on metavariables, and relational
  rules (`inside`/`has`/`follows`/`precedes` with `stopBy`/`field`). `regex` is
  reserved for string CONTENT and name-family prefixes, ideally anchored on a
  specific `kind` (e.g. `string_fragment`, `unit`).
- Every rule carries `message` (one line) and `note` (remediation; the
  orchestrator prints both in block output).
- Every rule ships with a `rule-tests/<id>-test.yml` red/green case. Validate
  with `ast-grep test --skip-snapshot-tests` — the harness bypasses
  `files:` scoping, so path exemptions are proven by the orchestrator tests
  and the tree gate instead.
- Deliberate code exceptions: `// ast-grep-ignore: <rule-id>` with a written
  justification on the same or previous line.
- Wall infrastructure (this directory, `.claude/hooks/`, `.claude/settings.json`,
  `sgconfig.yml`) is grant-gated: writes require the operator to set
  `SPLICE_WALLS_OK=1`, loudly. A blocked write means fix the code, not the wall.
