// NEW: V4-276 — every sentence `splice login <claude-head> --label` says. A refusal says what
// happened, that nothing live changed, and what to do next; a completed verb says what changed and
// what comes next. Split out of ClaudeLogins.kt for the concentration law.
package splice.client

/** The refusals: each says what happened, that nothing live changed, and what to do next. */
internal object Refusals {
    fun running(head: ClaudeHead, live: List<String>): String =
        "a session of ${head.key} is running (${live.joinToString("; ")}). Its login refreshes itself while it " +
            "runs, so splice changed nothing under it: end it, then run this again."

    fun unknownSessions(head: ClaudeHead, why: String): String =
        "splice cannot tell whether a session of ${head.key} is running ($why), so it changed no login."

    fun unreadable(head: ClaudeHead, why: String): String =
        "splice cannot tell whose login ${head.key} holds ($why), so it changed nothing. Start ${head.key} " +
            "once so Claude Code records its account, then run this again."

    fun notFiled(head: ClaudeHead, label: String, account: Account?, live: Account): String =
        "${head.key} is signed in as ${live.shown}, not '$label'${Words.of(account)}, so splice filed nothing " +
            "under '$label' and switched nothing. Save that login under a new label with `splice login " +
            "${head.key} --label <new name>`, or drop it by adding --discard to this command."

    fun unsaved(head: ClaudeHead, shown: String): String =
        "${head.key} is signed in as $shown, which is saved under no label, so a switch would lose it. Save it " +
            "with `splice login ${head.key} --label <new name>`, or drop it by adding --discard to this command."

    fun nothingLive(head: ClaudeHead, label: String, account: Account?, legacy: Boolean): String {
        val why = if (account == null && !legacy) {
            "${head.key} holds no login to save as '$label'"
        } else {
            "splice put nothing back${Words.stale(label, account, legacy)}. Re-save it from a live login"
        }
        return "$why: start ${head.key}, sign in${Words.to(account)} with /login, exit, then run `splice login " +
            "${head.key} --label $label`."
    }

    fun notRecorded(head: ClaudeHead, label: String, why: String): String =
        "could not record '$label''s account in ${head.key}'s ${Keys.CLAUDE_JSON} ($why), so the login it held was " +
            "put back and nothing was switched."

    fun stopped(head: ClaudeHead, label: String, why: String): String =
        "`splice login ${head.key} --label $label` stopped ($why). The head's live login is replaced only by a " +
            "switch that completed; run it again once that is fixed."
}

/** What a completed verb tells the operator: what changed, and what comes next. */
internal object Said {
    fun demoted(head: ClaudeHead, label: String, account: Account?): String =
        "'$label'${Words.of(account)} left ${head.key} without splice saving it first, so its stored copy may be " +
            "older than its last refresh and will not be put back: re-save it from a live login (sign in to it " +
            "with /login in ${head.key}, then run `splice login ${head.key} --label $label`)."

    fun refreshed(head: ClaudeHead, label: String, shown: String): String =
        "saved ${head.key}'s login ($shown) as '$label', and selected it."

    fun saved(head: ClaudeHead, label: String, shown: String): String =
        "saved ${head.key}'s login ($shown) as the new label '$label', and selected it."

    fun switched(head: ClaudeHead, savedAs: String?, label: String, shown: String): String =
        savedAs?.let { "saved ${head.key}'s login as '$it', then " }.orEmpty() +
            "switched ${head.key} to '$label' ($shown): its next session signs in with it."

    fun signedOut(head: ClaudeHead, owner: String, label: String, account: Account?, legacy: Boolean): String =
        "saved ${head.key}'s login as '$owner' and signed ${head.key} out, so '$label' can get a fresh login" +
            "${Words.stale(label, account, legacy)}: start ${head.key}, sign in${Words.to(account)} with /login, " +
            "exit, then run `splice login ${head.key} --label $label` again. `splice login ${head.key} --label " +
            "$owner` switches back."

    fun dropped(head: ClaudeHead, label: String, account: Account?): String =
        "dropped ${head.key}'s login, as --discard asked, and signed ${head.key} out, so '$label' can get a " +
            "fresh login: start ${head.key}, sign in${Words.to(account)} with /login, exit, then run `splice " +
            "login ${head.key} --label $label` again."

    /** A demotion found on the way is said beside whatever the verb then did or refused. */
    fun withNote(result: ClaudeLoginResult, note: String): ClaudeLoginResult = when (result) {
        is ClaudeLoginResult.Done -> ClaudeLoginResult.Done("${result.said} $note")
        is ClaudeLoginResult.Refused -> ClaudeLoginResult.Refused("${result.reason} $note")
        ClaudeLoginResult.Ok -> result
    }
}

internal object Words {
    /** Why a stored copy is never put back, or nothing for a new label. */
    fun stale(label: String, account: Account?, legacy: Boolean): String = when {
        account != null -> " ('$label''s stored copy may be older than its last refresh, so it is never put back)"
        legacy ->
            " ('$label' was stored before splice recorded whose account a copy is, so it may be stale and is " +
                "never put back)"
        else -> ""
    }

    fun of(account: Account?): String = account?.let { " (${it.shown})" }.orEmpty()

    fun to(account: Account?): String = account?.let { " to ${it.shown}" }.orEmpty()
}
