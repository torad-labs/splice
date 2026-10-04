// NEW: V4-421 — which listed sessions have a transcript to resume. /api/sessions lists what Claude
// Code's registry lists, and a process can register there without ever writing a transcript (a
// messaging bridge does), so a listed session is not a resumable one. The rule is the resume route's
// (ResumeAcrossHeads.plan): a regular file with conversation bytes in some HEAD's own tree, the trees the
// launch searches. The vanilla tree is not among them unless a head links to it, because a resume that
// is refused for it is the 404 this exists to keep off the row.
package splice.sessions.http

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import splice.core.util.WallClock
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptLookup
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

// why: a session that wrote its first message a moment after the last look reads resumable by the
// next poll; the durable history index holds its own scan for the same ten seconds.
private const val MISSING_MS = 10_000L

// why: a transcript with bytes keeps them, so a held yes is trusted for minutes; a deleted file still
// stops reading resumable within six. Measured on the everyday daemon, all 29 listed sessions cost
// about a second to look at together, and the first poll looks at all of them.
private const val FOUND_MS = 300_000L

// why: the sessions first measured in one poll would otherwise all expire in one request, a second-long
// stall on every renewal; each id's own spread walks their renewals apart.
private const val FOUND_SPREAD_MS = 60_000L

// why: Knuth's multiplicative-hash constant, 2654435761 as a signed Int, which scatters neighbouring integers.
private const val GOLDEN = -1_640_531_535

/** What one listing measured: the ids that can be resumed, or null when nothing was measured. */
internal class Resumability(private val resumable: Set<String>?) {
    /** Writes `resumable` on a row for [session]. Writes nothing when nothing was measured, or the row has
     *  no id: absence claims nothing. */
    fun mark(row: JsonObjectBuilder, session: String?) {
        if (resumable != null && session != null) row.put("resumable", session in resumable)
    }
}

internal class ResumableSessions(
    private val transcripts: SessionTranscripts,
    private val roots: List<Path>,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private data class Verdict(val resumable: Boolean, val at: Long)

    private val held = ConcurrentHashMap<String, Verdict>()

    /** Which of [ids] can be resumed, or nothing measured when no head has a tree to ask: no tree read is
     *  no claim made. Each id is measured through the transcript port (which walks every tree once, about
     *  a few dozen milliseconds) and the answer held, so a poll of the whole list re-asks only the ids
     *  whose answer has expired. An id that left the list is forgotten. */
    fun among(ids: Set<String>): Resumability {
        if (roots.isEmpty()) return Resumability(null)
        held.keys.retainAll(ids)
        val now = clock()
        return Resumability(ids.filterTo(HashSet()) { resumable(it, now) })
    }

    private fun resumable(id: String, now: Long): Boolean {
        val known = held[id]
        if (known != null && now - known.at in 0 until lifetime(id, known)) return known.resumable
        return measure(id).also { held[id] = Verdict(it, now) }
    }

    private fun lifetime(id: String, verdict: Verdict): Long =
        if (verdict.resumable) FOUND_MS + spread(id) else MISSING_MS

    /** An id's own offset within [FOUND_SPREAD_MS]. The hash is mixed first: ids that differ in a
     *  character or two have hashes a few units apart, which would put them all in the same instant. */
    private fun spread(id: String): Long = Math.floorMod((id.hashCode() * GOLDEN).toLong(), FOUND_SPREAD_MS)

    /** A lookup that refuses the id (not a session id) is a session the resume route also refuses. */
    private fun measure(id: String): Boolean = when (val lookup = transcripts.page(id, roots, null, 1)) {
        is TranscriptLookup.Found -> lookup.page.path.let(Path::of).toFile().length() > 0L
        is TranscriptLookup.Missing, is TranscriptLookup.Refused -> false
    }
}
