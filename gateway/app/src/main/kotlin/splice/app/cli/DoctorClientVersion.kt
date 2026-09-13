package splice.app.cli

/** Projects the daemon's aggregate Claude Code drift warning into one doctor row. */
internal class DoctorClientVersion {
    internal fun check(health: HealthView?): DoctorCheck? = health?.clientVersionWarning?.let { warning ->
        DoctorCheck("Claude Code", CheckStatus.WARN, warning)
    }
}
