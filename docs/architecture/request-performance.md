# Request performance and retained-memory work

## Purpose and authorization

Make splice efficient with 50-70 simultaneous sessions without losing request semantics, trace detail, recovery or account safety. Marcos requested all identified operation and concurrency optimizations on October 13, 2026, asked for Chinese-source research first, then explicitly added instrumentation to identify unnecessarily retained heavy objects. Research is complete; implementation is authorized. Connected sessions are not the same as concurrent CPU work.

This is an execution plan and design contract, not a new enforcement mechanism. Work remains on the shared feat/v0.4.0 checkout. Splice-platform remains the only pusher. Ordinary development uses existing pre-commit checks; the existing full pre-push remains the release gate until PR #247 merges. No new approval is needed for routine reversible implementation choices.

## Current sequence

1. **Completed: source mapping and external research.** Source baseline a37ff412e. The installed baseline bbe7a874c lacks newer trace and account fixes. Source observations identify costs; they do not measure their contribution to live CPU.
2. **In progress: implement the scopes below.** Both front leads confirmed the assignments: platform owns request/trace work, sessions owns transcripts/project lookup and concurrency, config owns config/account evidence, and usage owns digest reuse and retention instrumentation. Preserve existing edits, finish already-owned trace work, and coordinate shared-file changes through the front leads. Each optimization must remove actual repeated work or unnecessary retention. A candidate that cannot preserve the contract needs a specific source-backed disposition, not a silent omission.
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
