// NEW: V4-169 (2026-09-19) — the ONE place a transcript's assistant rows are moved onto a head's
// model. Extracted from ResumeAcrossHeads (V4-115), where it ran only on a COPY, because the shared
// transcript tree (V4-168) gave it two more callers that both rewrite IN PLACE: the `-r SESSION_ID`
// launch on a head that already sees the session through the shared tree, and the SessionStart
// resume hook, which is the only moment the daemon learns which session the picker or `-c` chose.
//
// WHY IT EXISTS. Claude Code restores a resumed session's model from the transcript and refuses one
// the head does not serve — "Session model <X> could not be restored", reason "is not in the
// availableModels allowlist" (verified in the 2.1.257 binary, V4-115). The allowlist is the head's
// own roster, written by the materializer, and it is RIGHT: another head's id does not belong in it.
// So the transcript is what moves, and only the rows that name a model — `message.model` of
// `type: assistant` rows — in the transcript itself and in every jsonl under its `<id>/` subdir.
//
// Rows are re-encoded only when they change, so history this head did not touch stays
// byte-identical; an unparseable line is history too and is never dropped. A read or write failure
// throws: half-rewritten history is precisely what leaves a resumed session on a model the head
// cannot serve, and each caller decides what a failure means for its own outcome.
package splice.core.launch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

private const val TRANSCRIPT_SUFFIX = ".jsonl"
private const val TRANSCRIPT_TYPE = "type"
private const val TRANSCRIPT_MESSAGE = "message"
private const val ASSISTANT_TYPE = "assistant"

public class TranscriptModelRewrite {

    private val json = Json { ignoreUnknownKeys = true }

    /** Rewrite every assistant row's `message.model` to [pinnedModel] in [transcript] and in every
     *  jsonl under its sibling `<id>/` subdir (the subagent transcripts and tool results Claude Code
     *  keeps beside it). Returns the number of rows changed; throws [IOException] on the first file
     *  that could not be read or written. */
    public fun rewrite(transcript: Path, pinnedModel: String): Int {
        var rewritten = rewriteFile(transcript, pinnedModel)
        val subdir = transcript.resolveSibling(transcript.fileName.toString().removeSuffix(TRANSCRIPT_SUFFIX))
        if (Files.isDirectory(subdir, NOFOLLOW_LINKS)) {
            jsonlUnder(subdir).forEach { file -> rewritten += rewriteFile(file, pinnedModel) }
        }
        return rewritten
    }

    private fun jsonlUnder(dir: Path): List<Path> = Files.walk(dir).use { stream ->
        stream.filter { it.fileName.toString().endsWith(TRANSCRIPT_SUFFIX) && Files.isRegularFile(it) }.toList()
    }

    private fun rewriteFile(file: Path, pinnedModel: String): Int {
        val text = Cancellables.runCatchingCancellable { Files.readString(file) }
            .getOrElse { cause -> throw IOException("$file unreadable (${SafeFailureText.render(cause)})") }
        var changed = 0
        val rows = text.split("\n").map { row ->
            val rewritten = rewriteRow(row, pinnedModel)
            if (rewritten != null) changed += 1
            rewritten ?: row
        }
        if (changed == 0) return 0
        Cancellables.runCatchingCancellable { Files.writeString(file, rows.joinToString("\n")) }
            .exceptionOrNull()
            ?.let { cause -> throw IOException("$file unwritable (${SafeFailureText.render(cause)})") }
        return changed
    }

    /** The rewritten row, or null when this row is not an assistant row on another model — an
     *  unparseable line included: a transcript is history, and history is never silently dropped. */
    private fun rewriteRow(row: String, pinnedModel: String): String? {
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-19 (V4-169): an unparseable line is kept verbatim BY DESIGN (see the KDoc); null here means "leave this row alone", never a swallowed failure.
        val obj = Cancellables.runCatchingCancellable { json.parseToJsonElement(row).jsonObject }
            .getOrNull() ?: return null
        val message = assistantMessage(obj)
        if (message == null || JsonScalars.str(message, Keys.MODEL) == pinnedModel) return null
        val fixedMessage = JsonObject(message.toMutableMap().apply { put(Keys.MODEL, JsonPrimitive(pinnedModel)) })
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject(obj.toMutableMap().apply { put(TRANSCRIPT_MESSAGE, fixedMessage) }),
        )
    }

    private fun assistantMessage(row: JsonObject): JsonObject? =
        if (JsonScalars.str(row, TRANSCRIPT_TYPE) == ASSISTANT_TYPE) row[TRANSCRIPT_MESSAGE] as? JsonObject else null
}
