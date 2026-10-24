// NEW: V4-220 item 6b (2026-09-25) — what upstream last said about a credential splice does not hold.
//
// A client head forwards the caller's own Claude login and holds nothing (ClientAuthProvider), so its
// description said `present = true` unconditionally and the console could never flag a signed-out
// Claude head. splice cannot read the client's credential, but it sees upstream's answer to every
// forwarded turn: a 401 means the login was rejected, a success that it was accepted. Until a
// forwarded turn has been answered since the daemon started, splice does not know, and says so.
package splice.core.auth

public sealed class CredentialVerdict {
    /** The word the control plane writes for this verdict (`/api/auth`'s `verdict.state`). */
    public abstract val wire: String

    /** When upstream gave this verdict, or null when there is none to date. */
    public open val atEpochMs: Long? get() = null

    /** splice holds the credential itself: [AuthDescription.present] is the whole fact. */
    public data object Held : CredentialVerdict() {
        override val wire: String = "held"
    }

    /** Forwarded, and no forwarded turn has been answered since the daemon started. */
    public data object Unverified : CredentialVerdict() {
        override val wire: String = "unverified"
    }

    /** Upstream accepted the last forwarded turn it answered. */
    public data class Accepted(override val atEpochMs: Long) : CredentialVerdict() {
        override val wire: String = ACCEPTED
    }

    /** Upstream answered the last forwarded turn 401: the login was rejected. */
    public data class Rejected(override val atEpochMs: Long) : CredentialVerdict() {
        override val wire: String = REJECTED
    }
}

/** Reads a verdict back off `/api/auth` (a collaborator, not a companion: kt-no-companion-objects). */
public class CredentialVerdictRead {
    /** The verdict [wire] and [atEpochMs] name; null for a word this build does not know, or a dated
     *  verdict without its date. */
    public fun of(wire: String?, atEpochMs: Long?): CredentialVerdict? = when (wire) {
        CredentialVerdict.Held.wire -> CredentialVerdict.Held
        CredentialVerdict.Unverified.wire -> CredentialVerdict.Unverified
        ACCEPTED -> atEpochMs?.let(CredentialVerdict::Accepted)
        REJECTED -> atEpochMs?.let(CredentialVerdict::Rejected)
        else -> null
    }
}

private const val ACCEPTED = "accepted"
private const val REJECTED = "rejected"
