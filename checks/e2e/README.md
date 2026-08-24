# Head e2e checks

Full-stack end-to-end tests over **every configured head** (`claudex`, `claude-grok`,
`claude-kimi`, `openrouter`, …). Heads are discovered from the running daemon (`/api/heads`), so a
head added to `~/.config/splice/splice.toml` is exercised here with zero harness edits. Discovery
is the only roster — the harness keeps no hardcoded list of heads it expects to exist.

These are live, provider-billed, real-network tests — not unit tests. Run them by hand or in a
dedicated CI lane, not on every commit. **Both tiers spend real provider quota on every head**;
see [Client-auth heads](#client-auth-heads) for what that means on a head that forwards your own
Anthropic credential.

The merge gate runs a different path: `npm run e2e:heads:selftest` drives the same script against
a loopback control+head (no daemon, no vendor, no quota) so the skip / fake-token / FATAL-mgmt-key
arms cannot rot. That is the only heads-e2e path that belongs in `npm run gate`.

## Run

```bash
npm run e2e:heads:selftest # loopback canary (in the merge gate; no quota)
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
`claude-grok`, `claude-kimi`, …) inside an isolated tmux server (`-L splice-e2e`), auto-answers
first-run prompts, sends two live prompts, asserts the answers render, then runs an oracle over
the head's perf JSONL (`~/.claude-codex/state/<head>-perf.jsonl`) for the drive window. A head
that is not logged in is reported SKIP, never FAIL.

The perf oracle asserts three things about the window:

1. **at least 2 `outcome=ok` rows** landed — a turn actually happened;
2. **no *unrecovered* non-ok row** landed — nothing stayed broken alongside it;
3. **clean retry counters** on every ok row — `attempts==1`, `retries==0`, `refreshes==0`, checked
   as *value-when-present* (`TurnPerf.add()` drops zero deltas, so a clean turn omits the counter
   rather than writing 0, and `attempts` is bypassed entirely by the WebSocket runner).
   `search_rounds` is reported, never asserted — 1-3 is healthy on a responses head.

"Unrecovered" is load-bearing. The window is per-head **wall-clock** and a perf row carries no
session or PID discriminator, so it cannot be narrowed to the harness's own turns — asserting on
*any* non-ok row reds the head for traffic the harness never sent. So:

- `client_abort` never fails a head. It is recorded when the **client** went away, so an operator
  pressing Esc in another TUI during the multi-minute window is not a head defect. It is counted
  and printed as information.
- A non-ok row followed immediately by an `ok` row is a **retry that worked** — the user-visible
  outcome was success. It is pardoned and reported as `retried-then-ok`.
- Everything else fails: two failures in a row, or a failure with nothing after it. A genuinely
  broken head produces runs of consecutive failures, which no amount of unrelated concurrent
  traffic can pardon.

## Latency budgets (env, ms)

`E2E_TTFB_MS` (20000) · `E2E_FIRST_DELTA_MS` (45000) · `E2E_TOTAL_MS` (120000) · `E2E_GAP_MS` (30000).
Model override per head: `E2E_MODEL_<HEADKEY>` (e.g. `E2E_MODEL_CLAUDE_GROK=claude-grok--grok-4.5`);
default picks the first row matching `E2E_CHEAP_MODEL_RE` (`haiku|mini|spark|flash|lite|nano`).
When nothing matches, the run falls back to the catalog head — the most expensive row — and says
so loudly on stderr rather than billing it silently.

## Client-auth heads

A head configured with `auth.kind = "client"` holds **no splice credential**: it forwards the
caller's own `Authorization` header verbatim to `api.anthropic.com`.

**Tier 1 refuses to probe such a head** unless `SPLICE_E2E_CLIENT_TOKEN` supplies a real caller
credential; without it both tier-1 checks report **SKIP** with the reason. Probing it with the
daemon's management key would ship that key to the vendor. For the same reason, setting
`SPLICE_E2E_CLIENT_TOKEN` to the mgmt key is a **fatal preflight error** — it re-creates exactly
the leak the gate exists to prevent.

**Tier 2 is not gated and drives two real turns on your personal Anthropic subscription.** This is
deliberate. Tier 1's gate protects the *key*, not your quota, and tier 2 cannot leak the key
(`LaunchService` withholds `ANTHROPIC_AUTH_TOKEN` from a client-auth head, so the wrapper rides
your own `claude` login). Every other head tier 2 drives spends an OAuth subscription too, so
gating this one alone would single out a cost that is already universal. The spend is announced
per head at dispatch instead. Use `--tier 1` if you do not want it.

## Debugging a failure

`E2E_KEEP_TMUX=1` keeps the tmux session and scratch dir; attach with
`tmux -L splice-e2e attach -t e2e-<head>`. On cleanup the last pane is dumped to
`/tmp/splice-e2e-<head>-pane.txt`.
