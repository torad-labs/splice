# Operator telemetry export

Local collection is on by default and remains self-contained. Prometheus and OTLP are optional, independent exports. No OTLP endpoint or an empty signal list constructs no network exporter: no discovery, probes or retries occur. Local request traces and legacy performance rows do not depend on external sampling or collector availability.

```toml
[defaults]
perf_collection_enabled = true

[telemetry.operator]
prometheus_enabled = false
otlp_endpoint = ""
otlp_protocol = "http/protobuf"
otlp_headers = "{}"
otlp_signals = ""
```

All six settings require restart. `perf_collection_enabled = false` disables the new observation, aggregate and runtime collectors; legacy rows and configured local traces remain enabled. The config API keeps its existing PATCH object shape, with canonical keys `perfCollectionEnabled`, `telemetryPrometheusEnabled`, `telemetryOtlpEndpoint`, `telemetryOtlpProtocol`, `telemetryOtlpHeaders`, and `telemetryOtlpSignals`. Restart metadata comes from the `Knob` registry. These settings are global and cannot be overridden by a head.

OTLP signals are a comma-separated subset of `metrics,traces,logs`. Headers are a JSON object encoded in a TOML string. Each value must be a string; newlines, invalid names, duplicate names ignoring case, transport-control headers, oversized values and unknown signals are refused. Endpoints require HTTP(S), a host and a valid port; credentials, query strings and fragments are refused. HTTP/protobuf appends `/v1/metrics`, `/v1/traces`, or `/v1/logs` to the configured base. gRPC accepts an origin without a signal path. Set bearer credentials in `otlp_headers`, not the URL. All header values and configured endpoints are masked in config responses, topology responses and exporter status. A masked topology edit preserves the stored secret; a config PATCH containing the display sentinel is refused.

The app mounts Prometheus behind the existing bearer and loopback guard. Its response media type is `text/plain; version=0.0.4; charset=utf-8`. The disabled route must return an explicit disabled/not-found answer. A scrape reads a cached in-memory projection: it never updates cumulative counters, scans history files or waits for a collector. The app owns the actual route and exporter lifecycle.

## Populations and units

The core metric registry is the only numeric allowlist. Stable metric names begin with `splice_`. Duration keys ending in `_ms` become `_seconds`; sums, min/max and bucket boundaries divide by 1,000, while counts and bucket populations are unchanged. For example, `arrival_to_terminal_ms` exports as `splice_arrival_to_terminal_seconds`; `input_tokens` exports as a cumulative OTLP sum and the Prometheus counter `splice_input_tokens_total`. Histograms preserve disjoint buckets in OTLP and accumulate those buckets for Prometheus `le` samples. Gauges export their latest observed value with its original timestamp, never the sum of repeated gauge observations.

The worker reconciles cumulative minute replacements. Re-reading the same snapshot adds nothing. Updated minutes contribute their difference, and expiry of a local minute never decrements exported totals. Cumulative state lasts for the exporter lifetime, which resets at process restart. Coverage says `cumulative_since_export_start`, records source issues, and counts time lost when source data expired between polls. It does not infer unobserved values. A snapshot from another boot/config identity is refused.

Coverage retains the source's requested interval and observed milliseconds, with `partial=true` when observed time is shorter even if the source supplies no issues. `sample_age_ms` and `source_age_ms` advance when status is read; `sampled=false` identifies unavailable coverage. At most 64 issue entries are grouped by approved code/source and counted, with omitted-entry and normalized-name counts reported separately. The live accumulator's finite issue names are preserved; unrecognized names become `unknown`, and free-form details are excluded. Coverage snapshots reserve the shared work budget; failed reservations keep the previous sample and increment `coverage_dropped`.

Each metric family accepts at most 256 complete dimension combinations, with 512 cumulative series and 2,048 retained minute checkpoints overall. Excess or invalid point reconciliations increment the export drop counter. Dimension families contain bounded head/model names, normalized transport/work/outcome, and boolean streaming/local/compact flags. They contain no request, session, account, project, tool, host-instance or trace IDs, URLs or exception strings. Invalid names normalize to unknown/absent. All retained checkpoints, queued events, active spans, conversion work and rendered snapshots reserve the shared `PerfWorkBudget`; memory pressure leaves explicit drops and keeps the previous scrape snapshot available.

## Traces and events

Request, attempt and tool spans preserve validated recorded trace/span/parent IDs. They end at recorded terminal observations, including failures and cancellation. Native tool spans are owned by the native SDK; `tool_native_span` prevents a duplicate observation span. Native exported names are fixed, all events and links are removed, status descriptions are removed, and attributes retain only registered finite numeric values. Vendor trace-state and arbitrary native resource attributes are removed. Unfinished/orphan spans and bounded queue losses are counted instead of inventing terminals.

Request arrivals precede context attachment. The exporter retains a bounded provisional arrival using its local request key, then adopts the recorded checkpoint context while keeping the original arrival time. A valid terminal can supply that context if the checkpoint was lost. Contextless terminals increment `trace_missing_context` and release the provisional start; no consumer creates replacement IDs. Standalone MCP tools retain the finite `mcp` transport and their recorded tool trace without creating an inference request lifecycle.

Logs are content-free observation events. The body and event name come from the fixed event-kind enum; numeric attributes use the same registry and conversions as metrics. Request/tool bodies, tool names/arguments/results, exception text and raw correlation IDs never enter the new exported signals. Local capture remains independent.

OpenTelemetry Java is pinned to **1.67.0**. The custom GenAI mapping is pinned to the **OpenTelemetry semantic conventions 1.37.0** meanings of input/output token usage and operation duration: `input_tokens`, `output_tokens`, and request/attempt durations retain their registry populations and use the `splice_*` namespace. No experimental prompt/completion/event-content conventions are enabled. The immutable metric adapters use the pinned SDK data factories, so SDK upgrades must run the conversion and HTTP/gRPC collector tests. References: [Java SDK](https://opentelemetry.io/docs/languages/java/sdk/), [OTLP endpoint semantics](https://opentelemetry.io/docs/languages/sdk-configuration/otlp-exporter/), [semantic conventions 1.37.0](https://github.com/open-telemetry/semantic-conventions/tree/v1.37.0/docs/gen-ai).

## Lifecycle and outage behavior

`PerfExports(config, identity, source)` owns its dedicated scheduled export lane, SDK exporters and bounded queue. The app fans observations into this component independently of local persistence, and injects `nativeSpans()` into the native runtime. That returned bridge acknowledges queue admission; exporter status reports actual network completion. It does not own shutdown of `PerfExports`.

Each SDK export uses an explicit policy of at most two attempts, 750 ms per attempt/connect timeout, and exponential retry backoff starting at 100 ms and capped at 200 ms (multiplier 2). The worker waits at most 2.5 seconds per signal for actual completion. The next five-second collection exports the latest cumulative metrics again; failed logs/spans are dropped and counted, so an unavailable collector cannot accumulate an unbounded retry backlog. `flush()` waits at most ten seconds; `close()` waits at most twenty seconds. Only worker code reads aggregate snapshots and performs network work. Record admission never waits for the collector. Status contains queue counts, per-signal accepted/exported/failed/dropped/pending counts, last successful exports, coverage and worker failures. Metric counts refer to exported metric families; log counts refer to events; trace counts refer to completed spans. Queue counters include span-start observations.

See [Collector](examples/collector.yaml), [Prometheus](examples/prometheus.yaml), and [Grafana](examples/grafana-datasource.yaml). Supply management and collector credentials through your operator secret store; examples contain none.
