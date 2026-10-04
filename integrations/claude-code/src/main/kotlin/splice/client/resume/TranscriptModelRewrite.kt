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
// A row that moves loses its THINKING with its model. A thinking block is signed by the model that
// wrote it (or carries splice's `splice-synth-v1` stand-in when a head like Kimi signs nothing), and
// once the row claims the pinned model Claude Code replays that signature to an upstream that verifies
// it: Anthropic refuses the whole request, "Invalid signature in thinking block", on every turn.
// Claude Code strips thinking and retries on that error only when it arrives as an HTTP 400, and a
// head's stream has already answered 200, so the retag is the last moment the block is known to be
// foreign. A row that held only thinking keeps its place (its uuid is the next row's parentUuid) with
// the text block Claude Code itself writes when that recovery strips a message bare (kcr in 2.1.281,
// xmr in 2.1.282, wwr in 2.1.283, the same bytes), a shape it already loads, merges by message.id and
// replays.
//
// Rows are re-encoded only when they change, so history this head did not touch stays
// byte-identical; an unparseable line is history too and is never dropped. A read or write failure
// throws: half-rewritten history is precisely what leaves a resumed session on a model the head
// cannot serve, and each caller decides what a failure means for its own outcome.
package splice.client.resume

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.client.Keys
import splice.client.resume.originals.TranscriptOriginals
import splice.client.transcript.CONTENT
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Claude Code's transcript extension — the one declaration; ResumeAcrossHeads reads it too. */
internal const val TRANSCRIPT_SUFFIX: String = ".jsonl"
private const val TRANSCRIPT_TYPE = "type"
private const val TRANSCRIPT_MESSAGE = "message"
private const val ASSISTANT_TYPE = "assistant"

/** Anthropic's model namespace: a row there is a model a client on its own login can restore. */
private const val CLAUDE_ID_PREFIX = "claude-"

/** The two steps of a rewrite a test must fail deterministically: writing the new bytes and the swap. */
public interface TranscriptFs {
    public fun write(path: Path, bytes: ByteArray)

    public fun move(source: Path, target: Path, vararg options: CopyOption)
}

private object ProcessTranscriptFs : TranscriptFs {
    /** The bytes are forced to disk before this returns: a rename can reach the disk before the data
     *  it names, so a crash after an unforced move could leave an empty transcript in the original's place. */
    override fun write(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        Files.move(source, target, *options)
    }
}

public class TranscriptModelRewrite(
    private val fs: TranscriptFs = ProcessTranscriptFs,
    private val originals: TranscriptOriginals = TranscriptOriginals(StatePaths()),
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** The blocks a signature rides on — the two Claude Code's own strip removes (aEt/Tcr). */
    private val thinkingTypes = setOf("thinking", "redacted_thinking")

    /** Claude Code's stand-in for a message its signature recovery leaves empty: kcr() in 2.1.281,
     *  xmr() in 2.1.282 and wwr() in 2.1.283, byte for byte. */
    private val thinkingRemoved =
        json.parseToJsonElement("""{"type":"text","text":"[Thinking removed]","citations":[]}""")

    /** Rewrite to [pinnedModel], without its thinking blocks (see the header), every assistant row
     *  whose `message.model` the head does NOT serve, in [transcript] and in every jsonl under its
     *  sibling `<id>/` subdir (the subagent transcripts and tool results Claude Code keeps beside
     *  it). A row on a model in [served] — the head's roster,
     *  the same list its `availableModels` allowlist is written from — is restored by Claude Code as
     *  it is, so it stays (v0.4.0 review: claude-splice's tree is the operator's main
     *  ~/.claude/projects, and moving its opus rows onto fable rewrote history for nothing). Returns
     *  the number of rows changed; throws [IOException] on the first file that could not be read or
     *  written. A null [served] is a head whose client picks its own models ([clientPicked]). */
    public fun rewrite(transcript: Path, pinnedModel: String, served: Collection<String>?): Int {
        val subdir = transcript.resolveSibling(transcript.fileName.toString().removeSuffix(TRANSCRIPT_SUFFIX))
        val children = if (Files.isDirectory(subdir, NOFOLLOW_LINKS)) jsonlUnder(subdir) else emptyList()
        val files = listOf(transcript) + children
        val policy = served?.let { roster ->
            val kept = roster.toSet() + pinnedModel
            RowPolicy(pinnedModel) { model -> model in kept }
        } ?: clientPicked(transcript)
        val prepared = files.map { prepareFile(it, policy) }
        val rewritten = prepared.sumOf { it.changed }
        if (rewritten == 0) {
            originals.rememberIfKept(transcript)
            return 0
        }
        originals.preserve(transcript, files)
        if (prepared.any { readText(it.file) != it.original }) {
            throw IOException("Transcript changed while preserving its original; nothing was rewritten")
        }
        prepared.forEach(::publish)
        return rewritten
    }

    /** Whether a row on this model stays where it is. */
    private fun interface KeptModel {
        operator fun invoke(model: String?): Boolean
    }

    /** Which rows stay ([keeps]) and the model a moved row takes ([target]; null keeps the row's own). */
    private data class RowPolicy(val target: String?, val keeps: KeptModel)

    /** V4-449: where the client picks its own models no roster exists to move onto. A row on a Claude model
     *  stays as it is; a row on another vendor's model moves, without its thinking, onto the newest Claude
     *  model this transcript used. With no Claude row there is nothing to move onto: the row still loses its
     *  thinking (another vendor's signature fails upstream) and keeps its model, which the picker replaces. */
    private fun clientPicked(transcript: Path): RowPolicy {
        val newest = readText(transcript).split("\n").asReversed().firstNotNullOfOrNull(::claudeModelOf)
        return RowPolicy(newest) { model -> isNativeClaude(model) }
    }

    private fun claudeModelOf(row: String): String? {
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-30 (V4-449): an unparseable line names no model; rewriteRow keeps it verbatim.
        val obj = Cancellables.runCatchingCancellable { json.parseToJsonElement(row).jsonObject }
            .getOrNull() ?: return null
        return JsonScalars.str(assistantMessage(obj), Keys.MODEL)?.takeIf(::isNativeClaude)
    }

    /** Discovery IDs use a head's double-hyphen namespace, not the native Claude model namespace. */
    private fun isNativeClaude(model: String?): Boolean =
        model?.startsWith(CLAUDE_ID_PREFIX) == true && "--" !in model

    private fun readText(file: Path): String = Cancellables.runCatchingCancellable { Files.readString(file) }
        .getOrElse { cause -> throw IOException("$file unreadable (${SafeFailureText.render(cause)})") }

    private fun jsonlUnder(dir: Path): List<Path> = Files.walk(dir).use { stream ->
        val root = dir.toRealPath()
        stream.filter { it.fileName.toString().endsWith(TRANSCRIPT_SUFFIX) && Files.isRegularFile(it) }
            .map { file ->
                if (!file.toRealPath().startsWith(root)) {
                    throw IOException("Nested transcript resolves outside its session")
                }
                file
            }.toList()
    }

    private data class PreparedRewrite(val file: Path, val changed: Int, val original: String, val bytes: ByteArray?)

    private fun prepareFile(file: Path, policy: RowPolicy): PreparedRewrite {
        val text = readText(file)
        var changed = 0
        val rows = text.split("\n").map { row ->
            val rewritten = rewriteRow(row, policy)
            if (rewritten != null) changed += 1
            rewritten ?: row
        }
        val bytes = if (changed == 0) null else rows.joinToString("\n").toByteArray(Charsets.UTF_8)
        return PreparedRewrite(file, changed, text, bytes)
    }

    private fun publish(prepared: PreparedRewrite) {
        val bytes = prepared.bytes ?: return
        Cancellables.runCatchingCancellable { replace(prepared.file, prepared.original, bytes) }
            .exceptionOrNull()
            ?.let { cause -> throw IOException("${prepared.file} unwritable (${SafeFailureText.render(cause)})") }
    }

    /** V4-259: the new bytes go to a temp file beside the transcript, which is moved over it in one step,
     *  so a write that dies partway leaves the user's transcript exactly as it was; the temp file goes
     *  either way. The in-place write this replaced wrote through a link and refused a read-only file,
     *  and so does this: the file a link names is the one replaced, and a rename, which a read-only file
     *  does not stop, is not attempted on one. The file's permissions carry over to the new one. */
    private fun replace(file: Path, original: String, bytes: ByteArray) {
        val target = file.toRealPath()
        if (!Files.isWritable(target)) throw IOException("$target is read-only")
        val staged = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        Cancellables.runCatchingCancellable {
            Cancellables.discard(
                runCatching { Files.setPosixFilePermissions(staged, Files.getPosixFilePermissions(target)) },
                "no POSIX permissions on this filesystem, so there are none to carry over",
            )
            fs.write(staged, bytes)
            ensureUnchanged(target, original)
            fs.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            Cancellables.discard(
                runCatching { Files.deleteIfExists(staged) },
                "the temp file's cleanup is best-effort; the write failure rethrows",
            )
            throw it
        }
    }

    private fun ensureUnchanged(file: Path, original: String) {
        if (readText(file) != original) throw IOException("Transcript changed while staging its rewrite")
    }

    /** The rewritten row, or null when this row is not an assistant row on another model — an
     *  unparseable line included: a transcript is history, and history is never silently dropped. */
    private fun rewriteRow(row: String, policy: RowPolicy): String? {
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-19 (V4-169): an unparseable line is kept verbatim BY DESIGN (see the KDoc); null here means "leave this row alone", never a swallowed failure.
        val obj = Cancellables.runCatchingCancellable { json.parseToJsonElement(row).jsonObject }
            .getOrNull() ?: return null
        val fixedMessage = assistantMessage(obj)?.let { moved(it, policy) } ?: return null
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject(obj.toMutableMap().apply { put(TRANSCRIPT_MESSAGE, fixedMessage) }),
        )
    }

    /** [message] moved under [policy]: onto its target model, without its thinking. Null when it stays as it is. */
    private fun moved(message: JsonObject, policy: RowPolicy): JsonObject? {
        if (policy.keeps(JsonScalars.str(message, Keys.MODEL))) return null
        val stripped = withoutThinking(message[CONTENT])
        if (policy.target == null && stripped == null) return null
        return JsonObject(
            message.toMutableMap().apply {
                policy.target?.let { put(Keys.MODEL, JsonPrimitive(it)) }
                stripped?.let { put(CONTENT, it) }
            },
        )
    }

    /** [content] without its thinking blocks, or null when it holds none (or is not a block list) and
     *  stays exactly as it is. Emptied, it becomes [thinkingRemoved]: the row is never dropped. */
    private fun withoutThinking(content: JsonElement?): JsonArray? {
        val blocks = content as? JsonArray ?: return null
        val kept = blocks.filterNot { block -> JsonScalars.str(block as? JsonObject, TRANSCRIPT_TYPE) in thinkingTypes }
        if (kept.size == blocks.size) return null
        return JsonArray(kept.ifEmpty { listOf(thinkingRemoved) })
    }

    private fun assistantMessage(row: JsonObject): JsonObject? =
        if (JsonScalars.str(row, TRANSCRIPT_TYPE) == ASSISTANT_TYPE) row[TRANSCRIPT_MESSAGE] as? JsonObject else null
}
