// NEW: 2026-11-10 — a turn that only ever reasons nothing ends with an honest line. On Nov 8 one claudex turn on
// gpt-6-luna ran 6.3 hours: its Responses stream carried completed reasoning items with no summary and 1,848 to 1,932
// characters of encrypted reasoning each, thousands in a row, and not one word or tool call. Every item read as progress
// to the watchdog, which holds a live path to the session's wall, so nothing ended it until the hourly socket
// rotations spent its recovery budget and it closed on a false "stayed overloaded". This reads what the stream carries,
// never the time between events: a model thinking in silence adds nothing to the run, and any real reasoning, text or
// tool call ends it, so neither is ever cut. Built like the admission's RefusalStreak: one run per turn, held on the
// turn's drive, read by the round drivers of both transports.
package splice.head.transport

import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.core.failure.ReasoningLoop
import splice.core.turn.TurnOutcome
import splice.core.util.ElapsedClock
import splice.core.util.JsonScalars
import splice.core.util.MonoClock
import java.util.concurrent.TimeUnit

/** One turn's run of completed reasoning items that carried nothing, across its rounds and socket rotations. */
internal class EmptyReasoningRun(private val clock: ElapsedClock = ElapsedClock { MonoClock.nowMs() }) {
    private var items = 0
    private var sinceMs = 0L
    private var tripped: ReasoningLoop? = null

    /** What one upstream event says about the run. */
    private enum class Carries { NOTHING, SOMETHING, NEITHER }

    /** Reads one upstream event: true once the run is long enough, in items and in time, to end the turn. */
    @Synchronized
    fun observe(event: JsonObject): Boolean {
        if (tripped != null) return true
        when (read(event)) {
            Carries.NOTHING -> extend()
            Carries.SOMETHING -> items = 0
            Carries.NEITHER -> Unit
        }
        return tripped != null
    }

    /** The run that ended the turn, or null while none did. */
    val loop: ReasoningLoop?
        @Synchronized get() = tripped

    /** [outcome] as the run it tripped on ends it: the stream was stopped there, so the translator's own failure (a
     *  stream with no terminal) is told as the empty reasoning it was. A round the model finished stands. */
    fun ended(outcome: TurnOutcome): TurnOutcome {
        val run = loop ?: return outcome
        val failure = outcome as? TurnOutcome.Failure ?: return outcome
        return failure.copy(
            cause = FailureCause.Provider.Empty(reasoningOnly = true, loop = run),
            evidence = failure.evidence.copy(
                message = "splice ended the turn after ${run.items} empty reasoning items in a row over " +
                    "${TimeUnit.MILLISECONDS.toSeconds(run.forMs)}s with no text or tool call",
            ),
        )
    }

    private fun extend() {
        val now = clock()
        if (items == 0) sinceMs = now
        items++
        val forMs = now - sinceMs
        if (items >= EMPTY_RUN_ITEMS && forMs >= EMPTY_RUN_MS) tripped = ReasoningLoop(items, forMs)
    }

    /** A completed reasoning item is empty or not by its own fields; any other event that carries text, reasoning or a
     *  tool call ends the run; the rest (lifecycle events, pings, a socket's close) leaves it as it is. What an event
     *  carries is [UpstreamEventKinds.hasContent], the one reading of it (summary text and reasoning text included), so
     *  this holds no list of fields of its own. */
    private fun read(event: JsonObject): Carries {
        val item = event["item"] as? JsonObject
        val carries = UpstreamEventKinds.hasContent(event, UpstreamEventKinds.kind(event))
        val reasoningDone = JsonScalars.str(event, "type") == ITEM_DONE && JsonScalars.str(item, "type") == REASONING
        val nothingMore = !carries && encryptedChars(item) <= EMPTY_REASONING_FLOOR_CHARS
        return when {
            reasoningDone && nothingMore -> Carries.NOTHING
            reasoningDone || carries -> Carries.SOMETHING
            else -> Carries.NEITHER
        }
    }

    /** The encrypted reasoning an item holds; a real step of thought holds more than [EMPTY_REASONING_FLOOR_CHARS]. */
    private fun encryptedChars(item: JsonObject?): Int = JsonScalars.strIfString(item?.get("encrypted_content")).length
}

// why: the Nov 8 loop's items each carried 1,848 to 1,932 characters of encrypted reasoning and nothing else. Across
// this machine's other claudex conversations the tenth percentile of an item is 2,040 to 4,364 characters and the
// median 5,516 to 8,420, so a real step of thought sits above this floor almost always, and one that falls under it is
// alone: no more than 3 such items ever came in a row outside the loop.
internal const val EMPTY_REASONING_FLOOR_CHARS = 2_000

// why: outside the loop no turn ever had more than 3 empty items in a row; the loop made 6,715 in 6.3 hours, about 18 a
// minute. A hundred is far past any real turn and still minutes, not hours, into a loop.
internal const val EMPTY_RUN_ITEMS = 100

// why: ten minutes of nothing but empty reasoning, with no text and no tool call, is past a model working through a
// hard step in short thoughts; at the loop's pace this bound, not the item count, is the one that trips.
internal const val EMPTY_RUN_MS = 600_000L

private const val ITEM_DONE = "response.output_item.done"
private const val REASONING = "reasoning"
