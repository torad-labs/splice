// NEW: the answer ledger behind non-stream retries (2026-10-25): what a hold keeps, when it is dropped, how a newer
// hold supersedes an older one, and how a retry waits on a still-driving one. The collect-path sibling of
// [CompactionReplay]'s recordings — no frames to follow, so a hold settles the terminal's ONE collected reply instead.
// Since 2026-11-09 it holds every non-stream turn with a session, not only compactions: a Claude Code subagent re-sent
// one stream:false request 228 times over 25 hours (Nov 8-9), each re-send restarting five minutes of work from zero.
package splice.head.compaction

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import splice.core.failure.WireAnswer
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock

/** What a held non-stream turn answers its re-send with: the collected [body] and the [status] its own client would
 *  have read, a failure's included, so a re-send that waited on a failed turn is told that failure and never a 200.
 *  [answered]: the failure's answer, which a handed-over limit's headers are read from (HandOverHeaders). */
internal data class HeldReply(val body: JsonObject, val status: Int, val answered: WireAnswer? = null)

internal class HeldCollectAnswers(
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
    private val ttlMs: Long = RECORDING_TTL_MS,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    /** A non-stream turn's held reply: what the identical re-send awaits instead of starting the turn again. */
    private data class Held(val hold: CompletableDeferred<HeldReply?>, val startedAtMs: Long)

    private val lock = Any()
    private val answers = LinkedHashMap<String, Held>()

    /** The collect-path sibling of [CompactionReplay.begin]: a NON-STREAM turn announces itself at launch, and its
     *  collected reply settles [finish] later. A hold has no frames to follow, so the re-send waits on the deferred
     *  instead — like a stream follower riding a recording. Memory-only: a reply lives within this process's TTL
     *  exactly as any recording does. */
    fun begin(key: String): CompletableDeferred<HeldReply?> {
        val hold = CompletableDeferred<HeldReply?>()
        synchronized(lock) {
            sweep()
            answers[key] = Held(hold, clock())
        }
        return hold
    }

    /** Settles [hold]'s slot with the drive's reply, null for a drive that never reached its terminal (cancelled).
     *  A superseded launcher's settle (a newer hold replaced its entry) is left alone. [keep] mirrors the recordings'
     *  verdict: only a clean answer whose client left is kept for a later re-send; anything else is told to the
     *  re-sends already waiting and then dropped, so the next attempt runs upstream. */
    fun finish(key: String, hold: CompletableDeferred<HeldReply?>, answer: HeldReply?, keep: Boolean) {
        hold.complete(answer)
        val current = synchronized(lock) { answers[key]?.hold === hold }
        if (!current || keep) return
        synchronized(lock) { if (answers[key]?.hold === hold) answers.remove(key) }
    }

    /** What a re-send follows under [key], or null when nothing runs there. */
    fun find(key: String): CompletableDeferred<HeldReply?>? = synchronized(lock) {
        sweep()
        answers[key]?.hold
    }

    /** A delivered reply has served its purpose; a further identical attempt runs upstream. Identity like
     *  [CompactionReplay.consumed]: removal spends [delivered], so a newer hold begun under the same key stays. */
    fun consumed(key: String, delivered: CompletableDeferred<HeldReply?>) {
        synchronized(lock) { if (answers[key]?.hold === delivered) answers.remove(key) }
    }

    // Mirrors the recordings' sweep: past TTL everything goes; past capacity a settled hold goes
    // before one still driving.
    private fun sweep() {
        val now = clock()
        answers.entries.removeIf { now - it.value.startedAtMs > ttlMs }
        while (answers.size > capacity) {
            val victim = answers.entries.firstOrNull { it.value.hold.isCompleted }?.key ?: answers.keys.first()
            answers.remove(victim)
        }
    }
}
