# Instrumentation capacity desk

A deterministic, content-free receipt harness for the unchanged source at
`45cae7f4fcc3382ebc816fdfe52296aa8ce8a045` and candidate artifacts. Run this from the
repository with Bun. It never starts or contacts the installed daemon. Its mock
upstream and optional jar process own ephemeral loopback ports. Only a jar under
`/tmp/splice-instrumentation-*` with a matching adjacent `identity.json` can be a
proxy target. Never put real credentials or real request/history content in this
harness.

```sh
bun tools/instrumentation-bench/run.ts --help
bun tools/instrumentation-bench/run.ts example > /tmp/splice-instrumentation-manifest.json
bun tools/instrumentation-bench/run.ts run \
  --manifest tools/instrumentation-bench/fixtures/steady.json \
  --output /tmp/splice-instrumentation-example/direct
bun tools/instrumentation-bench/run.ts run \
  --manifest tools/instrumentation-bench/fixtures/steady.json \
  --output /tmp/splice-instrumentation-example/plan --dry-run
bun tools/instrumentation-bench/run.ts analyze \
  --baseline /tmp/splice-instrumentation-example/baseline \
  --candidate /tmp/splice-instrumentation-example/candidate
```

Create the output's parent directory first. A run directory must not exist; a new
run never overwrites evidence. Dry-run writes the exact request hash, size, load
schedule, fixture delays, manifest and empty samples with `measured=false`. It
does not boot a process or claim evidence. `run` exits 2 for partial/wire-invalid
runs. `analyze` exits 2 for an invalid, failed **or inconclusive** comparison.

## Identity and isolation

The artifact receipt contains `source_commit` (full SHA), `artifact_sha256`,
`launcher_sha256`, `clean_source: true`, build command and exit status. The harness
hashes the actual jar before boot and compares the source and digest with that
receipt. Record source identity when building the artifact, independently of the
checkout used for the harness. Unknown process versions and process-reported
boot IDs remain `unknown`; a fresh desk is not evidence of a daemon-reported ID.
`process_build=source:<sha>` identifies the verified source, not `/health.version`.
The build's jar manifest, launcher and SHA256SUMS are separate artifacts.

The launcher uses an explicit child environment allowlist, `-Duser.home`,
`SPLICE_STATE_DIR`, `SPLICE_CONFIG`, and XDG directories under the new task desk.
It does not assign HOME or inherit provider/auth environment variables. The mock
provider uses synthetic client auth; there is no paid-provider traffic. Ports
3096/3099 and URLs with credentials, query or fragments are rejected. The JVM is
limited to 512 MiB heap. The captured configuration hash normalizes ephemeral
paths/ports so a new desk can reproduce the same configuration cohort.

Only manifest/sample/summary/observation/receipt files are shareable benchmark
receipts. The desk directory is an **ephemeral application state directory** and
can contain a synthetic management key and synthetic request traces. Do not
check it into Git or attach it to an issue. Receipt fields contain sizes, hashes,
status codes/classes, timings and identities, never request bodies, response
content, headers, tool inputs, or raw error text. Do not copy unrelated daemon
logs into receipts.

## Workload definitions

Use the complete `fixtures/steady.json` manifest as the starting point. The
fixture body is padded to exactly `input_bytes` and reused byte-for-byte for all
requests; its SHA256 is recorded. The proxy's own upstream serialization may
change the body. The fixture reports how many upstream bodies differ from the
client input; this is separate from client wire validity. A proxy transformation
must not be misrepresented as a harness rewrite or as proof of byte identity.

| Scenario | Manifest fields and interpretation |
| --- | --- |
| `steady` | Fixed first wait, chunk wait and count; each event/frame flushes independently. |
| `long` | Increase `first_wait_ms`, `chunk_wait_ms`, `chunks`, `output_tokens_target`, timeout and objectives together. |
| `bursty` | Every second eligible chunk adds three chunk waits; fixed deterministic schedule. |
| `large-history` | At least 100 `history_messages`, and `input_bytes` large enough to contain them. This exercises large request history. The retained on-disk history adapter below is a separate experiment. |
| `slow-client` | Set `slow_read_ms > 0`; pauses between body reads. Buffer coalescing is observable and is not claimed to establish socket-level backpressure by itself. |
| `tool-heavy` | Declares a synthetic tool and emits tool-only JSON argument deltas with a terminal tool stop. It exercises wire attribution, not execution of an MCP/native tool. |
| `retry` | Mock refuses every third upstream attempt with 429 and Retry-After: 0. The production proxy decides its existing retry policy. Direct runs report refusals. |
| `refusal` | Every request returns 429; accepted=false, refused rate rises and completion/goodput remain zero. |
| `cancel` | Set first wait greater than cancel_after_ms. The client aborts on schedule; it never fabricates a clean terminal. |

`cache_state=warm|cold` controls scripted upstream usage. Cold recreates the proxy
desk per repetition; warm retains it across repetitions. Neither means a real
provider cache was measured. `seed` fixes the declared workload identity; current
scripts intentionally use no randomness.

Protocols: direct Anthropic SSE/JSON and Responses SSE/JSON/WS are implemented;
proxy capture uses the real Anthropic passthrough path. Request streaming selects
`transport=sse` and `streaming=true`; nonstream uses `http`/false. Responses WS is
an explicitly selected **direct fixture transport**. The production Responses
websocket default stays off. The proxy Responses/WS adapter below is required for
that additional cohort; selecting it without an adapter is rejected.

`experiment_stage=calibration` is the default and cannot pass final budget analysis.
Set `experiment_stage=steady` only after selecting controlled timing: the harness
requires >=10 s warmup, >=30 s measurement, and >=5 repetitions; these minimums
do not themselves prove JIT stability. Every compared steady point needs >=5 repetitions. Start closed-concurrency
sweeps at **1, 10, 50, 70, 100**, with `max_inflight >= concurrency`. An open sweep
uses `load.kind=arrival_rate` and an independently scheduled `load.value` requests
per second. Every original arrival slot is retained: exceeding max_inflight
records `generator_dropped`, and late dispatch records generator_delay_ms. The
scheduler never changes the offered rate to wait for a free worker. Iteration,
request-timeout and wire-byte caps bound the generator. Exceeding any cap cannot
be reported as a capacity pass.

Warmup requests are retained with `phase=warmup` but excluded from steady
summaries. Each repetition drains with bounded per-request timeouts. A timed-out
request remains `pending` because server completion is unknown. Cancelled work
has its own outcome. `accepted` means successful response headers/WS handshake,
not unobserved internal admission. Unknown acceptance remains null.

## Measurement and comparison

`manifest.json` contains schema_version=1, exact artifact/source identity,
configuration, hardware/OS/JVM, load, fixtures, objective revision and fixed
budgets. `samples.jsonl` contains scheduled/start/first-content/terminal timestamps
on one monotonic run clock, outcome, wire/content hashes, volume, scripted terminal
usage and explicit missingness. Requests that lose text but retain a clean stop
and reported usage fail the content hash and cannot inflate throughput.

`summary.json` reports requested and observed intervals, rates for the steady
arrival cohort, count/coverage, objective goodput, first-content/completion tails,
generator delay and missed/dropped slots, bounded drain and per-repetition CPU/RSS
and disk snapshots. Completion/goodput rates use all bounded-drain outcomes of
steady arrivals divided by the requested offering duration. They are cohort
rates, not an assertion that every completion happened before the offering
window ended. Queue timing is null: generator lateness is not server queue delay.
GC/allocation, sink loss and isolated exporter CPU are null with reasons until the
instrumented adapters provide those measurements.

CPU is a Linux process /proc tick delta, including completion drain, divided by
completed requests; RSS is an endpoint snapshot. Disk counts the task state tree.
These are whole-process/whole-state measurements, not allocation or exclusive
aggregate-cache ownership. Direct runs co-locate upstream and generator in Bun,
so daemon CPU is unavailable. Endpoint snapshots do not prove absence of sustained
memory/disk growth. Polling reports bounded loop count, failures, bytes and wall
time. Failed polling is not a successful collection comparison.

The analyzer re-reads and checks the written receipt hashes. It matches workload,
objective, timing, JVM, hardware, transport and generator limits, then compares
wire/content hashes and outcomes. Direct and proxy runs remain separate cohorts;
use matched direct measurements to investigate added proxy time. End-to-end p95
differences are labelled as such, not presented as isolated proxy self-time.
Confidence intervals use a two-sided Student-t interval across repetition means,
not thousands of dependent requests treated as independent repetitions. The
worst endpoint combination is used for conservative budget decisions. Baseline
CV >10% makes the result invalid/inconclusive; increase controlled duration or
repetitions instead of relaxing budgets. Profiling is a separate cohort/envelope.

The checked-in [budget receipt](../../docs/benchmarks/instrumentation-2026-11-05/budgets.json)
is fixed before candidate results. Missing sink loss, exclusive growth or CPU
measurements prevents an overall pass. A short run cannot establish sustainable
capacity; increase controlled duration and observe stable queues/resources/loss
at each independently offered rate before making that claim.

## Collection and integration adapters

The unchanged baseline runs `collection.mode=local` with its legacy defaults;
it has no new-collection toggle. Baseline minimal/export/profile requests are
rejected. Candidate minimal means **only new instrumentation disabled**, preserving
legacy perf rows and full configured traces. Candidate local enables new
instrumentation. Both require a candidate artifact with the pinned config
contract: `[defaults] perf_collection_enabled = true|false`. Optional exports use
`[telemetry.operator]` with `prometheus_enabled`, `otlp_endpoint`, `otlp_protocol`,
`otlp_headers` and `otlp_signals`. All are set before boot; no runtime setting is
assumed hot reloadable. Compare candidate modes on otherwise matched cohorts to isolate new
collection overhead; compare unchanged local against candidate local for the
whole product change.

Optional candidate OTLP/HTTP collection targets a bounded, task-owned loopback
collector; payloads are discarded after byte accounting. No outside collector is
contacted. Candidate profiles use the authenticated JFR CPU profile API and stop
after the steady interval. Capture failures are partial. Profiles require a
separate measured overhead comparison and are excluded from the 5% always-on
budget. The collection contract must be exercised on the integrated candidate;
a request that is merely accepted is not proof that metrics/spans/profile samples
were produced. OTLP gRPC remains an external adapter capability, not an assumed
working alias for HTTP/protobuf.

The following integrations remain explicit adapters and **unmeasured** until
executed; they must not be relabelled as the direct fixtures above:

- **Production Responses/SSE/WS:** adapt the holding upstream in
  `features/turns/src/test/kotlin/splice/head/HeadServerLoadTest.kt`. Keep its real
  HeadServer/UpstreamClient path, use these fixture events and monotonic arrival
  records, and report actual transport. Preserve the existing 1000-stream gate;
  it is a correctness test, not a capacity result. Set Responses WS explicitly on
  the isolated adapter only; never alter the production default.
- **Retained history/cold reads:** reuse
  `app/src/test/kotlin/splice/app/sources/PerfHistoryProfile.kt` and
  `PerfHistoryScaleTest`. Seed synthetic retained rows in the desk only; record
  row count/window/day distribution and cold/warm OS-cache methodology. Export
  its JFR allocation/file-I/O evidence with process/build/cohort identity. A large
  request body is not a retained-history read benchmark.
- **Native/MCP tool execution:** wrap the existing native/MCP fixture with the
  same arrival/terminal contract and include actual execution attribution. Tool
  argument frames alone do not measure host startup, callbacks or pending replies.
- **Sibling Splice-Benchmarks/AIPerf:** use an adapter that emits this manifest and
  sample schema, preserves raw input bytes and real wire terminals, and supplies
  generator delay/missed slots. Do not call an OpenAI-compatible endpoint proof
  of Splice protocol support. No changes to the sibling repository are required.
- **Sink loss, exclusive growth and queue:** collect authenticated candidate
  counter snapshots around each steady interval and bind them to process/build,
  boot, config and measurement population. Reset/restart or missing snapshots
  produce null/partial, never zero loss. Attach server queue timing separately
  from generator delay. GC/allocation/profiler counters need capability evidence.

No Gradle suite, install, restart, push or release command is part of this harness.
Artifact creation is a separately authorized build of unchanged source; tests
and benchmarks run in the background under repository conduct rules.
