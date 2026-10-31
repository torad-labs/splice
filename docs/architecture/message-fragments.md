# Immutable request message fragments

This implements the independent-message part of request-performance.md. It does not cache a
complete request, BuiltTurn, slot lease, account selection, transport header or live reasoning lookup.

## Repeated work removed

- Chat reuses each message's mapped JSON before ChatAlternation. A hit avoids text/marker joins,
  image probing/data-URL construction and tool-argument rendering. Alternation and slot affinity
  still run on the current full conversation.
- Anthropic passthrough reuses scrubbed raw messages before request assembly. A hit avoids recursive
  block rebuilding, cache-control stripping, thinking validation and omission reconstruction.
  Capped tool-name heads retain the live scrubber because their reverse-map collision behavior can change.
- Responses reuses independent text/media/document/tool-result message fragments. A hit avoids the
  block walk, text joins, image work and loop-guard output construction. Any message carrying a
  tool-use or redacted-thinking block still takes the complete live path.

The fragment store is shared process-wide. Each builder has an opaque private namespace, so
different heads, dialects, quirk instances and name maps cannot share a result. Context contains
the raw non-message envelope, typed system/tools/tool choice/thinking/stream settings, routed
model and effective effort. Responses also includes reasoning display/summary/handoff settings,
the current tool-surface latch, session and current loop-guard directives.

Standing prompt layers, compaction tails and code-mode history shaping are not inputs to these
early fragments. They are applied later to every freshly assembled request, unchanged. The
cache does not stand in for those operations or for their identities.

## Equality and ownership

Fingerprints select candidates only. A candidate must match the complete current message and
context. Raw JSON equality compares object entries in emission order, array positions and exact
scalar lexemes. Typed equality additionally compares nested tool-input and unknown-block JSON
in emission order. This rejects hash collisions and key-order changes without rendering the
whole message merely to find a hit.

Admission precedes privately owned input/output container snapshots. Strings and scalar/media
DTOs are immutable; mutable lists/maps and nested tool/unknown JSON containers are copied and
wrapped against mutation. No containing raw request is retained.

The process bound is 8 MiB of conservatively estimated ownership and 2048 fragments, with a
512 KiB individual-entry admission ceiling. Context and both input/output snapshots are counted.
The global heap's bounded read view also admits every charge under RECENT_HISTORY.

Array consumers retain emitted objects rather than their fragment-array wrapper, so those
actual output roots hold shared heap handles. Evicting an entry releases the cache handle only.
Escaped roots keep the process byte/entry capacity charged until collection. A temporary shared
reader handle also protects input equality after concurrent eviction, including empty outputs.
Cleaner actions retain charge metadata, never payloads. Repeated hits do not register more Cleaners. Equality,
graph weighting, copying and transformation run outside the global metadata lock.

A large fragment, exhausted process bound or refused heap reservation falls back to the existing
uncached transformation. It never waits for cache capacity, drops content or refuses a request.

## Deliberate live seams and remaining work

ProviderTurnBuild cannot cache BuiltTurn safely: provider construction can acquire a slot lease,
its failure guard releases that lease, and compaction/prompt metadata belongs to this request.
The implementation therefore sits inside the message builders, before their assembly and before
ProviderTurnBuild applies current compaction/system/slot prompts.

ResponsesInputTools.appendToolUse reads live reasoning, modifies per-request declaration/reasoning
dedup sets and can shorten names. ResponsesReasoningInject handles the other injection path.
Neither is memoized. Whole-conversation prior-reasoning filtering also remains current.

Serialized request lengths and outgoing bytes remain at the existing transport representation
seam. This change does not duplicate the separately landed prepared-body/transport optimization
or introduce a parallel serializer.

No new tests, harnesses, goldens, ratchets or ignores are introduced. Existing pre-commit checks
run for the commit. The integrated release gate, exact-byte fixtures, fresh review and concurrent
measurement are still required by request-performance.md. This source change alone establishes
neither a latency reduction nor a verified gain at the target session load.
