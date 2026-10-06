// PORT-OF: daemon/control/.../ControlPorts.kt (DoctorReport) — invariants unchanged: the doctor
// route's port, moved beside the route that answers with it.
package splice.diagnostics.doctor

import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.pool.HeadAccountPoolView
import splice.core.head.ProviderAnswer
import splice.daemonclient.DaemonProbe

/**
 * V4-127: the `doctor --json` report, as the CLI renders it (JSON TEXT, not a JsonObject, so these
 * ports stay free of a serialization import and the daemon decides the shape in the module that
 * already owns it).
 *
 * `UpgradeStatus` (the lifecycle feature) returns the same `() -> String`, and the control plane's
 * `MgmtRoute` is a `() -> Unit` shape — three questions that a raw function type could not tell apart. Naming the question is
 * what keeps a caller from wiring the upgrade payload into the doctor route and having both compile.
 *
 * V4-230: the report reads the daemon from [DaemonAnswers] the route took in process, never from the
 * daemon's own port.
 */
public fun interface DoctorReport {
    public operator fun invoke(answers: DaemonAnswers): String
}

/** What the daemon's own doctor reads without making an HTTP call back into itself. [trace] is
 *  derived from the same effective ConfigService GET /api/config serves to an external CLI. */
public data class DaemonAnswers(
    public val health: String,
    public val heads: String,
    public val auth: String,
    public val trace: Map<String, DaemonProbe.HeadTrace>,
    /** Declared tiers absent from each head's current catalog, read in process. */
    public val unmappedTiers: Map<String, Map<String, String>> = emptyMap(),
    /** The exact /api/accounts roster, not the auth endpoint's different account-pool denominator. */
    public val accounts: String = """{"accounts":[]}""",
)

/** The authoritative roster was read, or its exact read failure, shared by doctor and status. */
public sealed class AccountPoolsRead {
    public data class Read(
        public val pools: Map<String, HeadAccountPoolView>,
        public val nativeLogins: List<NativeLoginHealth> = emptyList(),
        public val lastRefusals: Map<String, ProviderAnswer> = emptyMap(),
    ) : AccountPoolsRead() {
        /** Account-keyed observations stay with the roster instead of becoming a head's verdict. */
        public var accountRefusals: Map<String, Map<String, ProviderAnswer>> = emptyMap()
            internal set
    }

    public data class Unread(public val reason: String, public val fix: String?) : AccountPoolsRead()
}

/** Allowlisted place diagnosis, with no path, credential, identity or raw provider sentence. */
public data class NativeLoginHealth(
    val head: String,
    val place: ClaudeLoginPlaceId,
    val expired: Boolean,
    val present: Boolean,
)

/** Takes the daemon's [DaemonAnswers] at call time: two of the three are suspend reads, and a doctor
 *  run wants the state as of its own request. */
public fun interface DaemonAnswersSource {
    public suspend operator fun invoke(): DaemonAnswers
}
