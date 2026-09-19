// NEW: v0.4.0 FEATURES.md §2 — the daemon's Claude Code version-drift warning as one doctor row.
package splice.app.cli

/** Projects the daemon's aggregate Claude Code drift warning into one doctor row. */
internal class DoctorClientVersion {
    internal fun check(health: HealthView?): DoctorCheck? = health?.clientVersionWarning?.let { warning ->
        DoctorCheck("Claude Code", CheckStatus.WARN, warning)
    }
}
