# Parallel CI: fast, complete feedback

## Goal

For contributors, CI should finish the required verification sooner by starting independent work together, balancing JVM tests, and keeping advisory full coverage off the merge-critical path. A green `gate` must still mean every selected obligation completed on the exact candidate; main verifies the whole repository. Speed must never come from omitting affected App or test-fixture consumers.

## Scope

- Correct PR affected-project selection from declared production and test/fixture dependencies.
- Independent source, tooling, browser, architecture, native and packaged checks.
- Four JVM workers initially, with explicit partition ownership and complete result reconciliation.
- Independently sharded full instrumented coverage and report collection without test reruns.
- Preserve macOS, CodeQL, Docker, required check names, fork trust, main artifacts and fresh release rehearsal.
- No branch protection, version, release publication, local installation or restart changes.

## Execution and ownership

`bun tools/gate ci plan [base]` asks Gradle for the selected task graph in dry-run mode. It assigns executable verification boundaries and any otherwise-unowned terminal action, retaining the transitive inputs of every selected action. The plan records the candidate SHA and a content digest. It is not a compile-all producer: workers build their own prerequisite closure.

`ci worker <id>` runs its owned tasks and excludes verification boundaries assigned to other workers—not compiler/resource prerequisites. The collector requires those other workers' successful receipts. Four JVM workers receive duration-balanced whole test tasks; App and Turns ordinary tests are also split by compiled top-level class. Each shard preserves the task's existing includes/excludes and emits its complete candidate-class set and owned subset. Specialized tasks (packaged, architecture, laws, native) retain their behavior. Seed timings come from the successful PR476 and main42019952179 profiles; unknown tasks/classes receive a deterministic fallback, never get dropped.

`ci collect <directory>` rejects wrong candidates, wrong plans, duplicate/missing workers and incomplete class-shard unions, validates report/product hashes, and reconstructs the source-relative report tree. On full main it runs the existing `verifyTestDiscovery` implementation against the checked same-run producer outputs, without repeating the test producers. Main product retention happens only after this succeeds and macOS is green. Build-logic remains an included-build obligation. The full-main release rehearsal runs independently in its own runner and joins the verdict; it publishes nothing.

Local pre-push and release entry points remain unchanged apart from the corrected CI selector used by `run --since`. Main workers refuse to clean the shared local checkout. The tiny Gradle adapter integration fixture runs through the normal slot, not a direct Gradle binary.

## Startup overhead

For contributors, the PR gate should avoid downloading history and Gradle state that its job never reads. PR planning must still select the exact changes against the base parent of the tested GitHub merge candidate, including deletions and changes from long-lived or fork branches. The planner validates the event's head SHA against the merge's second parent and selects against its actual first parent; the event's base SHA can be stale after main advances. It refuses incomplete or mismatched history. History-dependent workers retain full history. PR receipt collection uses only Bun; main still sets up Gradle and runs full test discovery. Acceptance is unchanged verification with a shorter measured hosted critical path, not an assumed timing reduction.

## Measured balancing

The next latency target is a successful full-scope PR gate below ten minutes on the existing hosted runners. Rebalance the four JVM workers with task and class durations from run 42130624066 rather than the initial sparse seeds. Known compiled helper classes without test results carry zero test cost but remain assigned exactly once; new classes retain a nonzero fallback. Calibration must cover every JVM-owned task and every App/Turns compiled candidate from the same run, reject incomplete inputs, and preserve all specialized suites. Measured task durations overlap under Gradle parallelism and are scheduling weights, not additive wall-time savings.

Current calibration: [CI run 42130624066](https://github.com/torad-labs/splice/actions/runs/42130624066), tested merge candidate `db1564bb93d32221f29a9494b69ccdf80cbb65f6`. The four `gate-timings-1-jvm-*` artifacts provide 38 distinct JVM-owned task durations and JUnit XML. The candidate-bound `ci-receipt-1-jvm-0` class manifests enumerate 652 App and 570 Turns top-level compiled classes; all 1,222 are weighted, including 685 zero-cost entries. Nested-class suite times are charged to their top-level owner. App/Turns task weights sum their four observed shards; every other owned task is measured once. The previous 14-task/six-class seed table did not describe this execution shape.

## Acceptance

The selected Gradle task closure and ladder are the obligation inventory, not a copied job list. Each executable verification obligation has a designated owner. Missing, duplicate, wrong-candidate or failed required receipts refuse a green gate. Empty scope is represented explicitly. Worker failures preserve results for diagnosis and cannot be disguised as unselected work. Full coverage keeps its current trigger scope and remains outside `gate`.

New selection, partition and collector behaviors have failing-first tests. Hosted PR/main timing will be compared with the saved baseline (PR476 gate18m03s; main42019952179 Linux27m34s); no speedup is claimed before actual runs. All task and test counts are derived from the source/build inventory.
