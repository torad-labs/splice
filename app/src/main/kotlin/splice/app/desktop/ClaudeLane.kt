// NEW: door-default (docs/specs/door-default.md, decisions 1, 3 and 4). The lane the terminal's `claude` runs on, and
// what `splice claude`, `splice claude door` and `splice claude head` do to it.
//
// With no choice made the lane is the door when splice.toml has no head whose command is `claude`, and the head when
// it has one, so an upgrade changes no lane. A choice is kept in the lane's record. `door` refuses a `--model` the door
// does not serve and `head` refuses with no head to go back to, each changing nothing. `head` retires the terminal's
// key, so a session started on the door is refused from then on. The model a session starts on is Desktop's chain with
// the picker's step (StartingModel): the `--model` named now, or the one chosen the last time, or the pinned model of
// the first head in the picker (a signed-in account's), or the pinned model of the first head in the door.
package splice.app.desktop

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.config.FileHeldKey
import splice.core.wire.HttpStatus
import splice.launch.recipe.HEADS_DEPRECATED

/** Whether splice.toml has a head whose command is `claude`, read at each use. */
internal fun interface ClaudeHeadHere {
    operator fun invoke(): Boolean
}

internal class ClaudeLane(
    private val records: ClaudeLaneRecords,
    /** The terminal's key, which the door opens to for `claude`'s sessions and no other caller's. */
    val key: FileHeldKey,
    private val heads: DesktopHeads,
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
    suspend fun lineup(): DesktopLineup = heads.lineup()

    /** The model a session starts on in [lineup], and which rule chose it (StartingModel, with the picker's step);
     *  null with no head in the door. */
    fun starting(lineup: DesktopLineup): Pair<String?, String> = chain.of(lineup)

    /** The window the terminal's sessions declare, fixed the first time it is asked for: the starting model's in
     *  [lineup], or [NO_HEAD_WINDOW] with no head in the door. */
    fun window(lineup: DesktopLineup): Long = records.get().window ?: run {
        val found = starting(lineup).first?.let(lineup::find)
        val window = found?.let { (head, model) -> head.catalog.contextWindowFor(model.upstream) } ?: NO_HEAD_WINDOW
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
        val moved = listOfNotNull("$said at $door: a new `claude` session runs there", login.laid()).joinToString("; ")
        DesktopAct.Done(moved, chosen?.let { "$it ($why)" } ?: why)
    }

    /** `splice claude head`: the terminal's key retired, so a session started on the door is refused from now on. */
    suspend fun toHead(): DesktopAct = lock.withLock {
        if (!headHere()) {
            return@withLock DesktopAct.Refused(
                HttpStatus.CONFLICT,
                "splice.toml has no head whose command is claude, so there is no head to go back to " +
                    "(`splice add claude` adds one)",
            )
        }
        key.replace()
        records.update { it.copy(lane = HEAD_LANE) }
        val moved = "claude is on its head: $HEADS_DEPRECATED. Sessions started on the door are refused from now on"
        DesktopAct.Done(listOfNotNull(moved, login.removed()).joinToString("; "))
    }
}

/** The window a session declares with no head in the door: Claude Code's own, until a head is there to name one. */
private const val NO_HEAD_WINDOW = 200_000L
