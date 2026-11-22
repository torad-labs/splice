// NEW: door-default (docs/specs/door-default.md, decisions 1, 3 and 4). The lane the terminal's `claude` runs on, and
// what `splice claude`, `splice claude door` and `splice claude head` do to it.
//
// With no choice made the lane is the door when splice.toml has no head whose command is `claude`, and the head when
// it has one, so an upgrade changes no lane. A choice is kept in the lane's record. `door` refuses a `--model` the door
// does not serve and `head` refuses with no head to go back to, each changing nothing. `head` retires the terminal's
// key, so a session started on the door is refused from then on. The model a session starts on is Desktop's chain with
// the picker's step (StartingModel): the `--model` named now, or the one chosen the last time, or the pinned model of
// the first provider in the picker (a signed-in account's), or the pinned model of the first provider in the door.
// Decision 9:
// on its head `claude` carries the door's models, so neither move says heads are going away, and `door` on a machine
// with a head says that Claude's models stop running in `claude` until `head`.
package splice.app.desktop.lane

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.app.desktop.catalog.DesktopLineup
import splice.app.desktop.catalog.DesktopProviders
import splice.app.desktop.catalog.StartingModel
import splice.app.desktop.setup.DesktopAct
import splice.core.config.FileHeldKey
import splice.core.wire.HttpStatus

/** Whether splice.toml has a head whose command is `claude`, read at each use. */
internal fun interface ClaudeHeadHere {
    operator fun invoke(): Boolean
}

internal class ClaudeLane(
    private val records: ClaudeLaneRecords,
    /** The terminal's key, which the door opens to for `claude`'s sessions and no other caller's. */
    val key: FileHeldKey,
    private val providers: DesktopProviders,
    /** The door's address, the one Desktop's Code tab is given too. */
    val door: String,
    private val headHere: ClaudeHeadHere,
    /** splice's /login in the person's Claude folder, which `door` lays and `head` takes out. */
    val login: ClaudeLogin,
) {
    private val lock = Mutex()
    private val chain = StartingModel({ records.get().defaultModel }, picker = true)

    /** [DOOR_LANE] or [HEAD_LANE]: the person's choice, or with none the head when there is one to run on. */
    fun lane(): String = records.lane(headHere)

    fun onDoor(): Boolean = lane() == DOOR_LANE

    /** The lineup the door serves now. */
    suspend fun lineup(): DesktopLineup = providers.lineup()

    /** The model a session starts on in [lineup], and which rule chose it (StartingModel, with the picker's step);
     *  null with no provider in the door. */
    fun starting(lineup: DesktopLineup): Pair<String?, String> = chain.of(lineup)

    /** The window the terminal's sessions declare, fixed the first time it is asked for: the starting model's in
     *  [lineup], or [NO_HEAD_WINDOW] with no provider in the door. head-catalog: every session on the Splice gateway, in
     *  Claude Code's gateway mode, holds this window, since the launch sets CLAUDE_CODE_MAX_CONTEXT_TOKENS to it, and
     *  the gateway reports every count as a share of it. Seen on Claude Code 2.1.329, Nov 19, 2026: for a selector the
     *  variable is honoured (200000 held 200k, 400000 held 400k), and with it unset the session holds 1M, Claude Code's
     *  default for a model it does not know. */
    fun window(lineup: DesktopLineup): Long = records.get().window ?: run {
        val found = starting(lineup).first?.let(lineup::find)
        val window = found?.let { (provider, model) -> provider.catalog.contextWindowFor(model.upstream) }
            ?: NO_HEAD_WINDOW
        records.update { it.copy(window = it.window ?: window) }.window ?: window
    }

    /** The window the terminal's sessions declared, or null before the first launch fixed one. */
    fun declared(): Long? = records.get().window

    /** `splice claude door [--model]`. */
    suspend fun toDoor(model: String?): DesktopAct = lock.withLock {
        val lineup = lineup()
        chain.refusal(lineup, model)?.let { return@withLock DesktopAct.Refused(HttpStatus.BAD_REQUEST, it) }
        val was = lane()
        records.update { it.copy(lane = DOOR_LANE, defaultModel = model ?: it.defaultModel) }
        window(lineup)
        val (chosen, why) = chain.of(lineup, model)
        val said = if (was == DOOR_LANE) "claude is on the door" else "claude is on the door now"
        // decision 9: the door sends no Claude login, so a person whose `claude` ran Claude on its head is told the
        // cost
        // while no Claude account is added to splice, the one way Claude's models run on the door (Nov 17, 2026)
        val cost = CLAUDE_LEFT_BEHIND.takeIf { headHere() && lineup.leftOut.any { it.forwardsClientAuth } }
        val moved = listOfNotNull("$said at $door: a new `claude` session runs there", cost, login.laid())
        DesktopAct.Done(moved.joinToString("; "), chosen?.let { "$it ($why)" } ?: why)
    }

    /** `splice claude head`: a new `claude` session starts on its head, and one already on the door keeps running
     *  there. Until Nov 18, 2026 the move retired the terminal's key, so every session already on the door was refused
     *  from its next turn, mid-work. */
    suspend fun toHead(): DesktopAct = lock.withLock {
        if (!headHere()) {
            return@withLock DesktopAct.Refused(
                HttpStatus.CONFLICT,
                "splice.toml has no head whose command is claude, so there is no head to go back to " +
                    "(`splice add claude` adds one)",
            )
        }
        // The move back takes the door's starting model with it: kept, a model named with `door --model` came back
        // with the next `splice claude door`. On Nov 17, 2026 a Grok named at 9:33 AM, moved back from a minute later,
        // came back that evening, and every `claude` session started or restarted with no model named ran on Grok.
        records.update { it.copy(lane = HEAD_LANE, defaultModel = null) }
        val moved = "claude is on its head, on your own Claude login with Claude Code's own models: a new " +
            "`claude` session starts there, and sessions already on the Splice gateway keep running on it"
        DesktopAct.Done(listOfNotNull(moved, login.removed()).joinToString("; "))
    }
}

/** What `splice claude door` adds on a machine whose `claude` has a head to run Claude's models on (decision 9). */
private const val CLAUDE_LEFT_BEHIND = "Claude's models no longer run in claude, since the door sends no Claude " +
    "login and no Claude account is added to splice; add one with `splice console`, or `splice claude head` " +
    "brings them back on your own login"

/** The window a session declares with no provider in the door: Claude Code's own, until one is there to name it. */
private const val NO_HEAD_WINDOW = 200_000L
