# Inspecting Splice performance

Splice keeps performance observations locally. A default installation needs no
collector, metrics database or profiling agent. Prometheus, OTLP export and deep
profiling are optional. Performance objectives and alert delivery are opt-in.

The console's Performance panel is available from Usage and Requests. Start with
an explicit interval and cohort, inspect its coverage, then follow the same
filters into latency distributions, queue history, request timelines and traces.
Runtime readings describe the current process; they are labeled separately from
the selected historical interval.

## Available inspection surfaces

| Surface | What it answers |
| --- | --- |
| Summary and series | Arrival, completion, success, attempt, token, byte and event rates; latency distributions; observed concurrency and queues |
| Request and observation export | Which retained records match the cohort, with frozen pagination and completeness metadata |
| Diagnosis and timelines | Which measured stages overlap the slow interval; linked requests, attempts and tool work; the next measurement that could distinguish causes |
| Runtime | CPU, heap and nonheap memory, GC totals, allocation estimates, descriptors, threads and supported owner-specific counters |
| Profiles | Explicit bounded JFR captures and optional installed native-profiler captures |
| Objectives and alerts | Request-level threshold verdicts and sustained conditions with minimum count, coverage, hold and recovery rules |
| Saved baselines | Browser-local, content-free snapshots with workload and provenance compatibility information |
| Schema and doctor | Metric definitions, units, populations, missingness and the support-safe projection |

The machine interface uses a shared query contract. `splice perf --json` selects
machine output; the `summary`, `series`, `schema`, `diagnose`, `export` and
`profile` subcommands expose the corresponding operations. File-backed queries
remain available when the daemon is offline. Capturing a profile requires a
running process and a supported profiler.

The authenticated HTTP surface is hosted by the existing control plane:

| Operation | Route |
| --- | --- |
| Metric definitions | `GET /api/perf/schema` |
| Summary | `GET /api/perf/summary?contract=1` |
| Time series | `GET /api/perf/series` |
| Request inventory | `GET /api/perf/turns?contract=1` |
| Observation inventory | `GET /api/perf/observations` |
| Diagnosis | `GET /api/perf/diagnose` |
| Current runtime | `GET /api/perf/runtime` |
| Instrumentation budgets and sinks | `GET /api/perf/instrumentation/status` |
| Profile capabilities | `GET /api/perf/profiles/capabilities` |
| Profile list/start | `GET` / `POST /api/perf/profiles` |
| Profile status/stop/artifact | `GET /api/perf/profiles/:id`, `POST /api/perf/profiles/:id/stop`, `GET /api/perf/profiles/:id/artifact` |
| Objective configuration | `GET` / `PUT /api/perf/objectives` |
| Alert history/current state | `GET /api/perf/alerts`, `GET /api/perf/alerts/status` |
| Optional Prometheus scrape | `GET /api/perf/metrics` |
| Exporter state and accounting | `GET /api/perf/export/status` |

These routes retain the control plane's bearer and loopback protection. A
download uses the same authentication; tokens do not belong in artifact URLs.
The legacy summary and turns formats remain available without `contract=1`.
Unsupported selectors on a legacy format are rejected explicitly.

## Reading a result

Check the requested interval, filters, source and identity cohorts before
comparing numbers. Windows are half-open: `from` is included and `until` is
excluded. The default machine-query window is 24 hours; explicit windows include
1m, 5m, 15m, 1h, 5h, 24h and 7d. Five hours is exactly five hours.

Common selectors include head, model, transport, outcome, session, project,
account, local, compact and streaming. Named objectives add their configured
cohort. Conflicting filters are errors; unavailable evidence does not broaden a
project or session query into an all-request result.

Inspect `coverage`, `population_coverage`, metric-specific coverage,
`missing_fields`, `truncated` and `resolution_ms`. A known request can have a
valid objective verdict even when the surrounding interval is incomplete.
`known_good_per_s` is then a labeled lower bound; exact population goodput can
remain null. Empty, partial, stale, busy and unsupported are distinct states.

Request IDs, attempt IDs, tool IDs, trace IDs and legacy turn IDs serve different
purposes. Follow returned links; do not join them by spelling or assume that an
attempt or tool span is an additional client request. Nested durations overlap
and must not be summed into total latency.

## Optional capabilities

- [Measurement contract](measurement-contract.md): populations, timings,
  histograms, missingness, retention and memory ownership.
- [Exporters](exporters.md): Prometheus, OTLP protocols, credentials and collector
  examples. Export failures have their own accounting.
- [Objectives and alerts](objectives.md): configuration, lifecycle and delivery
  semantics. Installation creates no rules or outbound notifications.
- [Troubleshooting](troubleshooting.md): incomplete intervals, busy reads,
  profiles, stale exports and mismatched comparisons.
- [Benchmark evidence](../benchmarks/instrumentation-2026-11-05/README.md): artifact
  identities, measurement conditions and acceptance status. Calibration data is
  not an overhead or sustainable-capacity result.

Set `perf_collection_enabled` in `[defaults]` to control the new local collection
at startup. Disabling it preserves legacy performance records and configured
local traces. Exporter settings are also restart-required; the configuration API
reports this from knob metadata. A saved desired value does not claim that an
already running owner has applied it.
