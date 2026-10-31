// NEW: the answer ledger behind non-stream compaction retries (2026-10-25): what a hold keeps,
// when it is dropped, how a newer hold supersedes an older one, and how a retry waits on a
// still-driving one. The collect-path sibling of [CompactionReplay]'s recordings — no frames to
// follow, so a hold settles the terminal's ONE collected JSON body instead.
package splice.head.compaction

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock

internal class HeldCollectAnswers(
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
    private val ttlMs: Long = RECORDING_TTL_MS,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    /** A non-stream compaction's held answer: what the identical retry awaits instead of prefilling. */
    private data class Held(val hold: CompletableDeferred<JsonObject?>, val startedAtMs: Long)

    private val lock = Any()
    private val answers = LinkedHashMap<String, Held>()

    /** The collect-path sibling of [CompactionReplay.begin]: a NON-STREAM compaction announces itself
     *  at launch, and its collected body settles [finish] later. A hold has no frames to follow, so
     *  the retry waits on the deferred instead — like a stream follower riding a recording.
     *  Memory-only: an answer lives within this process's TTL exactly as any recording does. */
    fun begin(key: String): CompletableDeferred<JsonObject?> {
        val hold = CompletableDeferred<JsonObject?>()
        synchronized(lock) {
            sweep()
            answers[key] = Held(hold, clock())
        }
        return hold
    }

    /** Settles [hold]'s slot: the collected body for a cleanly ended drive that outlived its client,
     *  null otherwise. A superseded launcher's settle (a newer hold replaced its entry) is left alone.
     *  [keep] mirrors the recordings' verdict — a delivered turn keeps nothing; its client has it. */
    fun finish(key: String, hold: CompletableDeferred<JsonObject?>, answer: JsonObject?, keep: Boolean) {
        hold.complete(answer)
        val current = synchronized(lock) { answers[key]?.hold === hold }
        if (!current || keep) return
        synchronized(lock) { if (answers[key]?.hold === hold) answers.remove(key) }
    }

    /** What a retry follows under [key], or null when nothing runs there. */
    fun find(key: String): CompletableDeferred<JsonObject?>? = synchronized(lock) {
        sweep()
        answers[key]?.hold
    }

    /** A delivered answer has served its purpose; a further identical attempt runs upstream. Identity
     *  like [CompactionReplay.consumed]: removal spends [delivered], so a newer hold begun under the
     *  same key stays. */
    fun consumed(key: String, delivered: CompletableDeferred<JsonObject?>) {
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
