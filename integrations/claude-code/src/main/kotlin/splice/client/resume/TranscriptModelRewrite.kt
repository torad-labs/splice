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
//
// One row at a time (Oct 7 CT): a pass reads the rows that move and a SHA-256 of the bytes, and only a
// file with a moving row is streamed into its staged replacement. The whole-file read this replaced held
// several copies of the text at once, and a 715 MB transcript failed the launch that resumed it. What one
// line is, and how an assistant row moves, is AssistantRowMove; this file is the transaction over a file.
package splice.client.resume

import splice.client.Keys
import splice.client.resume.originals.TranscriptOriginals
import splice.client.transcript.TranscriptLines
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Claude Code's transcript extension — the one declaration; ResumeAcrossHeads reads it too. */
internal const val TRANSCRIPT_SUFFIX: String = ".jsonl"

/** Anthropic's model namespace: a row there is a model a client on its own login can restore. */
private const val CLAUDE_ID_PREFIX = "claude-"

/** Buffer between a rewrite's rows and its staged file. */
private const val WRITE_BUFFER_BYTES = 1 shl 20

/** What a rewrite puts in its staged file, written to the stream it is handed. */
public fun interface StagedRows {
    public operator fun invoke(out: OutputStream)
}

/** The two steps of a rewrite a test must fail deterministically: writing the new bytes and the swap. */
public interface TranscriptFs {
    public fun write(path: Path, rows: StagedRows)

    public fun move(source: Path, target: Path, vararg options: CopyOption)
}

private object ProcessTranscriptFs : TranscriptFs {
    /** The bytes are forced to disk before this returns: a rename can reach the disk before the data
     *  it names, so a crash after an unforced move could leave an empty transcript in the original's place. */
    override fun write(path: Path, rows: StagedRows) {
        FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val out = BufferedOutputStream(Channels.newOutputStream(channel), WRITE_BUFFER_BYTES)
            rows(out)
            out.flush()
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

    private val rows = AssistantRowMove()

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
        val kept = served?.let { roster ->
            val names = roster.toSet() + pinnedModel
            KeptModel { model -> model in names }
        } ?: clientPicked
        val surveys = files.map { survey(it, kept) }
        val policy = RowPolicy(served?.let { pinnedModel } ?: surveys.first().newestClaude, kept)
        val rewritten = surveys.sumOf { it.changed(policy) }
        if (rewritten == 0) {
            originals.rememberIfKept(transcript)
            return 0
        }
        originals.preserve(transcript, files)
        val preservedChanged = "Transcript changed while preserving its original; nothing was rewritten"
        surveys.forEach { ensureUnchanged(it, preservedChanged) }
        surveys.forEach { publish(it, policy) }
        return rewritten
    }

    /** V4-449: where the client picks its own models no roster exists to move onto. A row on a Claude model
     *  stays as it is; a row on another vendor's model moves, without its thinking, onto the newest Claude
     *  model this transcript used ([Survey.newestClaude] of the transcript itself). With no Claude row there
     *  is nothing to move onto: the row still loses its thinking (another vendor's signature fails upstream)
     *  and keeps its model, which the picker replaces. */
    private val clientPicked = KeptModel { model -> isNativeClaude(model) }

    /** Discovery IDs use a head's double-hyphen namespace, not the native Claude model namespace. */
    private fun isNativeClaude(model: String?): Boolean =
        model?.startsWith(CLAUDE_ID_PREFIX) == true && "--" !in model

    /** One pass over a file, as counts: how many rows a move takes and how many of those hold thinking, the last
     *  native Claude model an assistant row names, and the digest of the bytes read. No row is kept: the write
     *  decides each row again through the same [AssistantRowMove.moveOf], so memory does not grow with the file.
     *  [path] is what the rewrite was given; [file] is the file it names now, which every check and the
     *  replacement use. */
    private class Survey(val path: Path, val file: Path) {
        var moves = 0
        var strips = 0
        var newestClaude: String? = null
        var digest = ByteArray(0)

        /** The rows a rewrite under [policy] changes: a moving row changes when it takes a model, or, with
         *  none to take, when it loses its thinking. */
        fun changed(policy: RowPolicy): Int = if (policy.target != null) moves else strips
    }

    private fun survey(path: Path, kept: KeptModel): Survey {
        val survey = Survey(path, readable(path) { path.toRealPath() })
        survey.digest = readable(survey.file) {
            TranscriptLines.read(survey.file) { _, row ->
                val shape = rows.read(row.text())
                if (shape is LineShape.Assistant) {
                    val model = JsonScalars.str(shape.message, Keys.MODEL)
                    if (isNativeClaude(model)) survey.newestClaude = model
                }
                val move = rows.moveOf(shape, kept)
                if (move != null) {
                    survey.moves++
                    if (move.strips) survey.strips++
                }
            }
        }
        return survey
    }

    /** [read] of [file], a failure named as that file's: the one way a pass here reports a read. */
    private inline fun <T> readable(file: Path, read: () -> T): T = Cancellables.runCatchingCancellable(read)
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

    private fun publish(survey: Survey, policy: RowPolicy) {
        if (survey.changed(policy) == 0) return
        Cancellables.runCatchingCancellable { replace(survey, policy) }
            .exceptionOrNull()
            ?.let { cause -> throw IOException("${survey.file} unwritable (${SafeFailureText.render(cause)})") }
    }

    /** [file]'s rows to [out]: a row a move takes is rewritten under [policy], every other row's bytes are kept as
     *  they were. Returns the digest of the bytes read, which the rewrite checks against its survey. */
    private fun writeRows(file: Path, policy: RowPolicy, out: OutputStream): ByteArray =
        TranscriptLines.read(file) { index, row ->
            if (index > 0) out.write('\n'.code)
            val moved = rows.rewritten(row.text(), policy)
            if (moved == null) out.write(row.bytes, 0, row.length) else out.write(moved.toByteArray(Charsets.UTF_8))
        }

    /** V4-259: the new bytes go to a temp file beside the transcript, which is moved over it in one step,
     *  so a write that dies partway leaves the user's transcript exactly as it was; the temp file goes
     *  either way. The in-place write this replaced wrote through a link and refused a read-only file,
     *  and so does this: the file a link names is the one replaced, and a rename, which a read-only file
     *  does not stop, is not attempted on one. The file's permissions carry over to the new one. */
    private fun replace(survey: Survey, policy: RowPolicy) {
        val target = survey.file
        if (!Files.isWritable(target)) throw IOException("$target is read-only")
        val staged = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        Cancellables.runCatchingCancellable {
            Cancellables.discard(
                runCatching { Files.setPosixFilePermissions(staged, Files.getPosixFilePermissions(target)) },
                "no POSIX permissions on this filesystem, so there are none to carry over",
            )
            val changedWhileStaging = "Transcript changed while staging its rewrite"
            var consumed = ByteArray(0)
            fs.write(staged) { out -> consumed = writeRows(target, policy, out) }
            // The staged bytes are the surveyed bytes only if the staging read consumed exactly those.
            if (!MessageDigest.isEqual(consumed, survey.digest)) throw IOException(changedWhileStaging)
            ensureUnchanged(survey, changedWhileStaging)
            fs.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            Cancellables.discard(
                runCatching { Files.deleteIfExists(staged) },
                "the temp file's cleanup is best-effort; the write failure rethrows",
            )
            throw it
        }
    }

    /** Throws [changed] unless the path still names the file the survey resolved, and that file still holds the
     *  bytes the survey read. Every check of a rewrite goes through here, so each one reads the file a replacement
     *  would replace. */
    private fun ensureUnchanged(survey: Survey, changed: String) {
        val named = readable(survey.path) { survey.path.toRealPath() }
        val now = readable(survey.file) { TranscriptLines.digest(survey.file) }
        if (named != survey.file || !MessageDigest.isEqual(now, survey.digest)) throw IOException(changed)
    }
}
