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

Source provenance starts at the trusted external freeze: the operator builds a
clean, identified checkout and retains its build command, successful exit and
artifact hashes independently of the benchmark run. The adjacent `identity.json`
is that freeze receipt, not a signature or proof reconstructed from JAR bytes.
The harness checks consistency and detects replacement against the frozen
manifest; it does not authenticate a malicious local rewrite of both artifact
and receipt. `process_build=source:<sha>` is the freeze's source label. Candidate
runtime identities are captured separately from the application's responses.

Runtime, instrumentation-status, and profile responses declare their wire schema
in the enclosing `schema_version`. The harness requires numeric version `1` there
before normalizing an omitted nested `identity.schemaVersion` to its serialized
default. An explicitly present nested version must also be numeric `1`; missing or
unsupported envelope versions and conflicting identity versions remain failures.

Proxy readiness requires Linux `/proc` visibility. Before even requesting health,
the harness intersects both listening socket inodes with the spawned child's file
descriptors, verifies its process start ticks and liveness, then rechecks ownership
after health. It also verifies the child's `-jar` path. Ownership is checked around
each workload phase and before polling. Missing platform support or unverifiable
ownership fails explicitly; an unrelated HTTP200 can never establish readiness.
Each desk records this process/socket evidence with the verified artifact and
configuration digests in `provenance.process_identities`.

Wildcard and non-loopback bindings are rejected before the first HTTP probe;
the receipt retains each socket's observed address. Both production connectors
bind `127.0.0.1` directly, so the task configuration changes only their ports.

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

## Storage between trials

Benchmark storage is separate from Splice's operational requirements. The old
task coordinator required 128 GiB free whenever a plan selected more than one
scenario. That was a fixed preflight policy, not a measured minimum or a formula
derived from the workload. Do not report it as a product requirement.

Run roles serially and archive each completed role's `desk-*` directories after
the runner and its entire process group have exited. Keep all warm repetitions
on their original desk until that role finishes. Compression, verification and
cleanup belong outside the measured intervals, with the same treatment for both
baseline and candidate. Production trace settings, offered load, repetitions and
measured disk totals stay unchanged.

Keep `manifest.json`, `receipt.json`, `samples.jsonl`, `observations.json`,
`summary.json`, profiles and the frozen artifacts directly available. The
analyzer uses these files; it does not need expanded desk directories. Archive
the remaining desk data losslessly and verify it before removing the originals.
Worker JAR copies may instead reference the retained frozen artifact only after
their actual bytes match its SHA-256. Recovery must restore those exact bytes.
Retain failed trials for diagnosis and stop before starting another trial when
archival or process cleanup fails.

This makes expanded storage depend on one active role run instead of every
completed run. Archives and measurement files still accumulate. Record their
actual sizes and keep the existing 64 GiB check before each proxy launch; do not
assume a compression ratio or claim that a fixed amount of free space is enough
for the entire matrix. Retained-state growth studies keep their state throughout
the study and are not subject to between-repetition cleanup.

`archiveRun()` and `verifyArchive()` in `archive.ts` implement this storage seam;
the coordinator supplies the exited runner's process group and the pinned
artifact. The archive receipt reports original desk bytes, compressed payload
bytes and bytes deduplicated against the frozen JAR separately. Compression does
not rewrite the run's disk measurements. `restoreArchive()` verifies the archive
and restores file contents from compressed payloads and the retained artifact.
Archives remain private task evidence, subject to the same sharing restrictions
as the original desk.

## Serial runner

The checked-in coordinator executes prepared steady manifests in the given order:

```sh
bun tools/instrumentation-bench/serial.ts \
  --manifest /tmp/splice-instrumentation-study/manifests/direct.json \
  --manifest /tmp/splice-instrumentation-study/manifests/baseline.json \
  --manifest /tmp/splice-instrumentation-study/manifests/candidate.json \
  --output /tmp/splice-instrumentation-study/batch-01
```

The output directory must be new. The coordinator freezes the manifests and
harness hashes, runs one full role at a time, waits for its process group to
exit, and verifies its desk archive before starting the next role. It does not
shorten workload settings or change tracing. Proxy launches retain the 64 GiB
free-space floor; direct controls require 1 GiB. Each child has a workload-derived
deadline, a log-growth check, and a 1 GiB emergency disk reserve. These checks can
stop a run; they never turn a partial run into a pass.

Every numbered job directory retains its input, capacity, process ownership,
execution, and measurement receipts. Archived proxy jobs also retain `storage.json`.
The `completed.json` status means only workload completion with analysis pending.
Analyze the baseline and candidate numbered run directories with `run.ts analyze`
afterward, retaining their matched direct control. Archives, profiles and
measurement files accumulate. Archive preflight reserves space for incompressible
payloads, compression metadata and a 1 GiB emergency reserve before writing.

A partial workload, timeout, changed harness, failed cleanup or archive error
stops the sequence with exit 2 and preserves its evidence. There is no automatic
resume or automatic acceptance of partial history queries. Inspect the stopped
job before preparing a new batch for the remaining manifests. Preserve the
schedule's alternating baseline-first/candidate-first order between matched
points. Separate retained-state and native/MCP fixtures keep their own protocols.

## Workload definitions

Use the complete `fixtures/steady.json` manifest as the starting point. The
fixture body is padded to exactly `input_bytes` and reused byte-for-byte for all
requests; its SHA256 is recorded. The proxy's own upstream serialization may
change the body. The passthrough fixture requires the exact client body. Responses checks the real
converted request shape and records HTTP/WS request counts; it does not compare
converted bytes with the Anthropic client input. A proxy transformation
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

Protocols: direct Anthropic SSE/JSON and Responses SSE/JSON/WS are implemented.
Proxy clients use Anthropic; optional `upstream={protocol:responses,transport:sse|websocket}`
selects the real OpenAiResponsesProvider and explicit isolated `websocket` quirk.
The production default stays off. Actual HTTP/WS counts are recorded; a requested
WS cohort with no observed upstream WebSocket is partial. `reconnect` closes every
third selected WS request with1011 before output, exercising production retry/fallback.
Anthropic fixture usage explicitly reports input/output/cache-read/cache-write/hourly
zero or positive values. Responses lacks supported write/hourly measurement fields,
so its measured-workload comparison correctly remains insufficient for that tuple.

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
be reported as a capacity pass. Scheduling-delay saturation alone also marks the
run partial and exits2, even if all arrival slots eventually complete.

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
GC/allocation and isolated exporter CPU remain null with reasons. Candidate sink
loss uses actual bounded, identity-checked counter deltas; unchanged-baseline
instrumentation counters remain unavailable without making that baseline partial.

CPU is a Linux process /proc tick delta, including completion drain, divided by
completed requests; RSS is an endpoint snapshot. Disk counts the task state tree.
These are whole-process/whole-state measurements, not allocation or exclusive
aggregate-cache ownership. Direct runs co-locate upstream and generator in Bun,
so daemon CPU is unavailable. Endpoint snapshots do not prove absence of sustained
memory/disk growth. Polling reports bounded loop count, failures, bytes and wall
time. Failed polling is not a successful collection comparison.

The analyzer requires the first run to have role `baseline` and the second
`candidate`, both using proxy artifacts. It rehashes each retained jar and its
build identity sidecar, verifies the recorded process/artifact/configuration
binding, and matches the normalized base configuration across both runs. Binary
identities are expected to differ; each must match its own verified binding.
Artifact files must remain available for this verification. Direct controls and
candidate-to-candidate studies are separate analyses, not aliases for this
unchanged-baseline comparison. Older calibration receipts without process
bindings remain calibration records and cannot acquire retrospective proof.
Invalid roles or identities return `checks=null`, never plausible budget results.

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
is fixed before candidate results. Missing candidate sink loss, controlled growth or CPU
measurements prevents an overall pass. A short run cannot establish sustainable
capacity; increase controlled duration and observe stable queues/resources/loss
at each independently offered rate before making that claim.

## Collection and integration adapters

The unchanged baseline runs `collection.mode=local` with its legacy defaults;
it has no new-collection toggle. Baseline minimal/export/profile requests are
rejected. Candidate minimal means **only new instrumentation disabled**, preserving
legacy perf rows and full configured traces. Candidate local enables new
instrumentation. Both require a candidate artifact with the pinned config
contract: `[defaults] perf_collection_enabled = "true"|"false"` (defaults store
string values; the knob coerces this to a Boolean). Optional exports use
`[telemetry.operator]` with `prometheus_enabled`, `otlp_endpoint`, `otlp_protocol`,
`otlp_headers` and `otlp_signals`. All are set before boot; no runtime setting is
assumed hot reloadable. Use the unchanged-baseline analyzer for baseline local versus candidate local
or minimal with otherwise matched cohorts. Candidate-to-candidate mode studies
need their own explicitly labelled analysis; they cannot occupy the baseline
argument of this CLI.

Candidate status uses guarded GET /api/perf/instrumentation/status, runtime, schema,
summary and optional export/status. Every sampler record binds artifact/PID/config
and canonical runtime identity. One-second time series include pool reservation,
work leases, named sink counters and isolated aggregate/evidence/cursor/profile disk
bytes. No new endpoint is polled on the unchanged baseline. Delta reconciliation
rejects changed identity, reset/missing/stale counters and unbracketed intervals.
Pending backlog prevents a no-loss pass. The analyzer recomputes deltas from points.
Pool reservation is not retained heap; whole-process RSS is recorded separately.
Actual retained-heap plateau proof uses the separate authorized fixture experiment below.

OTLP/HTTP collector decodes bounded protobuf metrics, spans and lifecycle logs,
validates service/scope identity, timestamps, IDs and histogram bucket totals, and
retains only bounded names/counts/time bounds. All requested signals must contain
records. Byte accounting alone cannot pass. collector_fault chooses healthy, slow
or unavailable with delay and active-request caps; no outside collector is used.
Gzip and gRPC are explicit unsupported collector capabilities, never guessed aliases.
Wire fields follow [OTLP metrics](https://raw.githubusercontent.com/open-telemetry/opentelemetry-proto/v1.5.0/opentelemetry/proto/metrics/v1/metrics.proto),
[traces](https://raw.githubusercontent.com/open-telemetry/opentelemetry-proto/v1.5.0/opentelemetry/proto/trace/v1/trace.proto),
and [logs](https://raw.githubusercontent.com/open-telemetry/opentelemetry-proto/v1.5.0/opentelemetry/proto/logs/v1/logs.proto).

Profiles start before warmup, use durationSeconds, verify terminal profile_id, PID,
identity and recording coverage, download at most64MiB, check JFR magic/size/hash and
parse jfr summary. Acceptance of POST is insufficient. Optional profile cost is a
separate cohort, outside the always-on overhead envelope.

The profile's boot, loaded build, version and schema must match the actual
identity sampled by instrumentation telemetry before warmup and after steady
work. Its receipt retains those runtime identities, the frozen JAR/source,
process start ticks and configuration-file hash. Profile and runtime configuration
revisions remain separately reported: a profile can legitimately declare an
unknown configuration scope; that does not excuse a different boot or build.

Retained history uses the test-only BenchmarkFixtureSeed entry, real
PerfAggregateStore/RequestEvidenceFiles writers, injected clocks and their actual
coverage receipt. The fixture records producer class hashes and every durable file
hash. retained_history supplies its directory/receipt_sha256/source_commit plus
`query_window_ms` (86400000 or604800000) and `query_source` (history or evidence).
The desk verifies and copies only those task-owned files before launch. Four
separate cohorts select one source/window each, with five fresh processes per
cohort. The first population query is `first_query_in_process`, followed by one
`repeat_query`; no other history window warms that process first. Concurrent
polling, profiling and exports are disabled for these query-cost cohorts.
Queries retain the fixture's explicit24h/7d bounds, first/repeat-query cost,
counts, coverage and resolution, before workload warmup generates new traffic.
Each query brackets actual `sources.aggregate` and `sources.evidence` counters;
reset or unavailable counters yield no delta. `retainedBytes` describes the shared
work pool, and concurrent source work can contribute to the bracket.
First query is process/query-cache cold; OS filesystem cache is uncontrolled.
The helper never fabricates a durable coverage ledger. Aggregate and evidence
fixture populations are separate and identified as such.

History observations retain a bounded, validated projection of the returned
identity cohorts. These describe persisted data and need not include the process
serving the query. Invalid or truncated identities cannot establish provenance.
The live identity is available in the existing runtime telemetry sample; bind it
to the same repetition and verified process, start time, artifact and build before
comparing it with the verified seed. Preserve the raw configuration digest as
separate provenance: the runtime policy revision and normalized TOML digest hash
different inputs and must not be compared for equality. The desk's `unknown`
boot placeholder is not identity evidence. Boot/build/configuration transition
issues remain visible, and the default history-study verdict still requires
complete coverage.

Aggregate series reconcile48 histogram observations per fixture minute. Evidence
summaries reconcile one request arrival per fixture minute and retain their own
population completeness, receipt as-of, coverage basis and work counters.
Missing counts remain null; matching a known partial count is not a complete
population pass. Bounded partial seven-day evidence summaries remain explicit;
complete paginated observation export is a separate functional acceptance check.

`summary.history` retains every query row and recomputes the study verdict from
the requested source/window, independent expected population, complete coverage,
source as-of and work-cost receipts, and fresh process bindings. A query refusal,
error, unknown coverage or population mismatch marks the study and overall run
partial, with CLI exit2. A bounded partial response remains useful evidence but
cannot establish full history-query success. The harness records all requested
query repetitions before evaluating this verdict; other execution failures still
stop the run normally.

Prepare the four manifests with `materializeHistory(point, artifact, seed)` from
`schedule.ts`. `artifact` is the frozen `{path, source_commit, sha256}` reference;
`seed` is the independently verified `{directory, receipt_sha256, source_commit}`
reference. The helper copies the exact source/window selectors from each history
point and validates the cold/local configuration without starting a process:

```ts
import { finalSchedule, materializeHistory } from './tools/instrumentation-bench/schedule';
const manifests = finalSchedule().points
  .filter(point => point.cohort === 'candidate-history-study')
  .map(point => materializeHistory(point, artifact, seed));
```

The helper consumes trusted preparation references; `installHistory` still
rehashes the seed receipt and all declared files before each launch. Native/MCP
and retained-state points keep their separate entry prerequisites and cannot be
materialized by this helper.

BenchmarkExecutionFixture is a separately launched compiled app test entry that
invokes NativeCodeModeRuntime and McpHost with the deterministic FakeMcpServer
stdio child. It verifies actual returned values, child parent/PID/start/executable
identity, loaded-class hashes, native callback and MCP observations. It requires
task-specific user.home/java.io.tmpdir and records five fresh hosts per kind, each
with a cold and warm invocation. The app already depends on the MCP module's
published testFixtures; the runtime receipt includes FakeMcpServer's class hash.
It is production execution evidence, not unchanged-baseline causal overhead.
Tool argument frames in tool-heavy remain only a wire workload. Neither test-only
entry has an automatic benchmark test or launches during compilation. Root owns
the integrated build and authorizes execution of the compiled entries.

## Separate retained-state experiment

`BenchmarkRetainedFixture` owns the actual accumulator, aggregate store, evidence
writer/source, query cache and cursor paths. Virtual clocks advance one request
per minute through36days of fill, then five equal24hour steps. Each checkpoint
waits for the real writer lanes and exercises24hour series reads and a cursor.
Only synthetic segment mtimes are aligned with the injected producer clock because
production evidence retention uses mtime; payloads and coverage ledgers are never
rewritten. Both modes hold a5.5MiB shared-pool lease to represent other ordinary
application work. This reservation is accounting pressure, not a heap allocation.
Optional active exporter, profiler and alert owners are outside this experiment.

`retained.ts` runs five fresh minimal/local pairs serially and alternates their
order. Producers are paused for every probe. Before `jcmd GC.run` and
`GC.class_histogram`, it checks its child's PID, start ticks, exact command line
and executable; the candidate jar and fixture classes must match retained hashes.
It primes diagnostic classes before the measured post-fill points, bounds probe
time/output, hashes each histogram and validates its Total against all class rows.
Full-GC probes have substantial cost and run outside inference timing; see the
[JDK diagnostic command reference](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html).
No heap dump or installed JVM is attached.

The comparator uses repeated actual whole-JVM live shallow-heap totals, paired
local-minus-minimal differences, pool accounting and aggregate/evidence/cursor
disk sizes. The difference estimates extra retention; it cannot attribute shared
objects exactly to an owner. Five independent fresh pairs supply95%t intervals;
the five dependent points within a JVM supply its equal-work slope. Positive
growth fails, uncertain growth is inconclusive, and the fixed growth budget stays
zero. Missing points, loss, refused reads, reused controls or missing disk counters
invalidate evidence. The32MiB memory and256MiB rollup disk caps remain fixed.

After the integrated gate, retain only the compiled `BenchmarkRetained*.class`
files with their package paths under a new task directory. This driver puts the
frozen candidate jar first on the classpath, then those fixture classes; no second
build is required. Compute class receipts without launching a JVM:

    bun tools/instrumentation-bench/retained.ts classes /tmp/splice-instrumentation-.../classes
    bun tools/instrumentation-bench/retained.ts plan

The retained manifest contains schema_version1, source_commit, artifact_sha256,
artifact_path, fixture_classes_root, fixture_classes_sha256 and the exact protocol
printed by `plan`. Only after root authorizes the frozen candidate, run:

    bun tools/instrumentation-bench/retained.ts run manifest.json /tmp/splice-instrumentation-.../retained <frozen-source-sha>

Attach its directory to `run.ts analyze` with `--retained-study`. Analysis rehashes
the jar, classes, histogram and receipt files and matches hardware/OS/JVM with the
candidate timing study. This can supply scoped growth evidence; it cannot turn an
unstable experiment into a pass or establish optional-owner growth coverage.

Generate the final preparation schedule without starting workloads:

    bun tools/instrumentation-bench/schedule.ts

It declares34 points, including closed1/10/50/70/100, open100/250/400/500 and four
source/window history-query cohorts, with five repetitions, matched direct
controls and representative secondary cohorts. Normal defaults are
20s warmup/60s steady; initial open sweep uses10s/30s and must be extended if
unstable. The upper open point is not assumed saturated. Materialize with frozen
artifact receipts, then execute serially after root authorization. The final
iteration envelope is bounded at500000; reaching it remains partial. Candidate
telemetry is capped at10000 points. No confidence or performance budget is relaxed.

No Gradle suite, install, restart, push or release command is part of this harness.
Artifact creation is a separately authorized build of unchanged source; tests
and benchmarks run in the background under repository conduct rules.
