# P0-XAI receipt: does api.x.ai /v1/messages stream faithful Anthropic SSE? (2026-07-30)

**Verdict: near-faithful — passthrough plus a small index-normalising shim. Do NOT port the
Responses translators.**

One streamed request to `https://api.x.ai/v1/messages`, Anthropic-shaped, with a tool and thinking
enabled. HTTP 200 in 3.4s, 26 SSE events. Credential: grok-oauth from `~/.grok/auth.json` — the same
file the grok head already ships against.

## What is faithful

| contract | observed |
|---|---|
| event sequence | `message_start → content_block_start → content_block_delta → content_block_stop → message_delta → message_stop` |
| thinking blocks | yes — `content_block_start` opens `{"type":"thinking","signature":"","thinking":""}`, streamed via `thinking_delta` |
| tool_use streaming | yes — block opens with the real `name` (`get_weather`) and an `id`, arguments stream as `input_json_delta` (2 events) |
| `stop_reason` | `tool_use` — correct for a turn that ends in a tool call |
| `message_stop` | present |
| usage | `message_start.usage` carries `input_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`, `output_tokens`; `message_delta.usage` carries `output_tokens` |

That is the whole Anthropic streaming shape, including the two parts most likely to be wrong
(thinking and tool-call streaming).

## The one defect — block indexing

Two problems, same root:

1. **`content_block_delta` never carries `index`.** 19 of 19 delta events omit it. Anthropic's
   contract puts `index` on every delta.
2. **`content_block_start` / `content_block_stop` pin `index` at 0 for every block.** Raw bytes:

   ```
   {"type":"content_block_start","index":0,"content_block":{"type":"thinking",...}}
   {"type":"content_block_stop","index":0}
   {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call-ecc569ee-…"}}
   {"type":"content_block_stop","index":0}
   ```

   The `tool_use` block should be `index: 1`. A client that keys blocks by index merges the thinking
   and tool_use blocks into one.

**It is cheaply recoverable, and that is what decides the verdict.** The blocks are strictly
sequential, and the probe now VERIFIES that rather than observing it (review of #70): it walks the
stream tracking open blocks and asserts `maxOpen <= 1`, zero deltas outside a `start`/`stop` pair,
and no unclosed block at the end. The run below reports `strictly sequential blocks: true (max
concurrently open: 1, orphan deltas: 0)`, and the assertion FAILS the spike if a future run
interleaves. So correct indices can be reconstructed by counting `content_block_start` occurrences
and stamping the running index onto `start`, every `delta`, and `stop`.

That is a ~10-line stateful rewrite over the SSE stream, not a dialect port.

## What this means for P6-GROK

- **Grok head = near-passthrough**: auth + model map + usage instrumentation, **plus** an
  index-normalising SSE shim.
- **The fallback is not needed.** `server/src/grok/translate-{request,response}.mjs` — the proven
  Responses-dialect translators the ledger named as the unfaithful-path fallback — do not have to be
  ported.
- **The shim needs a wall.** Interleaved blocks would break the count-the-starts reconstruction. This
  probe observed strictly-sequential blocks on one model, one turn shape. P6-GROK should pin a
  fixture asserting the reconstruction and fail loudly if x.ai ever interleaves.

## Scope of this evidence, stated honestly

Both checked-in artifacts (this receipt and `xai-passthrough.raw.txt`) now come from ONE run — the
2026-08-01 re-run after the review of #70. They previously disagreed (26 vs 25 events, 19 vs 18
deltas) because the raw file had been overwritten by a later run than the one the prose described;
there was no way to tell which supported the verdict. The numbers below are that single run's.

Thinking is now explicitly requested in the probe body (`"thinking":{"type":"enabled",...}`) and
asserted present. Previously the receipt claimed thinking fidelity while the request never asked
for it, so the claim could not be reproduced from the test. The probe also now asserts HTTP 200, a
non-empty event list and a `message_stop`, so a 500 or a malformed stream fails instead of quietly
writing a receipt.

One request, one model (`grok-4-latest`, served as `grok-4.3`), one turn shape (thinking + one tool
call). Not probed: multi-tool parallel rounds, `max_tokens` truncation, refusal/`stop_sequence`
stop reasons, error frames, or long-context behaviour. The index defect is stable enough to design
against; the *absence* of other defects is not established by a single turn.

## Why this ran at all

The ledger carried a `PREMISE-BLOCK` from 2026-07-16: *"no XAI_API_KEY in env and no
`~/.local/share/claude-grok/auth.json` on disk — the fidelity probe cannot be grounded."*

Both halves were wrong at the time of writing. `~/.local/share/claude-grok/auth.json` existed with
mtime **2026-07-15**, the day before the block — its token was expired, which is a different fact
than absence. And the probe never needed that file: the product's own grok head authenticates from
`~/.grok/auth.json` via grok-oauth, which was never checked.

The block cost two weeks on an item that a two-minute credential search would have unblocked.

Reproduce: `./gradlew :spikes:test -PrunSpikes --tests 'XaiPassthroughSpike*'` (skips, never fakes,
when no live token is present).
