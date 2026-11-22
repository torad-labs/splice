# Heavy local runs: what each one holds, and why

The runs below take real memory and CPU on a developer machine. For each one: the unit it works on, the peak it
reached on a run done in the normal course of work, and why it reaches that peak. The numbers were read on a
16-core machine with about 120 GB of memory on Nov 18 and 19, 2026, each from a run that was going to happen anyway,
never from a run made to measure. When your run lands far from a number here, that is a finding: find the term that
grew, and update this page.

A peak here is the memory high-water mark of the run's own processes (cgroup `memory.peak`, or what systemd logs as
"memory peak" when the unit stops), not the machine's.

## Where gradle runs

With `rig` on PATH, the gradle slot sends a run to another of your machines (`tools/gate/src/lib/elsewhere.ts`): the
pre-push Gradle tier, `gate run`, and any build, test or check a `./gradlew` asks for, since the root wrapper runs every
one through the slot. A run goes to a machine of this one's system (a Linux machine's runs go to Linux machines), so
its verdict is the one it would have here. The fat jar is built there too, on any of your machines, and rig brings
`app/build/libs/app-all.jar` back once the build passes. When no machine has room, the slot asks again every 30 s for as long as it would wait for its lock
(`GRADLE_SLOT_WAIT_S`), then fails with 75; it never builds here in its place. A run stays here on CI, when a task makes
another file this machine reads, and when `SPLICE_GRADLE_HERE=1` is set for a run that needs this machine. Nothing
has to be set for a run to go: `rig` on PATH is what sends it.

The pre-push Gradle tier does not wait for a machine that is not there. The hook asks rig before the tier starts,
and when rig says no machine of this system exists or answers, the tier is not started and the hook sends the push
on its direct legs, saying that Gradle did not run (`PRE-PUSH: SENT, GRADLE NOT RUN`, and `sent:gradle-unrun` in the
timing record). The pull request's checks run the same suites on GitHub, and main takes nothing they have not
passed. A machine that is only busy is waited for.

## The pre-push gate

The pre-push hook judges the pushed sha in a build tree checked out clean from it (`bun tools/gate hook`). One push
runs at a time; another seat's push waits for the one ahead of it.

| Leg | Unit | Peak | Why |
|---|---|---|---|
| Direct legs | Chosen by what changed: gateRules, noPython, prTitle and attribution always; ktlint for changed Kotlin; the gate's own tests, the TypeScript typecheck and the dependency audit for a change under `tools/` | 1.1 GB for the hook and the legs it runs in its own process, with 81 s of CPU across nine legs; at most 245 MB for each Bun check it hands to a process of its own | The typecheck (13 to 33 s, by load) and the gate's tests (17 to 21 s) are the long legs and hold most of it; gateRules takes about 10 s. |
| Gradle tier | The modules the change reaches, and in `:app` only the test classes it reaches (`tools/gate/src/lib/test-impact.ts`), plus the law suites | 7,025 MB on a push that ran 309 `:app` test classes and the law suites (578 s); 8,973 MB for one pre-push daemon across the pushes it served on Nov 19 | Gradle runs the modules in parallel, so these JVMs overlap: the Gradle daemon (1.5 GB heap cap, about 1.4 GB resident while building), the Kotlin compile daemon (1.5 GB cap, about 1.9 GB resident), the `:app` tests in four JVMs of up to 2 GB each, and the architecture laws' JVM of up to 4 GB. |

Across 276 pushes, the Gradle tier took 514 s at the median and 1,290 s at the 90th percentile. `:app:test` ran on
204 of them, for 403 s on average: it is most of the tier whenever it runs, which is why the test-impact selector
runs only the `:app` classes a change can reach.

The pre-push tree keeps its own Gradle daemon (a marker in its JVM args makes it incompatible with the checkout's,
so the two don't stop each other), with Gradle's workers set to the idle cores, 2 to 6.

## The daemons between runs

| Process | Unit | Resident | Why |
|---|---|---|---|
| Gradle daemon, idle | One per build directory (the checkout, the pre-push tree), kept 30 minutes | 1.9 to 2.0 GB | It runs on G1 with a periodic collection, so it hands back the heap it isn't using: 805 MB committed and 241 MB used when read. The rest is code, metaspace and native memory. |
| Kotlin compile daemon, idle | One per compatible set of JVM args, shared by the builds that match it | 2.3 to 3.0 GB | With no collector named, the Kotlin Gradle plugin starts it on the Parallel collector, which never hands heap back: 1.38 GB of its 1.5 GB heap stayed committed with about 830 MB used. The gate now names G1 for it (`tools/gate/src/lib/slot.ts`). |

## Test suites

| Suite | Unit | Peak | Why |
|---|---|---|---|
| `:app:test` | The selected classes, split over four JVMs | Up to 2 GB per JVM, starting at 256 MB | 2 GB funds the 1000-stream load test's ceiling; a JVM grows toward it only when its classes need it (`build-logic/src/main/kotlin/splice.kotlin-common.gradle.kts`). |
| `:quality-architecture:test` | Every law, in one JVM, one after another | Live set over 2 GB; heap capped at 4 GB | Several laws read the whole tracked tree into memory at once (`quality/architecture/build.gradle.kts` records the GC log). Konsist keeps every declaration it hands out for the life of the JVM, so the laws now share one parse of each module (`LawScopes.kt`) instead of seven. |

## Installing a build

| Run | Unit | Peak | Why |
|---|---|---|---|
| `:app:shadowJar` in a build tree | The app and every module it packs | 1.7 GB, 79 s of CPU, 1 min 54 s | Compiled outputs come from the build cache when the pushed sha was already built, so the run is mostly packing a 214 MB jar. A cold tree compiles every module and costs far more. |
