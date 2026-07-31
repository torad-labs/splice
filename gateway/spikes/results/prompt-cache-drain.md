# claudex prompt-cache drain: measurement, cause, fix, and what is left (2026-07-30)

**Verdict: a gateway defect, now fixed. 92% of the waste came from the reasoning cache deleting
items from the MIDDLE of the input array. The remaining 8% is a server-side effect splice does not
control.**

claudex ran a 66.6% prompt-cache hit rate against grok's 98.0% and kimi's 96.3% on the same daemon,
same cache infrastructure. That gap is what this investigates.

## Measurement

Derived from 15,797 real cache-telemetry lines in `~/.claude-codex/logs/daemon.log`, over 7,056
consecutive CONTINUATION pairs (same conversation, input grew by <20k tokens).

For a prefix cache, turn N+1's input contains turn N's input as a strict prefix, so a healthy turn
should report `cached >= previous input`. The shortfall is tokens that were provably re-sent.

| fraction of the previous turn's input still cached | turns | wasted tokens | share |
|---|---|---|---|
| 0% (total miss) | 211 | 23,906,436 | 6.8% |
| 1-25% | 1,378 | **249,782,104** | **71.2%** |
| 25-50% | 377 | 35,565,279 | 10.1% |
| 50-75% | 337 | 13,399,501 | 3.8% |
| 75-99% | 2,729 | 26,961,485 | 7.7% |
| ~100% (healthy) | 2,024 | 1,306,127 | 0.4% |
| **total** | **7,056** | **350,920,932** | |

Only **7.4%** of continuation turns reused the full prefix they had already paid for.

The shape is the diagnosis: the 1-25% band has half as many turns as the 75-99% band but costs
**nine times** as much. An EARLY divergence invalidates everything after it, so a handful of turns
dominate the bill. 92% of all waste sits below 75% reuse.

## Cause (the 92%)

The builder injects each round's reasoning immediately before that round's FIRST `function_call`
(`ResponsesRequestBuilder.appendToolUse`, the RC-3 path). `ReasoningCache` then expired entries on a
30-minute **insertion** TTL and evicted **oldest-round-first** under bound pressure.

Both policies drop the OLDEST round first — precisely the item sitting EARLIEST in the input array.
Dropping it deletes an element from the middle of the array and shifts every element after it, so
turn N+1 stops being a prefix-extension of turn N and the whole remainder is re-billed.

Sessions run for hours against a 30-minute TTL, so this fired constantly.

**Why it survived review.** `ReasoningCache`'s own header says losing an entry "degrades to today's
no-injection behavior, never to an error." That is TRUE, and it is a per-turn statement. It is the
CROSS-TURN view that bills: no single request is malformed, but two consecutive requests disagree
about the middle of the array. Every existing test asserted one request at a time.

Reproduced offline against the real builder, no network, deterministic — evicting the single oldest
entry of an 8-round conversation:

```
CONTROL (nothing evicted):   100.0% of prefix reused
ONE oldest entry evicted:      7.7% of prefix reused
first divergence at index 2 of 26
  turn N   [2]: {"type":"reasoning","encrypted_content":"envelope-for-call_1"}
  turn N+1 [2]: {"type":"function_call","call_id":"call_1",...}
```

## Fix

A conversation's entries now live and die together (`ReasoningCache`):

- `lookup()` refreshes the whole conversation, making the TTL an **idle** timer. An active
  conversation never partially expires.
- `sweepLocked()` expires a conversation **wholesale**, so an idle one makes ONE clean transition to
  no-injection instead of churning round by round.
- bound eviction drops the oldest **conversation** as one unit, never a single round.
- a conversation that overflows the bounds **by itself** is marked non-injectable and dropped whole,
  rather than trimmed round by round.

That last point took two attempts and a review to get right, and the intermediate states are worth
recording because both look plausible:

1. *Wipe the active conversation wholesale* — empties the cache on every put once the conversation
   alone exceeds the bound. Four existing tests caught it.
2. *Fall back to oldest-round eviction for the active conversation* — passes those tests, but leaves
   the conversation half-cached, which is precisely the mid-prefix shift this whole fix exists to
   prevent (caught in review of #71).
3. *Wipe wholesale AND mark the conversation non-injectable* — correct. Without the marker, a wipe
   only trades grinding for oscillation: the conversation re-caches, overflows, wipes again, and
   each wipe makes rounds LOSE reasoning they already had. With it there is exactly one transition,
   then stability for the rest of that conversation's life.

Pinned by `PrefixStabilityDiagnostic`, which drives the REAL cache through a 12-round conversation
outliving its TTL and asserts every turn extends the previous turn's prefix exactly. On the parent
commit it fails at turn 2: *"rewrote the prefix at index 2 of 5 (40.0% reused)"*.

## What is NOT fixed, and why

### The 75-99% band (7.7% of waste) — server-side, not ours

If this were a structural mid-array rewrite the deficit would hold a roughly constant FRACTION of
context. It does the opposite:

| context | turns | median deficit | as % of context |
|---|---|---|---|
| <60k | 217 | 3,586 | 7.2% |
| 60-120k | 873 | 5,210 | 6.1% |
| 120-200k | 1,053 | 7,334 | 4.6% |
| >200k | 694 | 8,513 | 3.5% |

A bounded absolute deficit that shrinks as a fraction of context is the newest slice of the previous
request not yet committed to the upstream cache when the next request arrives. Only 3.0% of the band
is under 1,024 tokens, so it is not pure block granularity either. Nothing in the gateway controls
this.

### `previous_response_id` — real, but WebSocket-only

codex-rs does chain turns server-side and send only incremental items, which is why its cache
behaviour is near-perfect. It is **not** portable to splice as it stands:

- The mechanism lives entirely on codex's **v2 WebSocket** transport — `prepare_websocket_request`,
  `ResponseCreateWsRequest`, `ResponsesWsRequest::ResponseCreate` (`codex-rs/core/src/client.rs`).
  splice speaks HTTP SSE.
- It requires a cached, reused per-session WebSocket connection with a `generate=false` prewarm
  (`client.rs:16`).
- Reuse is gated by `responses_request_properties_match` (`client.rs:307`): model, instructions,
  tools, tool_choice, parallel_tool_calls, reasoning, store, stream, include, service_tier,
  `prompt_cache_key` and text must ALL be identical, or the connection is not reused.
- Note `store: provider.is_azure_responses_endpoint()` (`client.rs:921`) — store stays FALSE against
  the ChatGPT backend. The chaining is connection state, not server-side storage, so adopting it
  does not imply retaining conversation data upstream.

Adopting it means implementing the Responses WebSocket transport plus its connection lifecycle and
fallback logic. That is a separate project and an operator decision, not a bug fix.

## Reproduce

```
cd gateway && ./gradlew :dialect-openai-responses:test --tests 'PrefixStabilityDiagnostic'
```

The telemetry analysis reads `~/.claude-codex/logs/daemon.log` directly; the daemon has no
request-dump instrumentation, which is itself a `proxy-hardening` `W3-see-the-daemon` gap.

## Scope of this evidence, stated honestly

The token counts are exact and come from real turns. The attribution of the 1-25% band to reasoning
eviction is proven for the MECHANISM (offline, deterministic) and inferred for the LIVE turns — the
daemon logs no cache key or request bytes, so no single live turn was traced end to end. The
predicted post-fix hit rate is therefore not stated here; it should be measured from telemetry after
the fix has been deployed for a comparable period.
