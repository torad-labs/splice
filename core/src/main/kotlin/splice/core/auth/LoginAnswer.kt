// NEW: failure-handling slice 3 (2026-10-26). What a login lookup answers. A provider that refuses to grant a login
// says why, so a turn that meets the refusal ends with the provider's reason instead of telling the person to log in
// (final-design.md section 6: Muse's key mint can refuse, MuseMintVerdict.kt:15-37).
package splice.core.auth

import splice.core.failure.FailureCause

/** What a login lookup answers: a credential to send, none at all, or the provider's refusal to grant one. */
public sealed class LoginAnswer {
    /** The credential this attempt sends. */
    public data class Granted(val credentials: Credentials) : LoginAnswer()

    /** No login to send: the head turns it into NoLogin, with its own login command. */
    public data object Missing : LoginAnswer()

    /** The provider refused to grant a login, for [cause]: the turn ends with it, as on the provider's own reply. */
    public data class Refused(val cause: FailureCause.Provider) : LoginAnswer()
}
