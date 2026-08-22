// NEW: (wall kt-no-println, 2026-07-27) the process log sink, installed ONCE by Main and used as
// the DEFAULT for components that would otherwise fall back to bare stderr.
//
// WHY IT EXISTS. Daemon-side diagnostics used to be `System.err.println`, which reaches stderr
// ONLY. `/mgmt/logs` serves `daemon.log` (ControlServer.logsJson -> LogFileSource.tail), and
// `daemon.log` is written by Main.persistentLogger — so every one of those 13 lines was invisible
// through the API. The failure a reader most wants was the one they could not get. The wall could
// not see them either: `pattern: println($$$A)` matches a bare call, while `System.err.println` is
// a navigation_expression.
//
// WHY A PROCESS DEFAULT AND NOT PURE INJECTION. Injection is still the contract — every consumer
// takes `log: LogSink` (HD-21; was a raw `(String) -> Unit`) and tests pass their own capturing
// sink (CodexProviderTest does
// exactly that). This supplies only the DEFAULT. Threading the sink explicitly from Daemon through
// all twelve provider construction sites pushed Daemon past detekt's LargeClass ceiling, and
// "which class is too big" is a separate question from "where do diagnostics go". Defaulting here
// leaves Daemon untouched, so that question is decided on its own merits instead of being forced
// as a side effect of a logging fix.
//
// Install-once at process start, read-only after. Uninstalled (tests, library use) it is a NO-OP
// rather than stderr: a component that wants output says so by injecting a sink.
package splice.core.util

public object DaemonLog {
    @Volatile
    private var sink: LogSink = LogSink {}

    /** Called once from Main with the persistent logger (writes both stderr and daemon.log). */
    public fun install(logger: LogSink) {
        sink = logger
    }

    /** Reference as `LogSink(DaemonLog::write)` to use the installed sink as a default parameter. */
    public fun write(message: String) {
        sink(message)
    }
}
