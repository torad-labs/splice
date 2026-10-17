# Claudex performance investigation — October 14, 2026

## Confirmed finding

The live JVM profile identifies expensive code-mode bookkeeping under the Codex head's shared registry lock. It affects both source streaming and new tool-program admission, so unrelated conversations compete for it. This is a measured local bottleneck. It does not, by itself, explain every multi-minute delay.

The patch removes three sources of repeated work:

- `CodeModeSourceRecords.append` checks that its record is still registered on every streamed source prefix. The registry previously used `ArrayList.contains`, scanning retained records and calling generated structural equality. `CodeModeRecordList` now indexes live object identities while preserving list order, equality semantics, duplicate membership and all removal paths. Its index releases references when the list does.
- `CodexCodeModeRegistry.AdmissionPlan` computed new expiry markers with a list membership check for each candidate marker: quadratic work while holding the shared lock. It now builds one set of the previous markers before computing the difference.
- `CodexCodeModeSweeper.expireRecords(key)` previously grouped all conversations and examined all running records even for a query about one conversation. It now selects that conversation before grouping and checking its lifetime. Global startup sweeping and conversation-wide expiry semantics remain intact.

The shared lock remains: these changes shorten its critical sections without changing transaction, persistence, cancellation or no-rerun guarantees. They do not shard the entire registry or make all its queries constant-time.

## Live evidence

Recording: October 14, 01:20:57–01:21:57 America/Chicago, a bounded 60-second JFR profile of PID 2763056. No synthetic requests, forced GC or heap dump were used against the live daemon. The profile predates the patch and its compilation.

The open jar descriptor and installed jar both hashed to `438c740a96088f3d68088fabde4660924c0e9919e020f892127efdaffefcf0c2`. Health reported `0.4.0-beta.1`; this version string alone does not identify the source revision. The source checkout began at `03ca00a86`.

| Observation | Evidence |
|---|---|
| Record membership CPU | 228 of 864 Java execution samples contained `CodeModeRecord.equals`; a captured stack continues through `ArrayList.contains` into `CodeModeSourceRecords.append` |
| Expiry-marker membership CPU | 128 of 864 samples contained `CodeModeExpiredSnapshot.equals`; a captured stack continues through `ArrayList.contains` into `AdmissionPlan` |
| Repeated expiry work | 95 samples contained `expireRecords`; a captured caller is `CodeModeRecordQueries.recordsFor` during a turn |
| Lock contention | 12 recorded parks in registry paths totalled 0.830 seconds of thread wait; the longest was 284 ms in streamed source append |
| Overall process CPU | JFR CPU-load samples averaged approximately 0.83 logical core during this particular minute; instantaneous load and other hours differ |
| Process memory | Approximately 6.94 GiB RSS and 321 threads; launch flags were `-Xms4g -Xmx8g` |
| Heap occupancy | Approximately 4.37 GiB before the observed collection and 1.73 GiB afterward; committed heap and RSS are different quantities |
| Allocation churn | Observed thread allocation-counter increases summed to 2.32 GB during the recording; this is allocation volume, not retained heap |

The two equality paths account for about 41% of Java execution samples. Sample shares are not end-to-end speedup estimates, and park times are summed across threads. This recording does not establish a deadlock or an 18-minute lock hold.

Do not sum raw `ObjectAllocationSample.weight` as this minute's allocation total: first samples include misleadingly large weights. The allocation figure above comes from thread allocation counters. High RSS alone does not prove a leak: the JVM was configured with a 4 GiB minimum heap, and this collection reclaimed substantial memory.

The first thread dump also caught the single `splice-file-io` worker in `DayBodyBudget.admit`, checking volume capacity while holding the trace directory fence. This is a separate serial lane worth measuring at representative concurrency. It contributed only 16 Java execution samples in this recording, so it was not the leading sampled Java CPU consumer. The patch does not weaken trace recording or remove compression.

## Request timing and remaining stalls

An initial six-hour scan contained 17,898 Claudex rows, including local code-mode resumptions. The 10,836 rows with upstream first-byte timing are a different population; do not equate every row with a model call.

| Metric | Median | p95 | p99 |
|---|---:|---:|---:|
| Admission wait | 0 ms | 0 ms | 1 ms |
| Request preparation | 12 ms | 48 ms | 104 ms |
| Upstream first byte | 563 ms | 10.9 s | 45.4 s |
| First client delta, when recorded | 11.4 s | 58.4 s | 113.4 s |
| Maximum local event-consumer blocking per upstream row | 174 ms | 724 ms | 1.43 s |

The longest observed request received its first upstream byte at 1.98 seconds and its first client delta at 1,084 seconds. It reported 20,171 reasoning tokens and 20,532 total output tokens. That distinguishes it from an admission-queue wait, but does not identify the complete cause of the long interval. Its recorded local consumer blocking peaked at 331 ms. Do not claim the registry patch alone resolves that entire interval or infer blanket upstream blame from first-byte timing.

Later aggregate checks found active WebSocket continuation (7,365 logged chained sends), zero logged inbox overflows, and approximately 96.4% cached input tokens among recent upstream rows with usage. These are observations with different sampling boundaries, not a controlled Codex-versus-Claudex latency comparison. Increasing `maxInflight` would not fix the observed bookkeeping cost: the live gate had no queued requests and no recorded admission waits.

## Comparison with current Codex source

The initial local Codex checkout was dated September 29. The comparison was refreshed from official `openai/codex` at `7eed30ad1beaebc20b7d848ce9ee887525a379b0`, dated October 14.

| Concern | Codex | Splice |
|---|---|---|
| State ownership | `ModelClientState` belongs to a session; code-mode `SessionInner` has a session-owned mutex | One `CodexCodeModeRegistry` holds every conversation for a head; key locks still enter its shared monitor |
| Continuation | Reuses a WebSocket and validates an incremental input before sending `previous_response_id` | Already implements and actively uses this mechanism in `ResponsesWsSession`; lack of WebSockets is not the finding |
| Tool execution | The session delegates execution to its process host | Bridges streamed model programs to Claude Code callbacks and persists recovery state; the measured scans are inside that bridge |

Sources: [Codex model client](https://github.com/openai/codex/blob/7eed30ad1beaebc20b7d848ce9ee887525a379b0/codex-rs/core/src/client.rs), [Codex code-mode session](https://github.com/openai/codex/blob/7eed30ad1beaebc20b7d848ce9ee887525a379b0/codex-rs/code-mode/src/remote_session.rs).

Codex's session ownership is the useful architectural comparison. Splice's recovery journaling still has to preserve its own guarantees.

## Verification and rollout boundary

The Codex provider's 724 existing tests and detekt passed after the final code change. The structural rule scan, rule fixtures, routing, single-source and config guards also passed. A separate temporary probe exercised 10,000 mixed list mutations, including duplicate owners, detached equal values, bulk removal, sublists and retirement; its results matched an ordinary mutable list.

Using synthetic records and the installed record classes, at 14,000 retained entries:

- 20,000 membership checks: 668.3 ms with the original list, 0.37 ms with the indexed list (median of five runs after warmup).
- Expiry-marker difference: 667.6 ms with repeated list membership, 1.78 ms with one membership set.

These are operation-level measurements on a busy host, not a promised model-response speedup or a measurement of the live registry's record count. The first run also showed a large reduction (364 ms to 0.094 ms for membership; 255 ms to 1.83 ms for marker differences).

`npm run gate` judges committed HEAD in `.git/splice-prepush/gate`. The initial run checked the integration baseline `03ca00a86`, rather than the working-tree patch. It finished with `BUILD FAILED` and `GATE: FAIL`: `:releaseTests` found a release-workflow asset mismatch at `tools/release/test/workflow.test.ts:45`. Its stale ignored manifest omitted `splice-mcp-holder` and `splice-mcp-front`, which the current staging code and release workflow include. The subsequent release rehearsal refreshed the staged bundle and passed; that initial run did not qualify the patch.

The runtime fix was then committed as `b8ca1e422` and gated as committed source. The release mismatch disappeared after the stale ignored bundle was refreshed. That run exposed two additional findings: the new file exceeded the existing state package's 29-file concentration limit, and an existing watchdog test raced cancellation cleanup. `ca790476b` moves the collection into `state.collection` without changing the limit. `56ed4f3fd` makes the existing test await the cancelled round and check that its cleanup observed the typed verdict; runtime timeout behavior is unchanged. A subsequent run exposed another test setup race: the mock transport's exit precedes the reader's persisted loss. `9d46f785e` makes that recovery test await persisted loss while retaining its no-rerun and tool-result assertions.

The complete `npm run gate` passed against committed candidate `9d46f785e`: `BUILD SUCCESSFUL` in 6m 42s, release acceptance and verification passed, final `GATE: PASS`, process exit 0. This includes the provider suites, architecture laws and all 11 byte-exact oracle fixtures. The full receipt is retained locally at `captures/claudex-performance-20261014/qualified-gate.log`. The candidate is committed locally; no push, daemon installation or live before/after latency improvement is claimed. The project's release ownership still reserves pushing for splice-platform and installation for splice-lead.

## Transcript continuity

### Installation recovery finding

The first beta.2 restart exposed a second issue: Claudex failed while saving restored code-mode state. Restoring the prior beta.1 jar reproduced the failure. A bounded startup JFR of PID 638155 identified `HeapCapacityException` at `CodeModeSaveHeap.reserve`, wrapped as `CodeModePersistenceException`; no exception messages or journal payloads were printed. The state directory held 1,192 journals totalling 1.646 GB, which includes obsolete deltas and is not a measurement of live graph size.

The save path reserved 16 times the entire conversation's estimated graph before the encoder chose between a small append and a full checkpoint. Trusted prior state now admits changed records and current expiry markers first. The encoder expands that reservation before serializing a full checkpoint when state is missing/uncertain, records were removed, or the journal needs compaction. The existing factors, memory ceiling, forced writes and recovery data remain intact. This still reserves whole changed records rather than individual changed fields.

Three project transcripts were explicitly titled `splice-lead`: September 18–20, September 20–29, and September 29–October 14. Relevant operator requests and the October 13–14 performance handoff were reviewed. The latest transcript reports a red integration gate followed by fixes and a queued checkpoint; it explicitly says no optimized build was installed. See [the existing performance execution plan](request-performance.md) for that larger pending integration.

Local evidence retained for this investigation: `/tmp/splice-slow-20261014.jfr`, `/tmp/splice-threads-20261014.txt`, `/tmp/splice-registry-probe.log`, and `/tmp/splice-codemode-perf-final.log`. The JFR is local diagnostic data, not a repository artifact.
