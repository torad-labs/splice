# Performance measurement contract

The metric registry is the authority for names, units, event eligibility and
projection policy. The schema endpoint exposes it to people and agents. Local,
support and export projections are separate allowlists. New telemetry contains
measurements and correlation metadata; it does not contain prompt bodies, tool
arguments, credentials or captured response content. Existing configured local
trace and wire capture retain their own behavior and retention policy.

## Populations and clocks

| Population | Boundary and interpretation |
| --- | --- |
| Client arrival | Entry into the request path, including refusal before a legacy turn row exists |
| Client terminal | Actual clean completion, failure, refusal or cancellation; terminal closure is idempotent |
| Upstream attempt | Independent attempt start and finish; retries and work surviving client closure remain separate |
| Tool invocation | Actual native/MCP invocation lifecycle, including work still pending after a caller timeout |
| Canonical usage | At most one accepted usage observation per request, attributed to its terminal scope and time even when received late |
| Gauge sample | A reading of a named owner or scope; it is not an event delta |

Elapsed timings use a monotonic clock. Wall timestamps select and correlate
intervals. Rates divide by the requested wall duration. Any observed-time rate
must be named separately. Missing collection time cannot shorten the denominator
silently.

Arrival can precede model classification. Aggregate event-time scope therefore
differs from request scope resolved at the terminal or latest checkpoint. The
query names both populations and the attribution used by each rate or metric.
It does not rewrite an unknown-model arrival into a later model's aggregate.

Canonical token accounting remains the billing source of truth. Delayed usage
does not create another request completion. Missing usage is unknown, not zero.
Standalone MCP tools do not become model arrivals or goodput candidates.

## Timing boundaries

Arrival-to-terminal includes admission and delivery work. First protocol progress,
first meaningful content, first reasoning, first text, first tool content and
first successful client write/flush are distinct boundaries. A heartbeat cannot
establish meaningful content or successful delivery.

Upstream gaps, downstream gaps, local read blocking and client write/flush time
have different owners. Independent attempt/tool spans may overlap or outlive the
client request. Borrowed permits retain their actual ownership. A nested stage's
elapsed time is not CPU time and is not additive to its parent.

The runtime sampler reports supported process/owner observations. Heap, RSS,
proportional memory and allocation estimates are different measurements. Shared
owners are deduplicated; overlapping memory estimates are not summed. Unsupported
virtual-thread counts, generic dispatcher delay or transport internals remain
unavailable with a reason. Splice's own sampler scheduling delay describes only
that owned worker.

The runtime sampler refreshes the single process-wide `AsyncFileIo` lane every
five seconds. `GET /api/perf/runtime` exposes its `runtime_file_io_*` measurements
alongside the existing generic executor readings. Counts use `count`; elapsed
values ending in `_ms` use milliseconds.
`accepted`, `completed`, `failed`, `failed_writes` and `dropped` are cumulative
since process start. `completed` means the task returned normally; a sink can
report a write failure within such a task. `failed` means the task threw, while
`failed_writes` counts explicitly reported write failures. These counts can
overlap and must not be added together. `dropped` counts refusals by the pending
cap or executor rejection.

`pending`, `running` and `scheduled` describe unfinished tasks, executing tasks
and delayed timers with positive remaining delay. `queued` is the derived
runnable queue, `max(pending - running - scheduled, 0)`. These are independently
read scalars, not an atomic accounting balance. `peak_pending` is the highest
observed pending count since process start. `queue_wait_ms` accumulates elapsed
wait at task start, excluding requested timer delay; `run_ms` accumulates elapsed
execution when a task finishes, including waits. Their `peak_` counterparts are
the largest individual durations since process start. Unfinished execution time
is not included. These are gauge snapshots of totals and peaks, not task timing
histograms, interval deltas or CPU durations.

The file lane occupies one of the sampler's existing eight owner slots, after
the JVM source and before registered owners. Owner truncation remains disclosed;
no additional sampling pool is created. Reads use the cached sample and its
`as_of_ms`/`age_ms`. Missing, disabled, failed or budget-refused sampling is
unknown, never a fabricated zero; failed owner reads retain known metric keys
with null values. Per-sink instrumentation status and legacy `async_io_drops`
admission samples retain their separate meanings. This runtime visibility does
not replace a candidate-linked JFR or native-profiler capture.

## Histograms, gauges and rates

Histogram snapshots are cumulative minute replacements, keyed by identity,
metric, scope and bucket start. Repeated snapshots replace that bucket; distinct
buckets merge counts, sums and histogram buckets. Percentiles are derived from
the merged distribution, never averaged across percentiles. Bucket quantiles
report their approximation and bounds; they are not exact raw-row quantiles.
Unaligned edge buckets are excluded with a visible coverage issue rather than
prorated into invented counts.

Gauge output names sample and scope counts, most recent scope reading, observed
maximum and time-weighted mean. Covered scope-milliseconds can overlap across
owners. They do not by themselves establish full interval coverage. Sampled
cumulative GC or runtime counters remain gauges unless an actual interval delta
is measured; repeated cumulative values cannot be summed into rates.

Goodput evaluates each request against an explicit objective revision. Known
GOOD/BAD results remain known when interval coverage is incomplete. Pending
requests and requests missing required evidence remain separate. Exact population
eligibility, fractions and rates require sufficient population evidence; known
counts and labeled lower-bound rates remain inspectable. A percentile below a
threshold does not prove individual objective success.

## Coverage, retention and bounded work

Collection coverage, retained readable coverage, metric support and completed
delivery are separate facts. A timestamped event proves that event; it cannot
prove uninterrupted collection between the earliest and latest records. Loss,
pending writes, unreadable files, conflicting observation identities, expiry,
restart and incomplete checkpoints remain visible. A coverage improvement must
come from producer/retention evidence, not from filling a missing value with zero.

A certified settled prefix describes accepted and retained observations as of
its receipt. It does not establish event-time finality: a later accepted usage or
other observation can carry an older event timestamp and revise a later query.
The settled horizon, requested interval and observation as-of are distinct.
Consumers can deliberately select a completed window; the service does not
silently shorten a supplied interval or change its rate denominator.

Request-evidence writes can finish before their coverage checkpoint is published.
The sampler normally publishes every five seconds; delayed work can extend that
cadence. A drained sink therefore does not prove query visibility. Inspect
`as_of_ms` and `evidence_pending_checkpoint`: a zero known count before the relevant
checkpoint is not evidence that no requests occurred. Repeat the query after the
checkpoint advances, keeping the requested interval unchanged.

New retained telemetry has a 256 MiB disk envelope: 176 MiB for aggregate history,
16 MiB for frozen cursor metadata and 64 MiB for request evidence. Aggregate
history keeps minute resolution for 24 hours and hourly history for older data
within 35 days, subject to the disk cap. Dense data can force earlier eviction;
the time horizons are not guaranteed retention at every cardinality. Reads can
coarsen further to fit bounded work; their reported
resolution and boundary coverage remain explicit. Legacy raw-row retention is a
separate existing policy.

Aggregate checkpoints write one bounded dirty-minute page at a time and visit
pending minutes fairly. A failed page stays dirty for retry; it is acknowledged
only after durable completion and only if its generation has not changed. As
other observations advance the timeline, gauge readings are integrated through
intervening quiet minutes. Each page uses the existing 2 MiB snapshot admission,
4 MiB pending-write cap, 8 MiB history-work reserve within the shared 16 MiB
budget, and 512 KiB file cap. Under the fixed cell cap, the oldest completed
durable minute may be evicted; this is surfaced as `retention_expired`, not
complete coverage. Shutdown drains pages under one shared five-second deadline
and at most 120 attempts. Any dirty pages still outstanding are recorded as
`checkpoint_pages_unflushed` with their count, and the checkpoint remains
unclean.

Evidence segments are bounded and retained globally across heads. Retention uses
write time because usage may arrive after its attributed event time. A prior
loss or expired segment is not repaired by a later healthy write. Aggregate
writer ownership, durable checkpoints and interrupted shutdown markers distinguish
clean persistence from uncertain restart intervals.

Cursors freeze physical prefixes before the first page and bind the query,
ordering and selected source set. Their lifetime is 30 minutes. Changed,
unreadable or expired source prefixes fail explicitly. Filters apply before page
counts. Observation exports count observations; legacy exports count rows. The
JSONL export's final metadata record states completeness, so a page count cannot
be mistaken for a complete request population.

The core observation pool and the shared read/export work pool each have a
16 MiB budget. Retained caches, intermediate work and escaped replies have
separate lifetimes. A caller releases a leased reply only after encoding or
reducing it; an HTTP scrape retains ownership through response delivery.
Concurrency and size limits return visible busy/partial states. These budgets
do not claim that the whole JVM uses only 32 MiB.

Live source queries and aggregate checkpoints coordinate their use of that work
pool. A checkpoint retains its full workspace reservation until accepted file
work finishes. A source query waits for its phase before taking an active HTTP
worker, within the existing admission bound and 15-second deadline. Cancellation
releases query ownership; it does not revoke ownership already transferred to
accepted persistence. Runtime and instrumentation-status reads do not acquire
the source-query phase, but still share the bounded HTTP admission pool.

Instrumentation status exposes `memory.work.accounting` alongside the existing
`leased_bytes` and unchanged 16 MiB `limit_bytes`. Its `leased_bytes` is the sum
of `unretained_reservation_bytes` and `retained_ticket_bytes`. The `kinds` object
splits those two phases across nine fixed acquisition categories: `query_reply`,
`query_cache`, `history_read`, `evidence_read`, `configuration`, `runtime_sample`,
`evidence_coverage`, `webhook_queue` and `other`. These labels identify acquisition
sites, not individual owners.

The snapshot's `basis` is `lease_accounting_not_heap`. Reservations can be
long-lived; retained tickets can include collected owners awaiting Cleaner.
`retained_ticket_liveness` therefore reports
`not_inspected_includes_pending_cleaner`: neither phase establishes owner
liveness or measured heap usage.

`memory.work.last_refusal` is null until an acquisition is refused. It then holds
one bounded, process-wide record: `requested_bytes`, `requested_kind` and a copied
`accounting` snapshot from that refusal. A later refusal replaces it; successful
acquisitions do not clear it. This is the latest refusal, not an event history.

Aggregate-history sink rejection details are in `sinks[]` on the guarded
`GET /api/perf/instrumentation/status` response, on the row whose `sink` is
`aggregate_history`. History-backed query responses also include sink status in
`coverage.sinks[]`. Each sink's `rejections` is null until a rejection; its
camel-case counters are `snapshotMemory`, `serializationSize`, `historyMemory`,
`phaseUnavailable`, `writerUnavailable`, `asyncEnqueue` and `unclassified`.
`last` contains the most recent reason (`snapshot_memory`, `serialization_size`,
`history_memory`, `phase_unavailable`, `writer_unavailable`, `async_enqueue` or
`unclassified`), its wall-clock `atEpochMs`, and optional byte fields. The
counters accumulate, but `last` is one record, not a rejection history.

For a rejected work-budget admission, `requestedBytes` is the amount that attempt
tried to add; on a lease transfer it is the marginal addition. `targetBytes` is
the total lease size sought (equal to `requestedBytes` for a direct acquisition).
`leasedBytes` is the process-wide lease-accounting total captured at that exact
failed admission. These are lease estimates, not heap measurements or owner
attribution. Byte fields are null when the rejection did not come from a budget
admission, such as serialization-size, phase, writer or async-enqueue rejection;
null means no relevant admission context was captured, not zero usage. A sink
rejection and its shared leased total do not by themselves identify which other
owner held those bytes or prove a particular competing operation caused it.

Summary queries consume certified evidence incrementally and retain a bounded
request reduction. Request partitions share one frozen inventory and one total
read-work budget. All observations for a request stay in the same partition,
including late usage and observations encountered out of order. A terminal
observation does not authorize discarding that request's earlier or later data.
Time bins can coarsen while preserving counts, sums and histogram buckets;
`resolution_ms` reports the resulting detail.

The raw observation inventory has a separate page limit; that limit does not
cap a summary's request population. A reduction that reaches its memory,
cardinality or work limit reports partial coverage rather than presenting the
retained subset as the complete interval.

## Provenance and comparison

Results carry boot, running-build, schema and effective-configuration identities.
Unknown identities remain unknown. Desired restart-required settings do not
change the identity of an owner that has not restarted. Hot changes create real
per-head configuration intervals.

The running build's `compiled-artifact-inputs-sha256` identity is embedded during
the build and remains attached to the loaded code when the installed path is
replaced. It hashes compiled inputs; it is not the final JAR's byte checksum.
Benchmark receipts retain both the embedded identity and the actual JAR SHA256.

The effective-knob comparison scope excludes credentials, headers and certain
paths/addresses. A matching revision does not prove matching provider topology,
remote environment or workload. Baselines must also inspect measured input,
output and cache facts, transport, objective, source, coverage and timing.
Missing facts produce insufficient evidence; incompatible known facts are
different from missing ones. Neither a ratio nor a matched cohort alone proves
a causal speedup.

`summary.comparison` reports measured workload readiness separately from that
external assurance. Its `exact_joint_frequency_v1` basis retains joint request
facts and their frequencies, including outcome, input/output/cache quantities and
history markers. Equal averages do not imply equal workloads. A `ready` result
means the measured workload and effective-knob metadata are usable for comparison;
it does not certify provider topology or remote environment equivalence.

Actual measurement presence is separate from billing's reporting policy. An
omitted or null cache field is not an explicitly measured zero, even when billing
uses a zero for accounting. Incremental updates preserve observed fields;
distinct combined rounds cannot hide a missing contributing measurement.
Absorbed or cut history cannot be expanded into invented per-request sizes.

Implementation and source-review receipts are not performance acceptance.
Capacity and overhead claims require matching built artifacts, stable repeated
workloads, actual wire correctness, explicit generator limits, uncertainty and
telemetry-loss evidence. The benchmark receipts identify which of those were
actually measured.
