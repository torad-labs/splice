// NEW: (JW-03, no Node source) the ONE head-tag convention. The per-head Logs panel and
// `splice logs --head` slice daemon.log on the literal `[<headKey>]` substring, but the
// highest-value producers used their own brackets — `[auth-probe:key]`, `[codex-auth]`,
// `[daemon] head 'key' ...` — so auth, refresh and boot diagnostics never reached the head's
// tail. Head-scoped sinks wrap here; the kt-head-log-prefix wall bans the legacy shapes.
// Named object since the 2026-08-16 style migration (HD-M8): the wrapper takes a sink and returns
// a sink, so there is no receiver type it could become a member of.
package splice.core.util

public object HeadScopedLogs {

    /** A sink that guarantees every line starts with `[<headKey>]` — lines already carrying the
     *  prefix pass through untouched, everything else (e.g. a provider's own `[codex-auth] ...`)
     *  gains it, so the per-head substring filter sees every head-scoped diagnostic. */
    public fun headScopedLog(headKey: String, sink: (String) -> Unit): (String) -> Unit {
        val prefix = "[$headKey]"
        return { line -> sink(if (line.startsWith(prefix)) line else "$prefix$line") }
    }
}
