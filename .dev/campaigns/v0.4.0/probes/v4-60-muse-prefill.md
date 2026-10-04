# V4-60 — can muse continue a stream that was cut after text went out?

**Answer so far: no mechanism has been measured to work, and the one splice already uses elsewhere
(assistant prefill) is measured NOT to exist on muse.** This is a bounded negative, not a complete
one: one shape is still unmeasured because the operator's muse subscription hit its weekly quota
mid-probe. No code changed, per the row's own condition ("code only if a mechanism is measured to
work").

## Why it matters

V4-57 made a mid-stream `RATE_LIMIT` continuable in two of three cases: nothing visible streamed yet
(full restart, every head), and text already streamed on a head measured to resume (kimi built in,
deepseek via `splice.toml`). The third case, text already streamed on **muse**, still ends the turn,
because restarting would duplicate everything the operator already read. muse is the operator's own
head, so the row asked for a measurement before accepting that.

## What the documentation settles

Meta's Messages API page says the adapter is **stateless**: it always runs with storage off, keeps
no server-side conversation, and has no `previous_response_id` equivalent; history is replayed
client-side by appending prior assistant turns. So muse documents **no** system-message resume
marker and no server-side resume affordance, and its architecture rules one out. The only documented
mechanism is replaying history, which the docs describe as a re-ask rather than a resumption of a
truncated answer.

Anthropic documents the same refusal for its own modern models: prefill is not supported on Claude
4.6 and later, and such requests return a 400. muse is matching the current Anthropic surface, so
this is the mechanism being absent across vendors, not a muse quirk anyone failed to work around.

## Live probes

All against `https://api.meta.ai/v1/messages`, model `muse-spark-1.3`, `max_tokens` 200, with bearer
auth as `MuseAuthProvider.kt:64` sends it. The key was read inside the probe script from the head's
own auth file and used only as the `Authorization` header; it was never printed or logged. The
script (`probe.py`) and its raw results were kept out of the repository under
`~/splice-builder-scratch/v4-60/`, following the V4-41 precedent.

**The discriminator, chosen so nobody has to judge prose:** every request gives the model the same
opening, an assistant turn that has already printed `1 2 3`. A continuation answers `4 … 10`; a
restart answers `1 … 10`. The script extracts the first number from the reply.

| # | shape | HTTP | result |
|---|---|---|---|
| 1 | trailing assistant turn (prefill), no thinking block anywhere, `thinking` omitted | 400 | `invalid_request_error`: "`messages`: assistant prefill is not supported by this server" |
| 2 | trailing user turn asking the model to continue (the ordinary shape) | 200 | `stop_reason: max_tokens`, content blocks **empty**, no text to compare |
| 3 | system-message resume marker plus a user turn | 429 | `rate_limit_error`: "Subscription quota exhausted. Your usage window resets at 2026-09-21T00:00:00Z." |
| 4 | `reasoning_effort: "minimal"` at the top level | 400 | `invalid_request_error`: "unknown parameter `reasoning_effort`" |

Earlier probes (2026-09-16, same row, through the running head) had already measured #1's 400.
**Probe 1 here is sharper than those:** every earlier prefill probe carried a thinking block in its
history, so it was still open whether muse was rejecting the thinking block rather than the prefill.
It was not. muse rejects the trailing-assistant shape on its own, so prefill is unavailable on muse
with or without thinking.

## Measured

- Assistant prefill is **definitively unavailable** on muse (probe 1).
- No resume mechanism is documented, and the stateless architecture rules out a server-side one.
- `reasoning_effort` is not a top-level Messages parameter (probe 4); the spelling that would reach
  that knob is still unknown.

## Not measured

- **Probe 2 produced no text.** muse reasons by default and can spend the whole output budget with
  nothing visible, so at 200 tokens there was nothing to compare. This is not evidence that
  continuation works. It needs a larger `max_tokens` (or a working reasoning-effort spelling) to
  produce a comparison.
- **Probe 3 hit the weekly quota.** Whether a system-message resume marker continues or restarts is
  unknown.

## Re-running it

1. **Read `seven_day` beside `five_hour`.** On 2026-09-16 the five-hour window read 0 % while the
   endpoint refused. The binding quota is the weekly one; the five-hour window alone says the account
   is clear when it is not.
2. Run on or after **2026-09-21T00:00:00Z**, not in the first minutes of a fresh week if the operator
   is working.
3. Run shapes 2 and 3 **once** each with a larger `max_tokens`, using the same discriminator, and
   record the first number either way. Do not poll or retry against a 429.
4. Code changes only if a shape is measured to **continue** (`first_number == 4`). A restart, an
   empty reply or a refusal is a finished negative, and the fifth exclusion in
   `RetryAlwaysArmedTest.kt` already carries the vendor-parity reason for it.
