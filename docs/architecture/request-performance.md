# Request performance and retained-memory work

## Purpose and authorization

Make splice efficient with 50-70 simultaneous sessions without losing request semantics, trace detail, recovery or account safety. Marcos requested all identified operation and concurrency optimizations on October 13, 2026, asked for Chinese-source research first, then explicitly added instrumentation to identify unnecessarily retained heavy objects. Research is complete; implementation is authorized. Connected sessions are not the same as concurrent CPU work.

This is an execution plan and design contract, not a new enforcement mechanism. Work remains on the shared feat/v0.4.0 checkout. Splice-platform remains the only pusher. Ordinary development uses existing pre-commit checks; the existing full pre-push remains the release gate until PR #247 merges. No new approval is needed for routine reversible implementation choices.

## Current sequence

1. **Completed: source mapping and external research.** Source baseline a37ff412e. The installed baseline bbe7a874c lacks newer trace and account fixes. Source observations identify costs; they do not measure their contribution to live CPU.
2. **In progress: finish integrated implementation.** The lead's explicitly authorized workflows implement transferred scopes in the shared checkout. Platform retains its active trace writer/prefix/chunker/index files, capture route and qualified MCP pin. Completed peer digest work is integrated at its committed boundary. Transcript streaming and final composition hooks are still being finished. Each optimization removes repeated work or unnecessary retention while preserving the contracts below. No scope is complete merely because its builder committed.
3. **Pending: integrate and run the release gate.** One completed candidate, no per-edit suite. Fix gate failures forward without weakening ratchets or changing desired behavior to match obsolete tests.
4. **Pending: fresh scoped review and representative load check.** Review the implementation delta with the project's fresh reviewer. Reuse the existing desk with stand-in upstreams for one integrated before/after comparison at representative concurrency and history sizes. Measure local CPU, allocated and resident memory, queue/lock waits, latency and complete-record outcomes. Do not use operator tokens, prompts or production load for benchmarking.
5. **Pending: install the green reviewed candidate and inspect aggregate telemetry.** Back up and verify the running jar as usual. Report measured gains and any unresolved gaps. Completion is not implied by a green test alone or an assigned brief.

## Work scopes and owners

### Runtime front: request and trace pipeline

Owner lead: splice-runtime-lead. Primary builder: splice-platform. Files: features/turns, integrations/upstream transport/RoundBody, the affected dialect builders, and narrow supporting core byte/digest helpers. Existing dirty trace and adapter work stays with its current owner.

- Remove redundant full-record sizing, encoding, escaping, temporary body Strings and copies where the same representation is already available. Keep correct heap admission and exact UTF-8 behavior.
- Reuse byte-verified immutable message fragments and serialized lengths under bounded ownership. Prefer existing representation seams over a wholesale parser rewrite. Input identity alone is insufficient when model, dialect, prompt layers, tool schema, code-mode or effort changes.
- Keep existing trace prefix reuse, correct Unicode/chunk boundaries, current compressed-payload checks and writer healing. Do not trust a saved header digest as evidence about current payload bytes.
- Bound trace search hydration across nonmatching records. Avoid retaining decoded history after its last consumer. Retain complete search results and existing omission/error semantics.
- Consider boundary-preserving paired Gear updates only when the current recurrence and masks permit it. No new chunk layout or digest identity merely to get a speedup.

### Runtime front: transcripts, project lookup and concurrency

Primary builder: splice-sessions. Files: integrations/claude-code transcript paths, features/sessions, core/compaction/SessionProject and narrow shared scheduler/dispatcher changes coordinated with splice-platform.

- Keep bounded tail/page reads and cache unchanged row metadata. Bound history/ended-session metadata and fallback conversation materialization without silently dropping result coverage.
- Remove repeated cold project/session lookups through explicit identity and invalidation. Bound positive and negative caches. Do not replace freshness checks with arbitrary stale TTLs.
- Establish actual CPU and blocking-I/O dispatcher ownership, application and connection limits, shared FIFO contention and lock scope. Reuse bounded dispatcher views rather than creating per-request pools or increasing thread counts blindly.
- Implement fair scheduling only at an observed shared bottleneck. Byte-weighted work admission and short per-session ordered commit phases must preserve trace ordering and completion. Never hold a session lane across an upstream wait, tool callback or retry that must re-enter it. Cancellation releases work and memory without silently losing accepted trace records.

### Console front: configuration and credential evidence

Owner lead: splice-console-lead. Primary builder: splice-config. Files: core/config/ConfigService and immediate config mutation seams; integrations/upstream/credentials; provider auth/evidence adapters when needed.

- Publish deeply immutable, already merged/normalized configuration snapshots by revision. Retain authoritative external change detection and immediate control-plane updates. Per-request readers see internally consistent data, while live emergency/revocation checks remain current.
- Coalesce overlapping evidence reads per account and safely observed generation, distinct from existing OAuth refresh singleflight. Share evidence, not account selection, quota reservations or eligibility decisions.
- Do not accept stale completion after credential replacement/revocation. Keep concurrent waiter cancellation, failure propagation and key/waiter memory bounds correct. A file's mtime/size alone is not a strong credential generation.
- Eliminate redundant describe/credential reads only where their freshness and routing semantics remain identical.

### Console front: input digests and retention instrumentation

Primary builder: splice-usage. Files: core/perf, features/turns/perf, core/memory and existing diagnostics/perf routes, with specific cache/queue owner hooks coordinated through the runtime lead.

- Avoid repeating whole-prefix digest work using exact-byte prefix verification and privately owned unfinished SHA-256 checkpoints where supported. Preserve the current hash domain, delimiters and final digest. Finalized chunk hashes or a Merkle root are not a replacement for existing SHA-256 identities. Unsupported digest cloning falls back correctly.
- Add bounded low-overhead aggregate instrumentation of the existing ownership/admission transitions: active requests, queued trace bytes/work, decoded bodies, retained recent histories, transcript caches, config/auth snapshots, replay/code-mode state and their counts/estimated bytes/age/high-water marks.
- Distinguish estimated charged bytes from measured heap occupancy and resident memory; distinguish owner release from garbage collection. Show stale/expired-but-still-owned candidates without calling all uncollected objects leaks.
- The instrumentation must not keep a strong reference to payloads or otherwise create the retention being measured. Sampling/weak-reference lifecycle observation is acceptable only with a hard bound and no full-buffer scans on the request path.
- Expose summaries through an existing authenticated diagnostics/perf surface with no raw bodies, prompts, credential material, identifiers from operator content or high-cardinality per-message labels. Keep diagnostic collection optional where more than lightweight counters are needed. No heap dumps or forced GC for routine operation.
- Retain existing preparation/auth/network timings and clarify their boundaries. Add the missing before-send stage totals only where useful, avoiding per-request log spam and allocation-heavy instrumentation.

## Contracts that do not change

- Exact provider request bytes where required, especially Kimi; prompt and reasoning-effort/cache-key behavior; unknown-field and Unicode handling.
- Full configured trace content, retention, reconstruction, corruption detection, writer repair and truthful error reporting. No sampling of the actual trace, trace disabling or lower body caps.
- Fail-closed configuration and authorization; immediate revoke/hold/disable semantics; per-request quota checks; no cross-account secrets in shared immutable fragments.
- Existing crash/ordering promises. Ordinary trace writes already use the page cache; there is no per-turn force to remove.
- Bounded aggregate memory, cancellation propagation, per-session ordering where required and independent progress between unrelated sessions. Do not serialize entire turns to protect a small state update.
- Same checkout and file ownership. Commit only named own files; no private worktrees except authorized finished-commit build/desk trees.

## Research and limits

- Immutable publication and coroutine caveat: https://juejin.cn/post/7356894848517324819 and https://github.com/apache/brpc/issues/2209 . Use JVM atomic immutable references, not C++ thread-local reader locks across suspension.
- Overlapping computation sharing: https://github.com/Jaskey/singleflight and https://github.com/ben-manes/caffeine/wiki/Population . Singleflight is not a sequential cache and does not remove per-request eligibility checks.
- Byte fairness: https://kapsterio.github.io/test/2021/07/05/weightedfairqueuebytedistributor.html and https://netty.io/4.1/api/io/netty/handler/codec/http2/WeightedFairQueueByteDistributor.html . A finite lossless queue eventually applies backpressure; byte cost is not always CPU cost.
- Immutable fragment sequences: https://juejin.cn/post/7099628916666531847 and https://code.visualstudio.com/blogs/2018/03/23/text-buffer-reimplementation . Incoming full-body verification and full outgoing transmission remain linear in body length. Bound backing storage and fragmentation.
- Resumable digest state: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/MessageDigest.html . Clone only unfinished private state after exact prefix equality; handle unsupported providers without changing digest semantics.
- Gear acceleration: https://xiunianjun.github.io/2023/12/08/fastcdc/ and https://rickwinfrey.com/assets/papers/cdc/fastcdc-2020-xia.pdf . Check every original eligible boundary and the exact mask/overflow behavior. Published combined speedups are not splice predictions.

No claim of efficient concurrency, complete removal of overhead or a percentage improvement is established by source mapping or these papers. The integrated measurement must establish the actual outcome.

## Implementation receipts and open integration

These are committed source outcomes, not claims of measured improvement.

| Scope | Committed outcome | Revision |
|---|---|---|
| Trace search | Per-record decoded-literal retirement, preserving returned String leases and open pack readers | 38cb85c00 |
| Input identity | Exact-byte unfinished SHA-256 reuse, shared evidence budget, pinned owners and shorter locks | f718e852e, c3b75c383, 35124669f |
| Request representation | Operation-owned prepared bodies, unchanged-body account handoff reuse and scratch-free exact size counting | 16c08370e, 1e3b38de4 |
| Configuration | Deeply immutable normalized generations with live freshness, atomic PATCH and paired route provenance | 0f801d993, f834d3dde |
| Credential evidence | Bounded pre-read-frozen cohorts, publication fencing, cancellation isolation and per-call selection policy | 7c37d2eb3 |
| Transcript ownership | Streamed history suffix reads, bounded metadata caches, shorter publication locks, fresh project evidence and captured reply-group pages | 4221855af |
| Message fragments | Privately owned exact-equality reuse across independent dialect message transforms, with process bounds and capacity fallback | 5b51d986e |
| Charged memory | Unique allocation/owner accounting, fixed-category age coverage, retirement/eviction and separate JVM observations | dff897959 |
| Uncharged ownership | Zero-admission retained estimates, separated from charged bytes and actual process memory | 6c66d90f8 |
| Cache and replay attribution | Config/auth/transcript/project observations and categorized code-mode/replay acquisitions | b9efb0c8b, 7c37d2eb3, aec6a4a39, d53291554 |
| Runtime integration | Ingress/materialization categories, pool-bound asynchronous evidence and file-work outcomes | 08f3a3125 |

Message fragments deliberately leave live reasoning, request-local declaration/deduplication, prompt layering, name-collision state and slot leases outside reusable fragments. See docs/architecture/message-fragments.md. Incoming verification and full outgoing transmission still scale with body size.

Credential cohorts only share observations whose reads begin after all participating callers entered. Sequential observations remain fresh. The injected daemon probe scope is cancelled after head draining at Daemon.kt:292-297; reversible control-plane head stops do not destroy the reusable pool.

No speculative fair scheduler or thread-count increase was made. A runnable/shared bottleneck needs measurement. Existing FIFO outcome and queue/run elapsed counters are being exposed so contention is not inferred from connected sessions.

Mapped implementation is committed: bounded captured-source streaming and page leases (769d09964), authenticated aggregate memory/file-work exposure and before-send elapsed brackets (a0f1f0397), transcript/project composition bindings, and platform trace sizing/prefix/Gear/capture fixes (13eedefff, 0295ba48d, ae1ae80e7, 7ed57f228). Retained estimates overlap and must not be summed as process memory. Logical retirement is distinct from collection and accounting release.

The fresh independent review of a37ff412e..5e3c0f2bc reported three verified blockers: explicit no-tools choice still armed schema-tool output, captured page capacity failures were mislabeled missing/stale, and accepted trace tasks failing before encoding kept queued ownership. Fixes are committed as 7bca050db, e9a9117f8, and 4ec1b14d9/a55914750. The review did not execute native binaries. A fresh fix-range review is still required.

The retained gate has found compiler/API/architecture failures, all fixed forward without weakening ratchets. Committed remedies include explicit content-block cases, correct post-observer timing access, grouped diagnostic constructor fields, source-compatible timestamp lambda binding, actual terminal close ownership, role dispositions, bounded captured-package separation and documented constant purposes. The app concentration fold is committed as 15a0af554: process diagnostic composition belongs to the JVM adapter, while perf source footprint and generation metadata have separate owners. The exact 15a0af554 integrated gate is waiting under normal hostshield admission. The fresh fix-range and scoped MCP review brief covers 5e3c0f2bc..15a0af554 and is prepared, not yet run. No full green gate or review is claimed.

Release checkpoint: one completed candidate through the retained pre-push gate, a fresh independent delta review, then one representative synthetic before/after desk comparison and verified installation. Current partial candidate gates have found and fixed compiler issues. No full green candidate, review, measurement or optimized installation is claimed here.

The existing synthetic desk driver is adapted in a unique project checkpoint directory. It uses generated session identities and stand-in upstreams, records raw client timings and queried clock-tick frequency, compares full client and upstream body hashes through hydrated traces, and observes file-work terminal outcomes rather than directory-size stability. Baseline bbe7a874c includes fewer correctness fixes, so the comparison measures the combined candidate rather than isolating each optimization. Artifacts and operator-free data stay outside the repository.

The isolated 2 GiB baseline run did not complete successfully: 571 of 720 requests passed exact terminal/reply validation and 149 failed with observed 200/529 statuses. Its daemon reported heap-budget exhaustion. The initial runner then incorrectly waited for traces of all requests, including refusals; that diagnostic defect is fixed to report failed client load before checking drain. The failed run is preserved, not discarded or presented as a speed comparison. Candidate measurement is pending the green artifact. Tight host memory also defers further heavy desk work.

A separate read-only assessment of reported live local waits found no queued admission, sampled Java monitor deadlock or blocked Netty loop. Code-mode pipe readers had passed readiness, but idle loops and dispatched-result waits share their stack. Existing live aggregates do not expose occupied-cell count, pending-reply age or per-turn local stage. These observations neither explain isolated stuck sessions nor prove upstream blame, and no restart or live bottleneck fix was performed.

