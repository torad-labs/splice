# Instrumentation baseline receipts — 2026-11-05

This is **calibration evidence**, not capacity acceptance or a claim that the
instrumentation meets its overhead budgets. The candidate had not been measured
when [budgets.json](budgets.json) was fixed. Full W6 comparisons require longer,
controlled, matched repetitions after integration and enough warmup to establish
JIT/resource stability.

Unchanged source: `45cae7f4fcc3382ebc816fdfe52296aa8ce8a045`.

- Jar SHA256: `a45baf2631590cfeac33566b4f053b215eae1d4a66783a3495e5fd91ed264db6`.
- Launcher SHA256: `86a9ce3debe021bc4fed03c7e534f5c423275416bd6f670289c3db9e4aa6a218`.
- Artifact directory: `/tmp/splice-instrumentation-run/baseline-artifact`.
- Build: `./gradlew :app:shadowJar --no-daemon --max-workers=4`, clean detached
  source with a task Gradle cache, existing Bun dependencies reused, no install.
  `BUILD SUCCESSFUL in 4m 7s`, 93 tasks. The detached tree was removed afterward.
- Jar manifest: `Main-Class: splice.app.MainKt`, `Multi-Release: true`.
- Build log: `/tmp/splice-instrumentation-run/baseline-build.log`.

Every point below used **five repetitions**, each with 1 s warmup and 2 s steady
arrivals, an isolated JVM with 512 MiB maximum heap, and the same synthetic
Anthropic SSE fixture (1024 input bytes, four scripted output tokens, first wait
10 ms, chunk waits 5 ms). Completion rates refer to the steady arrival cohort
including its bounded drain. The unchanged artifact's legacy local collection
remained at its defaults; these are not “instrumentation disabled” results.

| Load | Completed | Generator dropped | Goodput/s | Completion p95 ms | Goodput repetition CV | Status |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Concurrency 1 | 331 | 0 | 33.1 | see JSON | 6.3% | Calibration complete |
| Concurrency 10 | 2948 | 0 | 294.8 | see JSON | 18.5% | High variance; no decision |
| Concurrency 50 | 4140 | 0 | 414.0 | see JSON | 13.7% | High variance; no decision |
| Concurrency 70 | 4098 | 0 | 409.8 | 233.3 | 6.4% | Calibration complete |
| Concurrency 100 | 2263 | 0 | 226.3 | 702.2 | 9.4% | Calibration complete |
| Offered 100/s | 1000 | 0 | 100.0 | 142.6 | 0.0% | Calibration complete |
| Offered 500/s | 2107 | 2893 | 199.3 | 1031.9 | 21.6% | Partial; generator cap reached |

The 500/s point retained all 5000 scheduled steady slots. It did not slow the
arrival schedule to conceal saturation. Its 128-request generator cap and some
objective misses prevent a capacity claim. A zero CV at 100/s means all offered
requests met the fixture objectives in each short repetition; it is not a
confidence claim about the maximum sustainable rate. There were no wire-invalid
or failed request outcomes in these seven calibration points.

[calibration.json](calibration.json) contains exact per-point identities,
environment, counts, intervals, tails, generator limits and SHA256 receipts.
Full samples/manifests/observations remain under each named `/tmp` run directory.
All manifests were explicitly annotated `experiment_stage=calibration` after the
orchestrator classified the short sweep; the amendment records the original
manifest digest and leaves every measured sample byte unchanged. The analyzer
rejects calibration as final budget evidence. This initial data used the harness
revision identified in its own manifest, before final adapter/integrity additions;
future comparisons must use one matched harness revision.

The shared host and co-located Bun mock/generator constrain these observations.
Queue delay, sink loss, GC/allocation, exclusive aggregate-cache/rollup growth and
isolated exporter CPU remain unavailable. Task-state endpoint sizes and process
RSS cannot establish the sustained-growth budget. New-collection, exporter and
JFR adapter behavior must be verified on the integrated candidate; this receipt
makes no candidate claim. Production Responses/WS, retained-history reads and
native/MCP execution have explicit adapter instructions in the
[harness README](../../../tools/instrumentation-bench/README.md).

No working daemon, paid provider, install, restart, push or release was used.
