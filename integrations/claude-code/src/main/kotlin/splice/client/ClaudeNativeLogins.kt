// NEW: native browser login prepares save-back before replacement and files only the newly live identity.
package splice.client

import splice.core.util.Cancellables
import splice.core.util.SafeFailureText

private val nativeLabelShape = Regex(CLAUDE_LOGIN_LABEL_PATTERN)

/** The fresh-login half of ClaudeLogins. It never restores a stored or stale credential. */
internal class ClaudeNativeLogins(private val store: LoginStore) {
    fun prepare(target: ClaudeLoginTarget, label: String?, sessions: HeadSessions): ClaudeNativeLoginPreparation {
        if (label != null && !nativeLabelShape.matches(label)) {
            return ClaudeNativeLoginPreparation.Refused("invalid Claude login label")
        }
        refusal(target.head, sessions)?.let { return ClaudeNativeLoginPreparation.Refused(it, running(sessions)) }
        return Cancellables.runCatchingCancellable {
            val records = store.records()
            val expected = label?.let { records[it]?.account?.uuid }
            when (val live = HeadLogin.read(target.head.configDir, target.accountFile)) {
                is Live.Unreadable -> ClaudeNativeLoginPreparation.Refused(Refusals.unreadable(target.head, live.why))
                is Live.Held -> saveBack(target, label, expected, live, records)
                Live.Absent -> {
                    store.selected()?.let(store::demote)
                    ClaudeNativeLoginPreparation.Ready(target, store, label, expected)
                }
            }
        }.getOrElse { failure ->
            ClaudeNativeLoginPreparation.Refused("native login save-back stopped (${SafeFailureText.render(failure)})")
        }
    }

    private fun saveBack(
        target: ClaudeLoginTarget,
        label: String?,
        expected: String?,
        live: Live.Held,
        records: Map<String, Record>,
    ): ClaudeNativeLoginPreparation.Ready {
        val existingOwner = owner(records, live.account)
        val owner = existingOwner ?: automaticLabel(live.account)
        val occupied = records[owner]?.account
        require(occupied == null || occupied.uuid == live.account.uuid) {
            "the automatic save-back label belongs to another account"
        }
        store.selected()?.takeIf { it != owner }?.let(store::demote)
        store.save(owner, live.bytes, live.account)
        store.unselect()
        val generated = owner.takeIf { existingOwner == null }
        return ClaudeNativeLoginPreparation.Ready(target, store, label, expected, generated)
    }

    fun complete(
        target: ClaudeLoginTarget,
        prepared: ClaudeNativeLoginPreparation.Ready,
        sessions: HeadSessions,
    ): ClaudeLoginResult {
        if (prepared.target != target || prepared.store !== store) {
            return ClaudeLoginResult.Refused("native login preparation belongs to another command destination")
        }
        refusal(target.head, sessions)?.let { return ClaudeLoginResult.Refused(it, running(sessions)) }
        return Cancellables.runCatchingCancellable {
            val live = HeadLogin.read(target.head.configDir, target.accountFile) as? Live.Held
                ?: return@runCatchingCancellable ClaudeLoginResult.Refused(
                    "native login recorded no readable live account",
                )
            require(prepared.expectedAccountUuid == null || prepared.expectedAccountUuid == live.account.uuid) {
                "native login used a different account than the label"
            }
            saveFresh(target, prepared, live)
        }.getOrElse { failure ->
            ClaudeLoginResult.Refused("native login save stopped (${SafeFailureText.render(failure)})")
        }
    }

    private fun saveFresh(
        target: ClaudeLoginTarget,
        prepared: ClaudeNativeLoginPreparation.Ready,
        live: Live.Held,
    ): ClaudeLoginResult.Done {
        val owner = owner(store.records(), live.account)
        val label = prepared.label ?: owner ?: automaticLabel(live.account)
        require(owner == null || owner == label || owner == prepared.generatedSaveBackLabel) {
            "this native account already has a different label"
        }
        store.save(label, live.bytes, live.account)
        owner?.takeIf { it != label && it == prepared.generatedSaveBackLabel }?.let(store::remove)
        store.select(label)
        return ClaudeLoginResult.Done(Said.refreshed(target.head, label, live.account.shown))
    }

    private fun owner(records: Map<String, Record>, account: Account): String? =
        records.entries.firstOrNull { it.value.account.uuid == account.uuid }?.key

    private fun automaticLabel(account: Account): String = "account-${account.uuid}".also {
        require(nativeLabelShape.matches(it)) { "the native account needs an explicit valid save-back label" }
    }

    private fun running(sessions: HeadSessions): Boolean = sessions is HeadSessions.Read && sessions.live.isNotEmpty()

    private fun refusal(head: ClaudeHead, sessions: HeadSessions): String? = when (sessions) {
        is HeadSessions.Unreadable -> Refusals.unknownSessions(head, sessions.why)
        is HeadSessions.Read -> sessions.live.takeIf { it.isNotEmpty() }?.let { Refusals.running(head, it) }
    }
}
