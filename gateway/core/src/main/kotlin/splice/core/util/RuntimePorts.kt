// NEW: the three recurring runtime ROLES, named (HD-21, wave 4a). The measured seam census found
// 246 raw function types in main sources spread over 49 distinct SHAPES but only a handful of
// ROLES — and three roles alone account for 121 of them: `log: (String) -> Unit` (47),
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
// interface, which is why there are three types here and not four.
//
// WHY `operator fun invoke`. The role is the TYPE, not the call syntax: `LogSink` is already
// non-transposable with `EnvReader` whatever its method is called. Spelling the single abstract
// method `invoke` keeps every one of the several hundred `log(...)` / `env(...)` / `clock()` call
// sites in the tree byte-identical, so this wave's diff is exactly the declarations and the wiring
// — the win is the named port and the testability, not a rename storm through the call graph.
//
// WHY :core. All three are pure kotlin-stdlib signatures, and :core is the one module every other
// module may reach (module law). HD-19's Waiter/Ticker went to :provider-spi instead because they
// are kotlinx.coroutines-typed and `production :core stays framework-free`; these carry no such
// dependency, so the concept lives at the bottom where its consumers already look.
//
// Production defaults are unchanged and are supplied at the same composition roots as before
// (`DaemonLog::write`, `System::getenv`, `MonoClock::nowMs`), so a production run is byte-identical.
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
 * Reads the current time in milliseconds, for budgets, deadlines and elapsed measurements.
 *
 * Production wires [MonoClock.nowMs], which advances only with monotonic nano time — so a
 * sleep/wake or NTP step cannot abort a healthy turn or pin a gate slot forever. A test wires a
 * virtual clock it steps by hand, which is what turns a watchdog cap from a wall-clock wait into an
 * assertion.
 *
 * Distinct from HD-19's Waiter (which SPENDS time) and from the repo's `nowIso: () -> String` and
 * `nowEpochMs` seams (which report wall time for the wire, where an epoch is required and a
 * monotonic reading would be wrong). Same shape, different roles — deliberately not merged.
 */
public fun interface Clock {
    public operator fun invoke(): Long
}
