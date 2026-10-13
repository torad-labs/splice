// NEW: Oct 12, 2026 — which login each session's requests carry, out of SessionsWiring for the concentration law.
package splice.app.control

import splice.accounts.pool.HeadAccountPoolView
import splice.app.sources.PerfRowsFileSource
import splice.app.sources.PerfSessionAccountIndex
import splice.core.auth.CLIENT_AUTH_KIND
import splice.core.topology.AuthKindRegistry
import splice.sessions.http.SessionAccountOf
import splice.sessions.http.SessionAccountSwitch
import splice.sessions.registry.SessionRecord

/** The label a single-login head's one login is filed under: its requests' perf rows (TurnDriveFactory's fallback
 *  account label) and its quota (HeadQuotaPolling) both use it, and the Accounts roster shows that login as the head's
 *  single_login row. */
private const val SINGLE_LOGIN_LABEL = "primary"

/** The stable label of the login a session's requests on its head carry, the one the Accounts roster and the perf
 *  rows use, so the console names it from the head plus this label. A client session reads the native place or
 *  added-account label proved by its own newest sent credential, or null when no login matches. Never another
 *  session's choice, the head's current selection, or a pool decision the request did not use. [ports] is read at
 *  CALL time, the SessionsWiring rule. */
internal class SessionAccountLabels(
    private val heads: Map<String, ManagedHead>,
    private val ports: ConsolePorts,
) : SessionAccountOf {
    override fun forRecords(records: List<SessionRecord>): SessionAccountOf {
        val saved = records.groupBy { it.head }.mapNotNull { (head, sessions) ->
            val managed = heads[head] ?: return@mapNotNull null
            if (managed.authSurface.authKind != CLIENT_AUTH_KIND) return@mapNotNull null
            val source = managed.sources.perfRows as? PerfRowsFileSource ?: return@mapNotNull null
            val ids = sessions.mapNotNull { it.sessionId }
                .filterNot { ports.claudeLogins?.hasCarryingProof(requireNotNull(head), it) == true }.toSet()
            if (ids.isEmpty()) return@mapNotNull null
            requireNotNull(head) to source.sessionAccounts(ids)
        }.toMap()
        return snapshot(saved)
    }

    override fun label(head: String?, sessionId: String): String? = head?.let {
        val surface = heads[it]?.authSurface?.takeUnless { auth -> auth.authKind == CLIENT_AUTH_KIND }
        account(it, sessionId, null, surface?.accountPool?.view(sessionId))
    }

    override fun pin(head: String?, sessionId: String): String? = pinOf(head, sessionId)

    override fun headPin(head: String?): String? = headPinOf(head)

    override fun switchOf(head: String?, sessionId: String): SessionAccountSwitch? = lastSwitch(head, sessionId)

    /** The labels as [forRecords] read them, with the accounts the perf rows saved for the sessions in view. One
     *  listing reads each pool view once: a view re-reads every login's credentials, and a row asked for up to four of
     *  them, so 590 rows read the logins thousands of times and /api/sessions took 23.5 s on a cold daemon
     *  (splice-usage, Oct 13). */
    private fun snapshot(saved: Map<String, PerfSessionAccountIndex.Snapshot>): SessionAccountOf = ListingViews(saved)

    private inner class ListingViews(
        private val saved: Map<String, PerfSessionAccountIndex.Snapshot>,
    ) : SessionAccountOf {
        private val views = HashMap<Pair<String, String?>, HeadAccountPoolView?>()

        private fun viewOf(head: String?, session: String?): HeadAccountPoolView? = head?.let { key ->
            views.getOrPut(key to session) { heads[key]?.authSurface?.accountPool?.view(session) }
        }

        override fun label(head: String?, sessionId: String): String? =
            head?.let { account(it, sessionId, saved[it], viewOf(it, sessionId)) }

        override fun pin(head: String?, sessionId: String): String? = viewOf(head, sessionId)?.pinnedLabel

        override fun headPin(head: String?): String? = viewOf(head, null)?.pinnedLabel

        override fun switchOf(head: String?, sessionId: String): SessionAccountSwitch? =
            viewOf(head, sessionId)?.lastSwitch
                ?.let { SessionAccountSwitch(it.from, it.to, it.reason, it.atEpochMillis) }
    }

    /** The label this session is pinned to on its head's pool (its own pin, else the head's), or null. */
    private fun pinOf(head: String?, session: String): String? =
        head?.let { heads[it]?.authSurface?.accountPool?.view(session)?.pinnedLabel }

    private fun headPinOf(head: String?): String? =
        head?.let { heads[it]?.authSurface?.accountPool?.view(null)?.pinnedLabel }

    /** The session's own last switch on its head's pool (the pool's per-session record), or null. */
    private fun lastSwitch(head: String?, session: String): SessionAccountSwitch? =
        head?.let { heads[it]?.authSurface?.accountPool?.view(session)?.lastSwitch }
            ?.let { SessionAccountSwitch(it.from, it.to, it.reason, it.atEpochMillis) }

    private fun account(
        head: String,
        session: String,
        saved: PerfSessionAccountIndex.Snapshot?,
        view: HeadAccountPoolView?,
    ): String? {
        val managed = heads[head] ?: return null
        val pool = managed.authSurface.accountPool
        return when {
            managed.authSurface.authKind == CLIENT_AUTH_KIND -> clientAccount(head, session, saved)
            pool != null -> view?.selectedLabel
            AuthKindRegistry.isOAuth(managed.authSurface.authKind) -> SINGLE_LOGIN_LABEL
            else -> null
        }
    }

    private fun clientAccount(head: String, session: String, saved: PerfSessionAccountIndex.Snapshot?): String? {
        val logins = ports.claudeLogins
        val live = logins?.carryingAccount(head, session)
        if (live != null || logins?.hasCarryingProof(head, session) == true) return live
        val account = saved?.accounts?.get(session) ?: return null
        return logins?.accountLabel(head, account) ?: account
    }
}
