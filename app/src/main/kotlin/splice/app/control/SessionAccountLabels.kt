// NEW: Oct 12, 2026 — which login each session's requests carry, out of SessionsWiring for the concentration law.
package splice.app.control

import splice.accounts.pool.HeadAccountSessionChoice
import splice.accounts.pool.HeadAccountSessionSource
import splice.accounts.pool.HeadAccountSessions
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
        val byHead = records.groupBy { it.head }
        val saved = byHead.mapNotNull { (head, sessions) ->
            val managed = heads[head] ?: return@mapNotNull null
            if (managed.authSurface.authKind != CLIENT_AUTH_KIND) return@mapNotNull null
            val source = managed.sources.perfRows as? PerfRowsFileSource ?: return@mapNotNull null
            val ids = sessions.mapNotNull { it.sessionId }
                .filterNot { ports.claudeLogins?.hasCarryingProof(requireNotNull(head), it) == true }.toSet()
            if (ids.isEmpty()) return@mapNotNull null
            requireNotNull(head) to source.sessionAccounts(ids)
        }.toMap()
        val ids = byHead.mapNotNull { (head, sessions) ->
            head?.let { it to sessions.mapNotNull(SessionRecord::sessionId).distinct() }
        }.toMap()
        return ListingViews(saved, ids)
    }

    override fun label(head: String?, sessionId: String): String? = head?.let {
        val surface = heads[it]?.authSurface?.takeUnless { auth -> auth.authKind == CLIENT_AUTH_KIND }
        account(it, sessionId, null, surface?.accountPool?.view(sessionId)?.selectedLabel)
    }

    override fun pin(head: String?, sessionId: String): String? = pinOf(head, sessionId)

    override fun headPin(head: String?): String? = headPinOf(head)

    override fun switchOf(head: String?, sessionId: String): SessionAccountSwitch? = lastSwitch(head, sessionId)

    /** The labels as [forRecords] read them, with the accounts the perf rows saved for the sessions in view. One
     *  listing reads each head's pool once for all its sessions ([ids]). A pool view re-reads every login's
     *  credentials: asked per row, 590 rows took /api/sessions to 23.5 s on a cold daemon (splice-usage, Oct 13), and
     *  one view per session still left 1.1 s of a 1.6 s warm listing there (splice-sessions, Oct 13). */
    private inner class ListingViews(
        private val saved: Map<String, PerfSessionAccountIndex.Snapshot>,
        private val ids: Map<String, List<String>>,
    ) : SessionAccountOf {
        private val pools = HashMap<String, HeadAccountSessions?>()

        private fun poolOf(head: String?): HeadAccountSessions? = head?.let { key ->
            if (key !in pools) pools[key] = read(key)
            pools[key]
        }

        /** A pool that cannot read a listing at once (a test double) is asked per session, as before. */
        private fun read(head: String): HeadAccountSessions? {
            val pool = heads[head]?.authSurface?.accountPool ?: return null
            val sessions = ids[head].orEmpty()
            return (pool as? HeadAccountSessionSource)?.sessions(sessions) ?: HeadAccountSessions(
                headPinnedLabel = pool.view(null).pinnedLabel,
                bySession = sessions.associateWith { id ->
                    pool.view(id).let { HeadAccountSessionChoice(it.selectedLabel, it.pinnedLabel, it.lastSwitch) }
                },
            )
        }

        private fun choiceOf(head: String?, session: String): HeadAccountSessionChoice? =
            poolOf(head)?.bySession?.get(session)

        override fun label(head: String?, sessionId: String): String? =
            head?.let { account(it, sessionId, saved[it], choiceOf(it, sessionId)?.selectedLabel) }

        override fun pin(head: String?, sessionId: String): String? = choiceOf(head, sessionId)?.pinnedLabel

        override fun headPin(head: String?): String? = poolOf(head)?.headPinnedLabel

        override fun switchOf(head: String?, sessionId: String): SessionAccountSwitch? =
            choiceOf(head, sessionId)?.lastSwitch
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
        selected: String?,
    ): String? {
        val managed = heads[head] ?: return null
        val pool = managed.authSurface.accountPool
        return when {
            managed.authSurface.authKind == CLIENT_AUTH_KIND -> clientAccount(head, session, saved)
            pool != null -> selected
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
