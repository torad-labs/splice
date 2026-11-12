// NEW: Oct 13, 2026 — every listed session's own choice on its head's pool, read once per listing.
package splice.accounts.pool

/** What a session listing shows of a head's pool, read for all of that head's sessions at once.
 *
 *  A SIBLING of [HeadAccountPoolSource], for the reason [HeadAccountPinSource] is one: test doubles implement that
 *  fun interface as a bare lambda. A listing discovers this with a checked cast and, without it, asks [view] per
 *  session. [HeadAccountPoolSource.view] re-reads every login and builds every account's row on each call, so 609
 *  session rows asking it apart spent 1.1 s of a 1.6 s /api/sessions there (splice-sessions, Oct 13). */
public interface HeadAccountSessionSource {
    public fun sessions(sessionIds: Collection<String>): HeadAccountSessions
}

/** The head-wide pin, and each asked session's own choice keyed by its id. */
public data class HeadAccountSessions(
    val headPinnedLabel: String?,
    val bySession: Map<String, HeadAccountSessionChoice>,
)

/** One session's login on its head's pool, the label it is pinned to (its own pin, else the head's), and its own last
 *  switch: the three fields [HeadAccountPoolView] carries for a session, without the head-wide ones. */
public data class HeadAccountSessionChoice(
    val selectedLabel: String?,
    val pinnedLabel: String?,
    val lastSwitch: HeadAccountSwitchView?,
)
