// NEW: the recurring runtime ROLES, named (HD-21, wave 4a). The measured seam census found
// 246 raw function types in main sources spread over 49 distinct SHAPES but only a handful of
// ROLES — and three SHAPES alone account for 121 of them: `log: (String) -> Unit` (47),
// `envReader`/`env: (String) -> String?` (29 + 27, one role under two spellings) and
// `clock: () -> Long` (18). Every one of those was threaded by hand, module to module, as an
// anonymous transposable type.
//
// WHY NAMED PORTS AND NOT LAMBDAS. `(String) -> Unit` and `(String) -> String?` say nothing about
// what they are for, and the compiler will happily let a config reader be passed where a log sink
// was meant as long as the arities line up. Naming the role makes that mistake inexpressible, gives
// the seam a place to carry its KDoc, and gives a test something to say rather than `{ _ -> }`.
// That is no-lambda-seam's tenet, and it is the same move HD-19 made for Waiter/Ticker.
//
// WHY BY ROLE AND NEVER BY SHAPE. [LogSink] and [EnvReader] are shape-identical — both are
// `(String) -> ?`. They are NOT interchangeable, and collapsing them (or the repo's other
// `() -> Boolean` seams) into one shared supplier type would be strictly worse than the lambdas it
// replaced. One interface per role; a role with two spellings (`env` and `envReader`) is ONE
// interface. Shapes and roles do not correspond either way, so the count runs BOTH directions:
// two spellings collapse to one [EnvReader], and the one `() -> Long` shape splits into two —
// [WallClock] and [ElapsedClock]. Four types for three shapes.
//
// WHY THE CLOCK SHAPE IS TWO ROLES (review finding, 2026-08-17). Wave 4a's first cut named a single
// `Clock`, which made wall-epoch and monotonic-elapsed time transposable EXACTLY as the raw
// `() -> Long` it replaced — the failure mode the paragraph above says a named port must prevent.
// Measured, not assumed: of the 18 declarations, 9 read the system wall clock and 9 read the
// monotonic timebase, and the two are not substitutable in either direction. Put a monotonic
// reading where an epoch is required and [PerfStats]/[CompactStats] persist a `ts` that is not a
// real epoch and `MgmtKey.mintedAtMs` stops being comparable with anything else in the tree; put a
// wall reading where elapsed is required and an NTP step or a suspend/resume aborts a healthy turn
// or pins a gate slot forever, which is the incident [MonoClock] exists to have fixed. Two types
// make each mistake a compile error; one type made both of them silent.
//
// WHY `operator fun invoke`. The role is the TYPE, not the call syntax: `LogSink` is already
// non-transposable with `EnvReader` whatever its method is called. Spelling the single abstract
// method `invoke` keeps every one of the several hundred `log(...)` / `env(...)` / `clock()` call
// sites in the tree byte-identical, so this wave's diff is exactly the declarations and the wiring
// — the win is the named port and the testability, not a rename storm through the call graph.
//
// WHY :core. All four are pure kotlin-stdlib signatures, and :core is the one module every other
// module may reach (module law). HD-19's Waiter/Ticker went to :provider-spi instead because they
// are kotlinx.coroutines-typed and `production :core stays framework-free`; these carry no such
// dependency, so the concept lives at the bottom where its consumers already look.
//
// Production defaults are unchanged and are supplied at the same composition roots as before
// (`DaemonLog::write`, `System::getenv`, and the two clock defaults — `System::currentTimeMillis`
// for [WallClock], `MonoClock::nowMs` for [ElapsedClock] — which were never the same value and are
// now no longer the same TYPE), so a production run is byte-identical.
package splice.core.util

/**
 * Where a component writes its diagnostics.
 *
 * The daemon's sink is [DaemonLog], installed once by Main with the persistent logger that writes
 * BOTH stderr and `daemon.log` (which is what `/mgmt/logs` tails). Head-scoped components wrap it
 * through [HeadScopedLogs.headScopedLog] so every line carries its `[<headKey>]` prefix. A test
 * wires a collecting sink and asserts on the lines.
 *
 * Uninstalled it is a no-op, never stderr: a component that wants output says so by injecting one.
 */
public fun interface LogSink {
    public operator fun invoke(message: String)
}

/**
 * Reads one environment variable by name, or null when it is unset.
 *
 * Threaded rather than read globally so a caller can pin a hermetic environment — the very first CI
 * run of `splice install` failed on the runner's ambient `XDG_CONFIG_HOME`, and doctor/status must
 * be able to resolve ports without touching the real process environment. Production wires
 * `System::getenv`; the kt-no-system-getenv wall keeps every other site off the global.
 *
 * The two historical spellings `env` and `envReader` are this one role, and both now name this one
 * type: `InstallCommand.localBin(env)` already passed its `env` straight into
 * `InstallPaths(envReader = ...)`.
 */
public fun interface EnvReader {
    public operator fun invoke(name: String): String?
}

/**
 * Reads UNIX epoch milliseconds — a real point in calendar time, comparable across processes,
 * across restarts, and with timestamps this daemon did not produce.
 *
 * Production wires `System::currentTimeMillis` at every one of its 9 sites. Required wherever the
 * reading CROSSES A BOUNDARY and is therefore read by something that does not share this JVM's
 * arbitrary origin:
 *  - persisted — [splice.gateway.perf.PerfStats] and `CompactStats` write it as the `ts` of a JSONL
 *    row, `UsageHud` as `updated_at`, and both are read back after a restart and plotted;
 *  - minted — `MgmtKey.mintedAtMs`, which doctor/status compare against daemon uptime;
 *  - compared against a foreign epoch — the codex/grok/kimi providers weigh it against a token's
 *    `expires`/`exp` and against `Files.getLastModifiedTime`, neither of which this process authored.
 *
 * An [ElapsedClock] reading is NOT an epoch and must never be substituted here: it re-anchors once
 * per JVM start and thereafter tracks `System.nanoTime`, so after an NTP step or a suspend/resume
 * every value above would silently stop being true calendar time for the life of the process.
 *
 * The inverse substitution is the one [ElapsedClock] exists to prevent; read its KDoc before moving
 * a site between the two. Also distinct from HD-19's Waiter, which SPENDS time rather than reads it.
 *
 * Wave 4b resolved the two seams this KDoc left open. `nowEpochMs` (UpstreamClient.retryAfterMs) IS
 * this role in this unit and now names this type; `nowIso` is this role in a DIFFERENT unit and is
 * [WallClockIso], because an epoch-ms reading and an ISO-8601 rendering are not interchangeable at
 * a `put(...)` call however identical their origin.
 */
public fun interface WallClock {
    public operator fun invoke(): Long
}

/**
 * Reads wall time already rendered as an ISO-8601 instant — the same reading as [WallClock], in the
 * form a credential file stores it.
 *
 * Production wires `Instant.ofEpochMilli(System.currentTimeMillis()).toString()` at both of its
 * sites (codex and grok), each of which writes it as `last_refresh` in the persisted `auth.json`.
 * That is why it is wall and not [ElapsedClock]: the value outlives the process that wrote it and
 * is read by a human and by the next daemon.
 *
 * A SEPARATE type from [WallClock] rather than a `String` alias for it, on the evidence of the two
 * call sites: both are `put(FIELD_LAST_REFRESH, JsonPrimitive(nowIso()))`, where a [WallClock]
 * would also compile and would silently persist `1755400000000` where an operator, and every other
 * `last_refresh` ever written, expects `2026-08-17T…Z`. The unit is part of the role here.
 */
public fun interface WallClockIso {
    public operator fun invoke(): String
}

/**
 * Reads a MONOTONIC timebase in milliseconds. A single reading means nothing on its own; only the
 * difference between two readings does. For budgets, deadlines, watchdog caps and elapsed timings.
 *
 * Production wires [MonoClock.nowMs] at every one of its 9 sites, which advances only with monotonic
 * nano time — so a sleep/wake or NTP step cannot abort a healthy turn ([splice.spi.Watchdog]),
 * pin a gate slot forever (`InflightGate.Slot`), evict a live reasoning entry, or corrupt a turn's
 * latency row. That is the incident [MonoClock] was introduced for, and this type is what keeps a
 * wall reading from being wired back in by hand.
 *
 * A test wires a virtual clock it steps by hand, which is what turns a watchdog cap from a
 * wall-clock wait into an assertion.
 *
 * NEVER persist a reading, put one on the wire, or compare one against a file mtime or a token
 * expiry — its origin is arbitrary and per-process. That is [WallClock]'s role.
 */
public fun interface ElapsedClock {
    public operator fun invoke(): Long
}
