// NEW: 2026-11-10 — a turn that only ever reasons nothing ends with an honest line. On Nov 8 one claudex turn on
// gpt-6-luna ran 6.3 hours: its Responses stream carried completed reasoning items with no summary and 1,848 to 1,932
// characters of encrypted reasoning each, thousands in a row, and not one word or tool call. Every item read as progress
// to the watchdog, which holds a live path to the session's wall, so nothing ended it until the hourly socket
// rotations spent its recovery budget and it closed on a false "stayed overloaded". This reads what the stream carries,
// never the time between events: a model thinking in silence adds nothing to the run, and any real reasoning, text or
// tool call ends it, so neither is ever cut. Built like the admission's RefusalStreak: one run per turn, held on the
// turn's drive, read by the round drivers of both transports.
package splice.head.transport

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.core.failure.ReasoningLoop
import splice.core.turn.TurnOutcome
import splice.core.util.ElapsedClock
import splice.core.util.JsonScalars
import splice.core.util.MonoClock

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
                    "${run.forMs / MS_PER_SECOND}s with no text or tool call",
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
     *  tool call ends the run; the rest (lifecycle events, pings, a socket's close) leaves it as it is. */
    private fun read(event: JsonObject): Carries {
        val item = event[FIELD_ITEM] as? JsonObject
        val reasoningDone = JsonScalars.str(event, FIELD_TYPE) == ITEM_DONE &&
            JsonScalars.str(item, FIELD_TYPE) == REASONING
        return when {
            reasoningDone -> if (item != null && emptyReasoning(item)) Carries.NOTHING else Carries.SOMETHING
            UpstreamEventKinds.hasContent(event, UpstreamEventKinds.kind(event)) -> Carries.SOMETHING
            else -> Carries.NEITHER
        }
    }

    /** No summary text, no reasoning text, and no more encrypted reasoning than [EMPTY_REASONING_FLOOR_CHARS]. */
    private fun emptyReasoning(item: JsonObject): Boolean =
        blankTexts(item[FIELD_SUMMARY]) && blankTexts(item[FIELD_CONTENT]) &&
            JsonScalars.strIfString(item[FIELD_ENCRYPTED]).length <= EMPTY_REASONING_FLOOR_CHARS

    private fun blankTexts(parts: JsonElement?): Boolean =
        (parts as? JsonArray).orEmpty().all { JsonScalars.strIfString((it as? JsonObject)?.get(FIELD_TEXT)).isBlank() }
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

private const val MS_PER_SECOND = 1_000L
private const val FIELD_TYPE = "type"
private const val FIELD_ITEM = "item"
private const val FIELD_SUMMARY = "summary"
private const val FIELD_CONTENT = "content"
private const val FIELD_TEXT = "text"
private const val FIELD_ENCRYPTED = "encrypted_content"
private const val ITEM_DONE = "response.output_item.done"
private const val REASONING = "reasoning"
