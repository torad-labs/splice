# Troubleshooting performance observations

Start with the response's status, requested interval, identity cohorts, coverage
issues and per-sink accounting. Keep the same filters when moving from summary
to series, diagnosis, request inventory and traces.

| Symptom | What to inspect |
| --- | --- |
| Exact goodput is null but known GOOD requests exist | Whole-window request coverage, missing usage, pending requests and required objective measurements. Known results do not prove a complete population. |
| A project/session query is unavailable | Exact retained evidence and reference mappings. Aggregate all-request data cannot substitute for that cohort. |
| A query returns busy | Current read/export/profile ownership and concurrent work. Retry after existing work settles; a busy result is not zero traffic. |
| A historical interval is partial | Retention, cursor expiry, unreadable segments, restart uncertainty, checkpoint age, limits and unaligned aggregate boundary buckets. |
| Model arrival and completion counts differ | Event-time versus terminal-resolved scope, window boundaries, refusals, pending work and retries. |
| A gauge total seems too large | Scope count and aggregation semantics. Covered scope time and overlapping memory estimates are not disjoint process totals. |
| An exported result is stale | Last successful sample/export time, source horizon, queue admission, render limits and actual delivery failures. |
| An alert stays active during a data gap | Missing data cannot establish healthy recovery. Inspect its reason, count, coverage and last known incident. |
| Saved baselines cannot be matched | Missing workload facts, mixed configuration, different objective revisions, topology scope and incomplete coverage. Do not infer partitions from average token sizes. |

## Sink accounting

Queue acceptance and durable completion are separate. File sinks expose observed,
enqueued, written, failed, dropped and pending values, plus pending age and last
successful write time. A failed write does not count as written. An accepted
export or alert operation does not count as delivered until its transport reports
the actual outcome.

`/api/perf/instrumentation/status` reports the observation and work pools' actual
reservation accounting alongside sink counters. Those values describe accounted
owners and leases, not measured heap or RSS. Unsupported per-owner breakdowns
remain null with a reason. Combine the endpoint with runtime and attributable
retained-store observations when investigating instrumentation cost.

Do not subtract unrelated boot counters. Compare deltas only across a known
continuous identity and interval. Cumulative snapshot values without an interval
delta cannot establish a telemetry-loss fraction. Cancellation and fatal failures
retain their normal propagation; ordinary observation failures have bounded,
visible accounting.

## Profiles

Read `/api/perf/profiles/capabilities` before selecting a capture. Creating a
manager starts no recording. A start request selects `profiler`, `mode` and
`durationSeconds`; the default duration is 60 seconds and the maximum is 300.
Only one managed capture can be active per process/store.

JFR exposes explicit CPU, allocation and lock modes using a limited event set.
Unsupported modes remain unavailable. Native profiling requires an already
installed supported executable discovered by the daemon; Splice does not download
it, invoke a shell installer or request elevated privileges. Capability probing
does not imply that a later capture succeeded.

Completed artifacts are limited to 64 MiB; the separate profile store is limited
to 256 MiB, 32 metadata records and a one-hour lifetime. Inspect the returned
actual recording interval, terminal state and reason before interpreting a file.
Aborted, oversized, unavailable or failed captures are not completed profiles.
If an uninterruptible profiler worker cannot be stopped, its admission remains
quarantined rather than claiming the underlying work was killed.

A profile measures a selected process interval. Match it to the request/query
window and running identity. Profiling overhead is evaluated separately from the
default collection budget.

## Exporters and alerts

Without enabled export settings there is no collector traffic. The Prometheus
endpoint serves a complete bounded snapshot or an explicit unavailable response.
It does not return a truncated family or an empty healthy scrape after rendering
fails. A scrape older than 15 seconds is unavailable as stale. See
[exporter configuration and limits](exporters.md).

Alert rules start empty and delivery is disabled by default. Performance delivery
requires both the rule opt-in and existing notification preferences. Browser
desktop notifications also require browser permission and deduplicate by the
returned incident key. Changed settings can disable queued delivery; an HTTP
operation already in flight retains its actual outcome.

Inspect the event's settings/objective revision, measured population, hold,
recovery and continuity fields. Late evaluations do not rewrite previously sent
incidents. Restart preserves known firing identity while resetting unproven
pending/recovery continuity. See [objective and alert semantics](objectives.md).

## Offline inspection and compatibility

File-backed machine queries remain useful without a running daemon. Current
runtime, live gates, capture controls and unpersisted collection intervals are
unavailable offline. Missing evidence is explicit; an offline command does not
start provider traffic to fill it.

Existing text CLI output, legacy API roots and legacy row fields retain their
meanings. Use the explicit new contract for shared machine filters and coverage.
New instrumentation does not change prompt construction, replay, canonical billing,
upstream permit limits or configured local trace fidelity.
