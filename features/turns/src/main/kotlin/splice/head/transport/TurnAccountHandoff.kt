// NEW: a native refusal may move an uncommitted turn to a free, never-visited login.
package splice.head.transport

import splice.head.admission.TurnQuota
import splice.head.turn.TurnDrive
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.Selection

/** Acceptance and client commitment close the same gate that changes account and quota together. */
internal class TurnAccountHandoff(
    private val pool: AccountPool,
    private val quotas: TurnQuota,
) {
    private val refused = linkedSetOf<String>()
    private var committed = false

    @Synchronized
    fun commit() {
        committed = true
    }

    @Synchronized
    fun move(drive: TurnDrive): Boolean {
        if (committed) return false
        val previous = drive.account ?: return false
        refused += previous.account.label
        return when (val next = pool.select(drive.meta.scope.sessionId, refused)) {
            is Selection.Chosen -> {
                previous.releaseCredentialProbe()
                drive.account = next.account
                drive.quota = quotas.forSession(drive.meta.scope.sessionId, next.account)
                true
            }
            is Selection.Exhausted -> false
        }
    }
}
