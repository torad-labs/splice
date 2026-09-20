// NEW: V4-175 — the one question `splice setup` asks about the Claude head, and what answering
// `wrap` does.
//
// THE DEFECT THIS CLOSES. The catalogue's `claude` row hardcodes headKey and command to
// `claude-splice` (AddProfileCatalog.kt) and the wizard never asked, because SetupHeads excluded
// the profile from its tick list with the reason "needs a name; run: splice add claude --name
// NAME" — which was not true of it: `key = args.name ?: profile.headKey` (AddPrepare.kt:42) and
// that row's headKey is non-empty, so `splice add claude --yes` has always worked with no --name.
// A wrong reason kept the whole lane choice off the wizard, and the operator who wanted `claude`
// itself to go through splice had to find the wrap route on their own.
//
// WHY THIS PACKAGE. `splice.app.cli` is the concentration ratchet's worst package (84 files at the
// baseline), and splice.app.cli.setup is where the wizard's other extracted half already went for
// the same reason (SetupSignIn, V4-156). SetupHeads keeps the tick list; this keeps the lane.
//
// THE SAME PATH, NOT A SECOND MECHANISM. Wrapping means POST /api/claude-head/wrap on the loopback
// control plane — the identical route the console's Settings page posts to, and the only place the
// MaterializeSpec can be built, since it is assembled from the live `claude-splice` head's own
// LaunchSpec (ClaudeHeadRoutes.kt). The wizard does not touch ~/.claude, does not plant a shim and
// does not learn what wrapping is; it asks the daemon that already knows.
package splice.app.cli.setup

import splice.app.cli.AdminSupport
import splice.app.cli.ControlPlaneClient
import splice.app.cli.ControlReply
import splice.app.cli.MgmtKeyRead
import splice.app.cli.prompt.SelectOption
import splice.app.cli.prompt.SelectOutcome
import splice.core.util.EnvReader

/** The catalogue row this question is about (AddProfileCatalog's `claude`). */
internal const val CLAUDE_PROFILE: String = "claude"

/** The one line on what Wrap takes over — the row's words, and the two files are named because
 *  they are the operator's own, not splice's to quietly own. */
internal const val WRAP_HINT: String =
    "claude itself goes through splice: a shim shadows the claude command and " +
        "settings.json and .claude.json in ~/.claude are rewritten (both backed up first)"

internal const val SEPARATE_HINT: String =
    "a claude-splice command beside your own; nothing in ~/.claude is touched"

/** Where to do it by hand. There is no `splice wrap` verb — the console's Settings page is the
 *  other caller of this route, so it is what the operator is actually sent to. */
internal const val WRAP_LATER: String = "wrap it from splice dashboard, Settings"

/** Which lane the operator put the Claude head in. SEPARATE is the default everywhere a choice is
 *  not made — including a non-TTY run, where SelectPrompt answers with the preselected option. */
internal enum class ClaudeLane { SEPARATE, WRAP }

/** Single-select over the two lanes; production is SelectPrompt. */
internal fun interface LanePicker {
    operator fun invoke(options: List<SelectOption<ClaudeLane>>, initialIndex: Int): SelectOutcome<ClaudeLane>
}

/** The wrap call, answering in one line the wizard prints verbatim. Production is
 *  [DaemonClaudeWrap]; a test hands over its own. */
internal fun interface ClaudeWrap {
    operator fun invoke(): String
}

internal class SetupClaudeLane(
    private val pick: LanePicker,
    private val wrap: ClaudeWrap,
) {

    /** The lane for this run. Asked only when `claude` is actually among the ticked profiles: a
     *  question about a head nobody is adding is a question with no answer. */
    fun ask(picked: List<String>): ClaudeLane {
        if (CLAUDE_PROFILE !in picked) return ClaudeLane.SEPARATE
        val options = listOf(
            SelectOption(ClaudeLane.SEPARATE, "Separate", SEPARATE_HINT),
            SelectOption(ClaudeLane.WRAP, "Wrap", WRAP_HINT),
        )
        return when (val answer = pick(options, 0)) {
            is SelectOutcome.Chosen -> answer.value
            SelectOutcome.Cancelled -> ClaudeLane.SEPARATE
        }
    }

    /** The summary line, so the operator reads what Wrap takes over BEFORE confirming the install
     *  rather than after it has happened. */
    fun summaryLine(lane: ClaudeLane): String = when (lane) {
        ClaudeLane.SEPARATE -> "Claude lane: separate — $SEPARATE_HINT"
        ClaudeLane.WRAP -> "Claude lane: wrap — $WRAP_HINT"
    }

    /** Run against what LANDED and after the daemon restarted, because wrap reads the live
     *  `claude-splice` head's spec. Returns the line to print, or null when there is nothing to
     *  say; never throws, since a refused wrap leaves a perfectly good separate head behind and
     *  must not fail the setup that built it. */
    fun apply(lane: ClaudeLane, landed: List<String>): String? = when {
        lane != ClaudeLane.WRAP -> null
        CLAUDE_PROFILE !in landed -> "not wrapping: the claude head was not added"
        else -> wrap()
    }
}

/** POST /api/claude-head/wrap, reported in the daemon's own words. A collaborator rather than a
 *  factory function: every branch names what to do next, and "wrap failed" with no reason is the
 *  answer that sends an operator to the source. */
internal class DaemonClaudeWrap(private val env: EnvReader = EnvReader(System::getenv)) : ClaudeWrap {

    override fun invoke(): String = when (val read = AdminSupport.readMgmtKey(env)) {
        is MgmtKeyRead.Present -> post(read.key)
        MgmtKeyRead.Absent -> "not wrapping: no management key yet — $WRAP_LATER"
        is MgmtKeyRead.Unreadable -> "not wrapping: the management key is unreadable (${read.reason})"
    }

    private fun post(key: String): String {
        val url = "http://127.0.0.1:${AdminSupport.controlPort(env)}/api/claude-head/wrap"
        val reply = ControlPlaneClient.send(url, "POST", key)
            ?: return "not wrapping: the daemon did not answer — $WRAP_LATER"
        return replyLine(reply)
    }

    /** Internal, not private: the three branches are what a test can reach without standing up a
     *  control plane, and a mapping nobody has watched choose the wrong branch is a mapping nobody
     *  has checked. */
    internal fun replyLine(reply: ControlReply): String = when (reply.status) {
        in ControlPlaneClient.OK_RANGE ->
            "wrapped: claude now runs through splice — undo it from splice dashboard, Settings"
        // The body is `{"error": "<reason>"}`; the reason is the whole content of a refusal, so it
        // is quoted rather than summarised.
        else -> "not wrapping: ${reasonOf(reply)}"
    }

    private fun reasonOf(reply: ControlReply): String =
        Regex("\"error\"\\s*:\\s*\"(.*?)\"").find(reply.body)?.groupValues?.get(1)
            ?: "the daemon answered ${reply.status}"
}
