// NEW: the answers of compactions whose clients gave up, held for their retries (2026-09-05).
//
// Claude Code arms a 600 s wall-clock cap on an auto-compaction (`dKt=600000`, `recovery-timeout`,
// not reset by frames), aborts at it, and retries the SAME request minutes later — on 2026-09-05
// eight claudex compactions died at 600-602 s and four of the six retries were byte-identical, each
// abort throwing away a 600 s upstream read. So a compaction's turn outlives its client
// (TurnStreamer.driveDetachable), its frames are recorded (FrameRecording), and a retry that would
// send the SAME BYTES upstream is answered from the recording (TurnPreparation → LocalResponses):
// complete, in one burst, or still in flight, followed to its end. Keyed on session + a hash of the
// upstream request body, which is what "the same compaction" means on the wire — a retry that
// differs (a message arrived in between) simply runs upstream as before. Only compactions whose
// client actually left are kept; a compaction delivered to its client has no retry to serve.
// V4-216: a finished answer is also written to [recordings] and read back through it on a miss, so
// the retry finds it after a daemon restart; without a store (null) the replay is memory-only.
package splice.head.compaction

import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapReservations
import splice.core.turn.TurnMeta
import splice.core.util.ElapsedClock
import splice.core.util.JsonWire
import splice.core.util.MonoClock
import splice.head.wire.FrameRecording
import splice.upstream.memory.JvmHeap
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

internal class CompactionReplay(
    private val recordings: CompactionRecordings? = null,
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
    private val ttlMs: Long = RECORDING_TTL_MS,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val heap: HeapReservations = JvmHeap.budget,
) {
    private data class Entry(val recording: FrameRecording, val startedAtMs: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    /** Null without a session: a retry cannot be tied to its first attempt. The hash is the body
     *  BEFORE the compaction tail when the preparation recorded one (TurnMeta.compactionRequestHash):
     *  a project resolved late or an instructions file edited between attempts changes the tail,
     *  never the client's bytes, and the client is retrying THIS compaction (review 2026-09-14). */
    fun key(meta: TurnMeta, upstreamBody: String): String? =
        key(meta.sessionId, upstreamBody, meta.compactionRequestHash)

    fun key(sessionId: String?, upstreamBody: String, bodyHash: String? = null): String? {
        val session = sessionId?.takeIf { it.isNotBlank() } ?: return null
        return "$session:${bodyHash ?: sha256Hex(upstreamBody)}"
    }

    /** A cached pre-tail identity needs neither a wire string nor a second tree traversal. */
    fun key(meta: TurnMeta, upstreamBody: JsonObject): String? =
        key(meta.sessionId, upstreamBody, meta.compactionRequestHash)

    fun key(sessionId: String?, upstreamBody: JsonObject, bodyHash: String? = null): String? {
        val session = sessionId?.takeIf { it.isNotBlank() } ?: return null
        return "$session:${bodyHash ?: this.bodyHash(upstreamBody)}"
    }

    /** The hash [key] uses for a body: exposed so the preparation can record it before tailing. */
    fun bodyHash(body: String): String = sha256Hex(body)

    /** Hashes the borrowed wire tree without creating a body string or UTF-8 array. */
    fun bodyHash(body: JsonObject): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(OutputStream.nullOutputStream(), digest).use { JsonWire.write(body, it) }
        return java.util.HexFormat.of().formatHex(digest.digest())
    }

    /** A compaction's recording, from its first frame: a retry may attach while it is in flight. */
    fun begin(key: String, recording: FrameRecording) {
        synchronized(lock) {
            sweep()
            entries[key] = Entry(recording, clock())
        }
    }

    /** The drive ended. [keep] is the caller's verdict — the client was gone AND the terminal
     *  ended cleanly (TurnTerminal.endedCleanly); anything else would replay a truncated or failed
     *  stream to the retry, so it is dropped. A recording a newer begin() replaced is left alone.
     *  A kept one is stored under the lock, so a replay consumed meanwhile cannot leave a file behind. */
    fun finish(key: String, recording: FrameRecording, keep: Boolean) {
        synchronized(lock) {
            val superseded = entries[key]?.recording !== recording
            if (!superseded && !keep) entries.remove(key)
            if (!superseded && keep) {
                recordings?.save(key, recording.generation, recording.frames())
            }
        }
    }

    fun lookup(key: String): FrameRecording? = synchronized(lock) {
        sweep()
        entries[key]?.recording ?: restored(key)
    }

    /** A delivered replay has served its purpose; a second identical request runs upstream. It spends [delivered], not the key:
     *  consumption runs after the response is written, so a newer compaction may have begun under the same key meanwhile, and
     *  its recording (and its stored copy) is not this replay's to remove. */
    fun consumed(key: String, delivered: FrameRecording) {
        synchronized(lock) {
            if (entries[key]?.recording?.generation == delivered.generation) entries.remove(key)
            // The durable copy is spent by the delivered recording's own generation, which no cache needs to remember: a newer answer
            // kept at the key since lives under another generation and stays.
            recordings?.remove(key, delivered.generation)
        }
    }

    /** An answer a previous process kept: complete and whole by construction (only those are saved). */
    private fun restored(key: String): FrameRecording? {
        val kept = recordings?.load(key) ?: return null
        val recording = FrameRecording(heap, kept.generation)
        kept.frames.forEach(recording::append)
        recording.complete(whole = true)
        entries[key] = Entry(recording, clock())
        return recording
    }

    // Past capacity, a settled recording goes before one still in flight: begin() inserts at the
    // START of a compaction, so insertion order alone would evict the oldest-begun entry while its
    // drive is still running and its retry has not arrived yet (review of PR 137). Only when every
    // entry is in flight does the oldest go.
    private fun sweep() {
        val now = clock()
        entries.entries.removeIf { now - it.value.startedAtMs > ttlMs }
        while (entries.size > capacity) {
            val victim = entries.entries.firstOrNull { it.value.recording.isComplete }?.key ?: entries.keys.first()
            entries.remove(victim)
        }
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

private const val DEFAULT_CAPACITY = 32
