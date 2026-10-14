// NEW: Oct 13, 2026 — a listing row's transcript fields, kept against the transcript file they came from.
//
// A row's `last`, `model` and `title` come only from the session's transcript (and its model moves), so they are kept
// against that file's stamp: a quiet /api/sessions poll answers each row from memory after one stat, where it located,
// resolved and stat'ed the file two to four times and redrew the card line through its markdown rules on every row
// every five seconds (splice-sessions, Oct 13: 686 rows). The account and turn fields change without the file changing
// and stay live (SessionRowFacts).
package splice.sessions.http

import kotlinx.serialization.json.JsonElement
import splice.sessions.registry.SessionRecord
import splice.sessions.transcript.ModelMove
import splice.sessions.transcript.SessionActivity
import splice.sessions.transcript.SessionTitleOf
import splice.sessions.transcript.SessionTranscriptViewEnabled
import splice.sessions.transcript.SessionTranscripts
import java.nio.file.Path

// why: every row one listing shows, with room for the history window's ended sessions; a row is three short fields.
private const val HELD_ROWS = 4096

/** What one row says of its transcript. */
internal data class TranscriptFields(val last: JsonElement, val model: JsonElement, val title: String?)

internal class RowTranscriptFields(
    private val transcripts: SessionTranscripts,
    private val viewEnabled: SessionTranscriptViewEnabled,
    private val titleOf: SessionTitleOf,
) {
    /** Everything the fields are read from besides the file's bytes, and the stamp that stands for those. */
    private data class Key(
        val stamp: String,
        val cwd: String?,
        val trees: List<Path>,
        val running: Boolean,
        val name: String?,
        val moves: List<ModelMove>,
    )

    private data class Held(val key: Key, val fields: TranscriptFields)

    private val held = LinkedHashMap<String, Held>()

    /** [record]'s fields, read from its transcript under [trees] only when the file or what they depend on changed. */
    fun of(record: SessionRecord, trees: List<Path>): TranscriptFields {
        // A session that is not running shows the reply before a message of his that was taken back or never answered.
        val running = record.status.state == "working" || record.status.state == "busy"
        val id = record.sessionId
        val stamp = id?.takeIf { viewEnabled() }?.let { transcripts.stamp(it, trees, record.process.cwd) }
        if (id == null || stamp == null) return read(record, trees, running)
        val key = Key(stamp, record.process.cwd, trees, running, record.name, transcripts.moves(id))
        synchronized(held) { held[id]?.takeIf { it.key == key } }?.let { return it.fields }
        val fields = read(record, trees, running)
        synchronized(held) {
            held.remove(id)
            held[id] = Held(key, fields)
            if (held.size > HELD_ROWS) held.remove(held.keys.first())
        }
        return fields
    }

    private fun read(record: SessionRecord, trees: List<Path>, running: Boolean): TranscriptFields {
        val id = record.sessionId
        val cwd = record.process.cwd
        val said = if (running) SessionActivity::last else SessionActivity::answered
        return TranscriptFields(
            last = said(id, cwd, trees, transcripts, viewEnabled),
            model = SessionActivity.model(id, cwd, trees, transcripts, viewEnabled),
            title = titleOf(record),
        )
    }
}
