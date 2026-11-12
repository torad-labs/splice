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

    /** A login is on file, but its token has expired and the renewal this lookup waited for ended as [outcome]. Never
     *  [Missing]: the person is signed in. The turn's retry engine reads [outcome] as it reads a renewal after a refused
     *  login: a token endpoint that could not answer is asked again 2 s and then 4 s later, and anything else moves
     *  the turn to another login or ends it as splice's own login refused (audit, Nov 9, 2026: a resume past the
     *  token's expiry told a signed-in person to log in, with no retry, because the outcome was flattened to null).
     *  [held] is the expired credential the lookup read (null: none known): nothing was sent, so a later exchange the
     *  turn asks for is asked about this one, and adopts a renewal another turn landed meanwhile instead of rotating
     *  the renewed token again (Nov 10, 2026). */
    public data class Unrenewed(val outcome: RefreshOutcome, val held: Credentials? = null) : LoginAnswer()
}
