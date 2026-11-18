// NEW: the harness in the drive (docs/specs/harness.md, decisions 3, 5 and 6). Before a turn's first round, its
// capabilities run while it is held, and the texts its thread carries are placed in its first user message through
// one function, on its first turn and on every later one; a refusal ends the turn before anything goes upstream.
package splice.head.turn

import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.head.compaction.SessionProjectLookup
import splice.head.port.HarnessEnd
import splice.head.port.HarnessTurn
import splice.head.port.Held
import splice.head.port.TurnHarness
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.retry.InflightGate
import splice.upstream.transport.TurnDeclined
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal class HarnessStep(
    private val provider: Provider,
    private val harness: TurnHarness?,
    private val folders: SessionProjectLookup,
) {
    /** The gate slots of the turns held on a capability right now: a slot is one turn's, compared by identity. */
    private val held: MutableSet<InflightGate.Slot> = ConcurrentHashMap.newKeySet()

    /** Runs [drive]'s capabilities and places what its thread carries; the refusal that ends the turn, else null.
     *  [holdable] is false for a turn whose response cannot be held open (one that does not stream). */
    suspend fun before(drive: TurnDrive, holdable: Boolean): TurnDeclined? {
        val harness = harness ?: return null
        val request = drive.inputs.harness ?: return null
        val end = try {
            harness.around(request, Holding(drive, holdable, held, folders))
        } finally {
            held -= drive.slot
        }
        return when (end) {
            is HarnessEnd.Refused -> TurnDeclined(FailureCause.Splice.Declined(end.why))
            is HarnessEnd.Go -> {
                val built = end.sending?.let { drive.inputs.built.sending(it) } ?: drive.inputs.built
                val placing = placed(built, end.texts)
                if (placing.chars > 0 || end.sending != null) drive.place(placing.turn.requestBody)
                end.rounds?.let(drive::answering)
                drive.held = drive.held.copy(textChars = placing.chars, unplaced = placing.unplaced)
                null
            }
        }
    }

    /** The head stops or retires: every held turn stops waiting and goes on as today (docs/specs/harness.md, decision
     *  6). Answers the slots of the turns that were held, which the stop keeps to their end instead of cutting them. */
    fun stop(): Set<InflightGate.Slot> {
        val kept = held.toSet()
        harness?.stop()
        return kept
    }

    /** The head runs again: turns are held again. */
    fun started() {
        harness?.started()
    }

    /** The one function every request of a thread gets its capabilities' texts through, in the order they ran, in
     *  the dialect's own shape. */
    private fun placed(built: BuiltTurn, texts: List<String>): Placing =
        texts.fold(Placing(built)) { placing, text ->
            val next = provider.withFirstUserText(placing.turn, text)
            // The same instance back says the dialect could not place it: the body goes as it is, and the row says so.
            if (next === placing.turn) {
                placing.copy(unplaced = true)
            } else {
                Placing(next, placing.chars + text.length, placing.unplaced)
            }
        }

    /** The request with the texts placed so far, their size, and whether one could not be placed. */
    private data class Placing(val turn: BuiltTurn, val chars: Long = 0L, val unplaced: Boolean = false)

    /** The turn held as a usage-limit hold holds one: the response opens at once and is pinged, the notice for a
     *  long silent turn is off, and the turn gives its place in the head's queue back until it goes on. Its record
     *  says what the hold was before anything else is undone, so a turn the client left mid-hold keeps it too. */
    private class Holding(
        private val drive: TurnDrive,
        override val holdable: Boolean,
        private val held: MutableSet<InflightGate.Slot>,
        private val folders: SessionProjectLookup,
    ) : HarnessTurn {
        override val id: String = drive.sentTurnId()

        override val sending: JsonObject get() = drive.inputs.built.requestBody

        override fun folder(session: String?): Path? = folders(session)

        override suspend fun hold() {
            held += drive.slot
            drive.channel.statusGate?.open()
            drive.emitter.ensureStarted()
            drive.emitter.holding(true)
            drive.slot.yieldPermit()
            drive.watchdog.holdStarted()
        }

        /** A turn held more than once (the model called a capability in more than one round) keeps every hold, in order,
         *  and their time together. */
        override suspend fun release(held: Held) {
            this.held -= drive.slot
            val before = drive.held
            val on = (listOfNotNull(before.on) + held.on).joinToString(",").ifEmpty { null }
            drive.held = before.copy(on = on, ms = before.ms + held.ms)
            try {
                drive.emitter.holding(false)
                drive.slot.retakePermit()
            } finally {
                drive.watchdog.holdEnded()
            }
        }
    }
}
