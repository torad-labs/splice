// NEW: (split from DoctorCommand.kt, which sits at detekt's 14-function file budget) the doctor
// vocabulary — the types every section speaks in, plus the two constants more than one section
// file names. CHECK_DAEMON and FIX_RESTART MUST be internal rather than private: `private const val`
// is FILE-private in Kotlin, and after the split their readers live in three files (DoctorCommand.kt,
// DoctorDaemonChecks.kt, DoctorAuth.kt). CHECK_TOPOLOGY has exactly one reader and stayed
// file-private, next to it, in DoctorConfigChecks.kt.
package splice.app.cli

import splice.app.DaemonProbe
import splice.core.topology.Topology

internal const val CHECK_DAEMON = "daemon"
internal const val FIX_RESTART = "splice restart"

internal data class DoctorCheck(
    val name: String,
    val status: CheckStatus,
    val detail: String,
    val fix: String? = null,
)

/** Resolved control port + the version the listener there reports (null = nothing answering).
 *  Computed ONCE in doctor() and threaded into both the daemon and auth sections so the port is
 *  resolved a single time and /health is probed a single time (was: twice each). */
/** JW-02 payload shape now lives on DaemonProbe (concentration, 2026-08-19). The CLI
 *  name stays so same-package FQCN and the one test import do not churn. */
internal typealias HealthView = DaemonProbe.HealthView

internal data class DaemonSnapshot(val port: Int, val health: HealthView?) {
    val healthVersion: String? get() = health?.version
    val running: Boolean get() = health != null
}

/** The topology as doctor sees it: not written yet, readable, or broken (with the parse error). */
internal sealed class DoctorTopology {
    data object Absent : DoctorTopology()
    data class Parsed(val topology: Topology) : DoctorTopology()
    data class Broken(val message: String) : DoctorTopology()
}

/** DR-174: the mgmt-key file as a reader actually found it — the same three-way distinction
 *  DoctorTopology already draws for splice.toml, applied to the credential beside it.
 *
 *  AdminSupport.mgmtKey returned String? through runCatching(...).getOrNull(), so a key sitting at
 *  0000 and a key that was never minted arrived identically as null. `splice restart` rendered that
 *  single null as "mgmt-key not found at <path> — can't stop the daemon", which is advice to go
 *  create a key that already exists, when the actual remedy is one chmod. DoctorHeadChecks.mgmtKeyCheck
 *  has drawn this distinction correctly since DR-41a and says so out loud — "it may exist, so nothing
 *  needs re-minting" — so the law was established in-repo and the other readers simply never received
 *  it. Only NoSuchFileException is positive evidence of absence; every other read failure means the
 *  key MAY exist, and the operator must never be told to re-mint on that evidence.
 *
 *  Unreadable carries a rendered reason, never raw bytes: this is a credential path, so the text
 *  goes through SafeFailureText (DR-65). */
internal sealed class MgmtKeyRead {
    data class Present(val key: String) : MgmtKeyRead()
    data object Absent : MgmtKeyRead()
    data class Unreadable(val reason: String) : MgmtKeyRead()
}
