# Head e2e checks

Full-stack end-to-end tests over **every configured head** (codex, grok, kimi, …). Heads are
discovered from the running daemon (`/api/heads`), so a head added to
`~/.config/splice/splice.toml` is exercised here with zero harness edits; a known-interesting head
that is absent (e.g. kimi before it is configured) is reported as **SKIP** with the fix.

These are live, provider-billed, real-network tests — not unit tests. Run them by hand or in a
dedicated CI lane, not on every commit.

## Run

```bash
npm run e2e:heads          # both tiers, every discovered head
npm run e2e:heads:wire     # tier 1 only (fast, no TUI)
bash checks/e2e/heads-e2e.sh --head claude-grok        # one head
bash checks/e2e/heads-e2e.sh --tier 2                  # TUI drives only
bash checks/e2e/heads-e2e.sh --list                    # what discovery sees
```

The harness cold-starts the daemon if it is down (same recipe as the CLI).

## Tiers

**Tier 1 — wire probe** (`stream_probe.py`): opens a real streaming turn straight at the head port
and validates the Anthropic SSE contract *as a client experiences it* — event ordering,
`content_block` start/delta/stop pairing by index, exactly one `message_start`, `message_stop`
last with nothing after, no `error` frame — plus that deltas arrive **incrementally** (a proxy that
buffers the whole reply into one flush fails even if the bytes are correct), plus latency budgets:
TTFB, first-delta, total, and max inter-event gap. Also a `count_tokens` sanity call.

**Tier 2 — tmux TUI drive**: launches the head's real Claude Code wrapper (`claudex`,
`claude-grok`, …) inside an isolated tmux server (`-L splice-e2e`), auto-answers first-run
prompts, sends two live prompts, asserts the answers render, then asserts fresh `outcome=ok` rows
landed in the head's perf JSONL (`~/.claude-codex/state/<head>-perf.jsonl`). A head that is not
logged in is reported SKIP, never FAIL.

## Latency budgets (env, ms)

`E2E_TTFB_MS` (20000) · `E2E_FIRST_DELTA_MS` (45000) · `E2E_TOTAL_MS` (120000) · `E2E_GAP_MS` (30000).
Model override per head: `E2E_MODEL_<HEADKEY>` (e.g. `E2E_MODEL_CLAUDE_GROK=claude-grok--grok-4.5`);
default picks a cheap-looking discovery row (mini/spark/flash/lite) else the first.

## Debugging a failure

`E2E_KEEP_TMUX=1` keeps the tmux session and scratch dir; attach with
`tmux -L splice-e2e attach -t e2e-<head>`. On cleanup the last pane is dumped to
`/tmp/splice-e2e-<head>-pane.txt`.
