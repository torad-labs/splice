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
package splice.gateway.head

import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import splice.gateway.wire.FrameRecording
import java.security.MessageDigest

internal class CompactionReplay(
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private data class Entry(val recording: FrameRecording, val startedAtMs: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    /** Null without a session: a retry cannot be tied to its first attempt. */
    fun key(sessionId: String?, upstreamBody: String): String? {
        val session = sessionId?.takeIf { it.isNotBlank() } ?: return null
        return "$session:${sha256Hex(upstreamBody)}"
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
     *  stream to the retry, so it is dropped. A recording a newer begin() replaced is left alone. */
    fun finish(key: String, recording: FrameRecording, keep: Boolean) {
        synchronized(lock) {
            val superseded = entries[key]?.recording !== recording
            if (!superseded && !keep) entries.remove(key)
        }
    }

    fun lookup(key: String): FrameRecording? = synchronized(lock) {
        sweep()
        entries[key]?.recording
    }

    /** A delivered replay has served its purpose; a second identical request runs upstream. */
    fun consumed(key: String) {
        synchronized(lock) { entries.remove(key) }
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

private const val DEFAULT_TTL_MS = 2 * 60 * 60 * 1000L
private const val DEFAULT_CAPACITY = 32
