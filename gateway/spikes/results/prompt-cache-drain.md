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

Two reuse statistics, at different thresholds (they are NOT contradictory — review of #71 round 2
flagged the ambiguity): **7.4%** of continuation turns reused the full prefix to within one
128-token cache block (measured over 6,909 pairs from a first extraction pass with a slightly
stricter pairing filter), while the table's ~100% band (28.7% of 7,056 pairs) uses the looser
"≥99% of the previous input" cut, which at ~150k-token contexts allows ~1.5k tokens of slack.
Full-prefix reuse in the strict sense was rare; near-full reuse was a minority.

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

The **conversation is the primary cache record** (`ReasoningCache`, reworked 2026-07-31 after a
second review round): one rounds map, ONE idle timestamp, ONE admission flag. The policies fall out
of the data shape instead of being retrofitted onto a flat per-round map:

- every build takes ONE atomic `snapshot()` of the conversation (which is also its single touch),
  making the TTL an **idle** timer — an active conversation never partially expires, and a build
  can never tear across a concurrent eviction.
- idle expiry is **wholesale by construction**: one record, one clock; there is no per-round age to
  half-expire on.
- bound pressure evicts the least-recently-touched **neighbor** conversation whole — never the
  conversation being written.
- a conversation that alone exceeds the bounds **freezes admission**: the offered round (never yet
  injected, so rejecting it shifts nothing) is dropped and every admitted round keeps serving. The
  tail loses its injection; the prefix never busts.
- a stale-envelope 400 (`evictByToolId`) evicts the **whole conversation**, never a round — a
  per-round hole in a conversation that no longer ages out would shift the prefix forever.

It took four designs and two review rounds to get here, and the intermediate states are worth
recording because each looks plausible:

1. *Wipe the active conversation wholesale* — empties the cache on every put once the conversation
   alone exceeds the bound. Four existing tests caught it.
2. *Fall back to oldest-round eviction for the active conversation* — passes those tests, but
   leaves the conversation half-cached, the exact mid-prefix shift this fix exists to prevent
   (review round 1).
3. *Wipe wholesale AND mark non-injectable* — shipped briefly. Review round 2 confirmed four holes
   mechanically: the disable trigger fired on "the writer owns the globally-oldest entry" (neighbor
   pressure permanently disabled innocent conversations); touch-immortality made the 256-round cap
   the guaranteed end state of every long session, wiping and disabling it at round ~257; a
   conversation evicted as a cross-pressure victim re-cached into a partial group with no marker
   (per-cycle re-bills); and `evictByToolId` still punched per-round holes that touch-refresh then
   kept alive forever.
4. *Conversation-primary records with freeze-admission* — current. Freeze-admission is strictly
   better than wipe+disable: zero prefix busts instead of one catastrophic one, and rounds already
   paid for keep serving. A 257-round session keeps rounds 1..256 injecting; only the tail goes
   uninjected (tail-append, prefix-stable).

Pinned by `PrefixStabilityDiagnostic` (every turn both extends the previous prefix exactly AND
actually injects every round's reasoning — the second assertion exists because prefix-extension
alone is vacuously satisfied by a cache that injects nothing) and by `ReasoningCacheTest` pins for
neighbor-eviction, freeze-admission, wholesale stale eviction, retry-grace, and the null-key status
quo. All five round-2 defect pins were run RED against the wipe+disable design before the rework.

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

### Known residual limitations (review of #71 round 2)

- **Null-key conversations** (first user message with no text blocks — image-first or
  tool_result-first openers) have no grouping identity, so they keep the ORIGINAL flat per-round
  insertion TTL and can still hit the mid-conversation-expiry pathology this fix removes for keyed
  conversations. They also share one id namespace, so a cross-conversation `call_id` collision
  inside that class could cross-inject (pre-existing; low probability; no coverage).
- **Conversation-key fusion**: the key is a hash of the first user message's text alone, so two
  concurrent sessions opening with byte-identical first messages (scripted `claude -p` dispatch,
  templated subagent openers) fuse into one cache unit — shared budget, shared idle clock, shared
  freeze. With freeze-admission the worst case is tail rounds losing injection and retention
  extending while either session is active; nothing wipes or cross-injects.
- **A second mid-array injection source is NOT protected**: the deferred-tool declaration pair
  (`tool_search_call`/`tool_search_output`) that CHANGE 2 injects in history vanishes mid-array if
  a deferred tool leaves `body.tools` (MCP disconnect, schema change) — empirically reproduced at
  27.3% prefix reuse. Blast radius today is small (27 tool-search rounds in the whole log); it
  belongs to the `proxy-hardening` campaign, not this fix.
- **Retention semantics changed**: the reasoning-cache TTL is now idle-based, so an active
  session's encrypted envelopes stay in memory for the session's lifetime rather than a fixed 30
  minutes. SECURITY.md was updated to say so (hard caps: 256 rounds / 64 MB, wholesale eviction,
  ciphertext only, never on disk).

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
