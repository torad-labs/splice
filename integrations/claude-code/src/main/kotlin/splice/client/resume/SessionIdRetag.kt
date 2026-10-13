// NEW: Oct 13, 2026 (the President's ruling on V4-169, via splice-lead) — the cross-head `-r` copy is a session of its
// own. A session another head wrote, which this head sees through the shared projects tree, is resumed as a copy beside
// it under a new id, and the original is left byte for byte: another head resuming it sees it as it was written
// (ResumeAcrossHeads). Claude Code writes the session's id into each row it appends (`"sessionId":"<id>"`), so the copy's
// rows name the copy, and its picker and its next resume read one session, not two sharing an id.
//
// Only that field is touched, and only where it names the original: the id is a UUID, so the exact field spelling
// Claude Code writes cannot occur inside a message's text, where a quote is escaped. Every other byte is kept as read.
package splice.client.resume

import splice.client.transcript.TranscriptLines
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/** Retags rows of session [from] as session [to]. */
internal class SessionIdRetag(from: String, to: String) {
    private val old = "\"sessionId\":\"$from\""
    private val new = "\"sessionId\":\"$to\""

    /** [source]'s rows written to the new file [target], each naming [to] where it named the original. */
    fun copy(source: Path, target: Path) {
        BufferedOutputStream(Files.newOutputStream(target, CREATE_NEW, WRITE)).use { out ->
            // A flag, not a row count: as in TranscriptModelRewrite, the separator goes before every row but the first.
            var first = true
            val _ = TranscriptLines.read(source) { row ->
                if (!first) out.write('\n'.code)
                first = false
                val text = row.text()
                if (old in text) {
                    out.write(text.replace(old, new).toByteArray(Charsets.UTF_8))
                } else {
                    out.write(row.bytes, 0, row.length)
                }
            }
        }
    }
}
