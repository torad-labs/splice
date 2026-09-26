// NEW: (split from DoctorCommand.kt, which sits at detekt's 14-function file budget) the doctor
// vocabulary — the types every section speaks in, plus the constants more than one section file
// names. CHECK_DAEMON, FIX_RESTART and FIX_LOGS MUST be internal rather than private: `private const
// val` is FILE-private in Kotlin, and after the split their readers live in several files
// (DoctorCommand.kt, DoctorDaemonChecks.kt, DoctorAuth.kt, AccountPools.kt). CHECK_TOPOLOGY has
// exactly one reader and stayed file-private, next to it, in DoctorConfigChecks.kt.
package splice.diagnostics.doctor

import splice.core.topology.Topology
import splice.daemonclient.DaemonProbe

internal const val CHECK_DAEMON = "daemon"
internal const val FIX_RESTART = "splice restart"
internal const val FIX_LOGS = "splice logs"

/** [fixId] names a fix the daemon can run itself (POST /api/doctor/fix/{id}); [fix] stays the
 *  sentence an operator reads, and a row whose remedy is theirs alone (edit a shell rc, fix access,
 *  move a foreign file) carries no id. */
internal data class DoctorCheck(
    val name: String,
    val status: CheckStatus,
    val detail: String,
    val fix: String? = null,
    val fixId: DoctorFix? = null,
)

/** V4-220 item 4: the fixes the console may ask the daemon to run, each one the CLI verb it runs.
 *  Only a verb that is safe unattended belongs here — it creates or relinks splice's own files and
 *  refuses rather than replacing anything else. */
public enum class DoctorFix(public val wire: String) {
    /** `splice install --all`: links every head's wrapper and `splice` onto the installed shim. */
    INSTALL_ALL("install_all"),
}

/** Resolved control port + the version the listener there reports (null = nothing answering).
 *  Computed ONCE in doctor() and threaded into both the daemon and auth sections so the port is
 *  resolved a single time and /health is probed a single time (was: twice each). */
/** JW-02 payload shape now lives on DaemonProbe (concentration, 2026-08-19). The CLI
 *  name stays so same-package FQCN and the one test import do not churn. */
internal typealias HealthView = DaemonProbe.HealthView

/** V4-230: [probe] is what /health found, each case its own; a daemon too slow to answer is not a
 *  stopped one. */
internal data class DaemonSnapshot(val port: Int, val probe: DaemonProbe.HealthProbe) {
    val health: HealthView? get() = (probe as? DaemonProbe.HealthProbe.Up)?.view
    val healthVersion: String? get() = health?.version

    /** A daemon holds the port: it answered, or it accepted the probe and was too slow to answer. */
    val running: Boolean get() = probe is DaemonProbe.HealthProbe.Up || probe is DaemonProbe.HealthProbe.Slow

    /** Why the daemon's other reads are not asked, or null when /health answered and they are. */
    val unanswered: String? get() = when (probe) {
        is DaemonProbe.HealthProbe.Up -> null
        DaemonProbe.HealthProbe.Down -> "daemon stopped"
        is DaemonProbe.HealthProbe.Slow -> "daemon slow to answer: /health waited ${probe.waitedMs}ms"
        is DaemonProbe.HealthProbe.Odd -> "the listener on :$port is not splice's daemon: ${probe.detail}"
    }
}

/** The topology as doctor sees it: not written yet, readable, or broken (with the parse error). */
internal sealed class DoctorTopology {
    data object Absent : DoctorTopology()
    data class Parsed(val topology: Topology) : DoctorTopology()
    data class Broken(val message: String) : DoctorTopology()
}
