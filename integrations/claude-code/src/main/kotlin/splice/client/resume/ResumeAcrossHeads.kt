// NEW: cross-head resume as an explicit COPY (V4-115, 2026-09-17). Commit 91d68f3e bought
// "a session started on one head resumes on any other" by sharing ONE projects tree through a
// symlink into the operator's vanilla ~/.claude/projects. Measured 2026-09-17: 95 transcripts
// carrying head model ids sat in the vanilla tree, and the vanilla client printed "Session model
// deepseek-flash could not be restored" on every restore.
//
// OPERATOR RULING: head configurations and details must NEVER leak into other heads, their wrappers,
// or the core claude binary sessions. V4-115 read that as covering transcripts and removed the shared
// tree; V4-168 (2026-09-19) put it back after the operator named the removal a regression — the ruling
// covers CONFIGURATION, and transcripts are shared session state (ProjectsLink's header). So this
// class is now the path for a head whose policy ISOLATES projects, and for an id that lives only in
// another head's private tree: an on-demand act with a moment and an actor. On a head that shares
// the tree, a foreign id is found through the link, and it goes on as a copy beside it when its rows
// would move (C).
//
// (A) BOUNDED BY HEAD. Nothing here runs unless the launch asked for one specific session id. The
//     whole point of a head-private tree is that `-c` and the `-r` PICKER see only this head's
//     sessions; nothing in this class can change that, because it is only ever called with an id.
// (B) ON DEMAND. `-r SESSION_ID` on a head whose own tree does not hold the id resolves it across
//     the OTHER heads' projects trees, copies SESSION_ID.jsonl and its SESSION_ID/ subdir into the
//     calling head's tree, and launches. It is a COPY — never a link, never a move, and the source
//     is left byte-identical — so the two heads never share an inode, an append, or a deletion.
// (C) MODEL FOLLOWS THE HEAD. On that copy every assistant row's `message.model` is rewritten to the calling head's
//     pinned model (TranscriptModelRewrite, shared with the resume hook). A session the calling head already sees
//     through the shared tree (V4-168) whose rows would move is never rewritten where it lies (the President's
//     ruling on V4-169, Oct 13): it resumes as its copy beside it under a new session id, its rows retagged to that
//     id (SessionIdRetag), and the original stays as its head wrote it. One whose rows all stay resumes as it is.
//     Claude Code restores a resumed session's model from the
//     transcript and refuses one the head does not serve — the reason string it prints with
//     "Session model <X> could not be restored" is "is not in the availableModels allowlist"
//     (verified in the 2.1.257 binary, strings around tengu_resume_model_restore). The materializer
//     ALREADY writes that allowlist from the head's own roster (ClaudeConfigMaterializer.writeSettings
//     puts `availableModels` = spec.availableModelIds), so the roster half of (C) needs no change:
//     the warning fired because the transcript carried ANOTHER head's id, which is correctly absent
//     from this head's roster, and the rewrite is what makes it present.
//
// A session found NOWHERE is reported as Absent so the caller can say so; one that was found but
// could not be copied is Refused. Neither is an exception: the refusal rides the return type of the
// call that decided it (kt-no-exception-as-outcome).
//
// SAME ENCODED CWD FIRST. The launch carries its cwd, and Claude Code names a project directory by that path with
// every character outside letters and digits turned into a dash, so a copy found under the launch cwd's directory
// wins. A shim too old to send a cwd falls back to the one signal left: an encoded-cwd directory this head already
// holds a transcript tree for is the session's likely home, and a head whose tree is freshly empty has no signal at
// all — which is exactly when falling through to any head is right.
//
// SAFE TEXT. The id becomes a path component and reaches operator-facing text, so it is validated
// BEFORE either: every id any outcome carries matches SESSION_ID_SHAPE (`[A-Za-z0-9_-]{1,128}`),
// which cannot traverse out of the projects tree and cannot forge a line in the launch warning. A
// rejected id is reported without echoing it.
package splice.client.resume

import splice.client.Keys
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.DirectoryListing
import splice.core.util.FilesListing
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** A Claude Code session id, and the only shape allowed to become a path component or a message. */
private val SESSION_ID_SHAPE = Regex("[A-Za-z0-9_-]{1,128}")
private val NOT_ALPHANUMERIC = Regex("[^A-Za-z0-9]")

/** One primary transcript is eligible for -r only when its own regular file has conversation bytes.
 *  The durable history index uses this same rule; a zero-byte file remains in its source census. */
internal object ResumableTranscript {
    enum class State { MISSING, EMPTY, USABLE }

    fun state(file: Path): State = when {
        !Files.isRegularFile(file, NOFOLLOW_LINKS) -> State.MISSING
        Files.size(file) == 0L -> State.EMPTY
        else -> State.USABLE
    }

    fun accepts(file: Path): Boolean = state(file) == State.USABLE
}

private const val NOT_A_SESSION_ID = "a session id is letters, digits, '-' and '_' only, up to 128 characters"

/** The calling head's model and its whole roster, and the model choice they make: a row on a served model stays, and
 *  the rest move onto the pinned one. A null roster is a head whose client picks its own models (V4-449): no roster
 *  exists to move onto, so a row on a Claude model stays and a row on another vendor's model moves, without its
 *  thinking, onto the newest Claude model its transcript used. With no Claude row there is nothing to move onto: the
 *  row still loses its thinking (another vendor's signature fails upstream) and keeps its model, which the picker
 *  replaces. */
public class CallingRoster(
    internal val pinned: String,
    private val served: Collection<String>?,
    /** The head's key, the command whose launch moves a session: recorded with each move. */
    internal val command: String? = null,
    /** The launch's working directory: the encoded cwd a foreign copy is preferred under. Null when the shim sent none. */
    internal val cwd: String? = null,
) {
    /** Whether a row on a model stays where it is: a served model (the pinned one included), else a Claude one. */
    internal fun kept(): KeptModel = served?.let { roster ->
        val names = roster.toSet() + pinned
        KeptModel { model -> model in names }
    } ?: KeptModel { model -> NativeClaude.isModel(model) }

    /** The model a moved row takes: the pinned one, or [newestClaude] where the client picks its own. */
    internal fun target(newestClaude: String?): String? = if (served != null) pinned else newestClaude
}

public class ResumeAcrossHeads(
    public val rewriter: TranscriptModelRewrite = TranscriptModelRewrite(),
    private val listing: DirectoryListing = FilesListing,
) {

    /** Resolve `-r [sessionId]` for the head launching from [callingConfigDir], looking in every
     *  other head's CLAUDE_CONFIG_DIR. [roster] is that head's model and its whole roster:
     *  a row on a served model is left as it is (TranscriptModelRewrite). */
    public fun adopt(
        callingConfigDir: Path,
        otherConfigDirs: List<Path>,
        sessionId: String,
        roster: CallingRoster,
        log: LogSink = LogSink(DaemonLog::write),
    ): SessionAdoption {
        return when (val plan = plan(callingConfigDir, otherConfigDirs, sessionId, log, roster.cwd)) {
            is ResumePlan.Invalid -> SessionAdoption.Invalid(plan.cause)
            is ResumePlan.Absent -> SessionAdoption.Absent(plan.sessionId, plan.searchedHeads)
            is ResumePlan.Empty -> SessionAdoption.Empty(plan.sessionId, plan.transcript)
            is ResumePlan.Owned ->
                resumeSeen(plan.transcript, sessionId, roster, log)
            is ResumePlan.Copy -> copyIn(callingConfigDir, plan, sessionId, roster, log)
        }
    }

    /** What [adopt] would do for the same arguments, read only: nothing is copied, linked or rewritten.
     *  The resume recipe route asks this (V4-320). */
    public fun plan(
        callingConfigDir: Path,
        otherConfigDirs: List<Path>,
        sessionId: String,
        log: LogSink = LogSink(DaemonLog::write),
        cwd: String? = null,
    ): ResumePlan {
        // The id becomes a path component and reaches operator-facing text, so it is validated
        // before either.
        if (!SESSION_ID_SHAPE.matches(sessionId)) return ResumePlan.Invalid(NOT_A_SESSION_ID)
        val others = otherConfigDirs.filter { it != callingConfigDir }.distinct()
        val own = findAllIn(callingConfigDir, sessionId, log)
        val foreign = others.flatMap { dir -> findAllIn(dir, sessionId, log) }
        val ownedUsable = own.firstOrNull { it.state == ResumableTranscript.State.USABLE }
        val foreignUsable = foreign.filter { it.state == ResumableTranscript.State.USABLE }
        return when {
            ownedUsable != null -> ResumePlan.Owned(ownedUsable.transcript)
            foreignUsable.isNotEmpty() -> preferSameCwd(callingConfigDir, foreignUsable, cwd, log).let { chosen ->
                val into = callingConfigDir.resolve(Keys.PROJECTS).resolve(chosen.cwdDir)
                    .resolve(sessionId + TRANSCRIPT_SUFFIX)
                ResumePlan.Copy(chosen.transcript, chosen.headConfigDir, into)
            }
            else -> (own + foreign).firstOrNull { it.state == ResumableTranscript.State.EMPTY }
                ?.let { ResumePlan.Empty(sessionId, it.transcript) }
                ?: ResumePlan.Absent(sessionId, (listOf(callingConfigDir) + others).map(::headName))
        }
    }

    /** V4-169 as the President ruled it (Oct 13): a session this head sees through the shared tree resumes where it
     *  lies when none of its rows has to move, and otherwise as a copy beside it ([copyAside]), so the original is
     *  never written. One that cannot be read resumes as it is: Claude Code's one-line restore notice, nothing lost. */
    private fun resumeSeen(transcript: Path, sessionId: String, roster: CallingRoster, log: LogSink): SessionAdoption {
        val pending = Cancellables.runCatchingCancellable { rewriter.pending(transcript, roster) }.getOrElse { cause ->
            log(
                "[resume] session $sessionId could not be read for its models (${SafeFailureText.render(cause)}); " +
                    "it resumes as it is, after Claude Code's restore notice\n",
            )
            return SessionAdoption.HeadOwned(transcript)
        }
        if (pending == 0) return SessionAdoption.HeadOwned(transcript)
        return copyAside(transcript, sessionId, roster, log)
    }

    /** The copy, its subdir, the retag and the model move are one act: a copy that fails partway is removed, and the
     *  original, read only, resumes as it is. */
    private fun copyAside(transcript: Path, sessionId: String, roster: CallingRoster, log: LogSink): SessionAdoption {
        val copyId = UUID.randomUUID().toString()
        val target = transcript.resolveSibling(copyId + TRANSCRIPT_SUFFIX)
        val targetSubdir = transcript.resolveSibling(copyId)
        val retag = SessionIdRetag(sessionId, copyId)
        val made = Cancellables.runCatchingCancellable {
            retag.copy(transcript, target)
            val sourceSubdir = transcript.resolveSibling(sessionId)
            if (Files.isDirectory(sourceSubdir, NOFOLLOW_LINKS)) {
                copyTree(sourceSubdir, targetSubdir, log)
                retagUnder(targetSubdir, retag)
            }
            rewriter.rewriteCopy(target, roster)
        }
        val rewritten = made.getOrElse { cause ->
            discardCopy(target, targetSubdir)
            return SessionAdoption.Refused(sessionId, transcript.parent, SafeFailureText.render(cause))
        }
        log(
            "[resume] session $sessionId goes on as its copy $copyId ($rewritten assistant rows on " +
                "${roster.pinned}); the original is untouched\n",
        )
        return SessionAdoption.Copied(sessionId, copyId, target, rewritten)
    }

    /** Every jsonl the copy's subdir holds (its subagents' transcripts) retagged in place: each is splice's copy. */
    private fun retagUnder(dir: Path, retag: SessionIdRetag) {
        val files = Files.walk(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(TRANSCRIPT_SUFFIX) && Files.isRegularFile(it) }.toList()
        }
        files.forEach { file ->
            val staged = file.resolveSibling(".${file.fileName}.retag")
            retag.copy(file, staged)
            Files.move(staged, file, REPLACE_EXISTING)
        }
    }

    private fun discardCopy(target: Path, targetSubdir: Path?) {
        Cancellables.discard(
            Cancellables.runCatchingBestEffort {
                val _ = Files.deleteIfExists(target)
                if (targetSubdir != null && Files.isDirectory(targetSubdir, NOFOLLOW_LINKS)) {
                    Files.walk(targetSubdir).use { stream ->
                        stream.sorted(Comparator.reverseOrder()).forEach { val _ = Files.deleteIfExists(it) }
                    }
                }
            },
            "a half-made copy is removed best-effort; the launch resumes the untouched original either way",
        )
    }

    /** A config dir as the operator names it in a message: the directory itself, never a guess. */
    private fun headName(configDir: Path): String = configDir.fileName?.toString() ?: configDir.toString()

    /** The encoded-cwd directory a transcript was found under — Claude Code's name for the session's
     *  own working directory, which the copy must preserve or the resumed session resolves no project. */
    private data class Located(
        val headConfigDir: Path,
        val cwdDir: String,
        val transcript: Path,
        val state: ResumableTranscript.State,
    )

    /** EVERY transcript of [sessionId] in [configDir]'s OWN projects tree — one per encoded-cwd
     *  directory it appears under, because a session can be recorded under more than one. Only ever
     *  this head's tree: the picker is bounded by head and so is the search that feeds it. */
    private fun findAllIn(configDir: Path, sessionId: String, log: LogSink): List<Located> =
        directoryEntries(configDir.resolve(Keys.PROJECTS), log)
            .filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
            .mapNotNull { cwdDir ->
                val transcript = cwdDir.resolve(sessionId + TRANSCRIPT_SUFFIX)
                val state = Cancellables.runCatchingCancellable { ResumableTranscript.state(transcript) }
                    .onFailure { cause ->
                        log(
                            "[resume] transcript could not be checked " +
                                "(${SafeFailureText.render(cause)}); not eligible\n",
                        )
                    }
                    .getOrDefault(ResumableTranscript.State.MISSING)
                if (state != ResumableTranscript.State.MISSING) {
                    Located(configDir, cwdDir.fileName.toString(), transcript, state)
                } else {
                    null
                }
            }

    /** Same encoded cwd first, then a directory this head already holds, then any. [found] is non-empty by every
     *  caller's check. */
    private fun preferSameCwd(callingConfigDir: Path, found: List<Located>, cwd: String?, log: LogSink): Located {
        val launch = cwd?.takeIf { it.isNotBlank() }?.let(::encodedCwd)
        val known = directoryEntries(callingConfigDir.resolve(Keys.PROJECTS), log)
            .map { it.fileName.toString() }
            .toSet()
        return found.firstOrNull { it.cwdDir == launch } ?: found.firstOrNull { it.cwdDir in known } ?: found.first()
    }

    /** Claude Code's name for a project directory: the path with every character outside letters and digits a dash. */
    private fun encodedCwd(cwd: String): String = cwd.replace(NOT_ALPHANUMERIC, "-")

    /** The copy, the subdir tree and the model rewrite are ONE transaction: a partial adoption would
     *  hand Claude Code a transcript that is half another head's. The launch still names the original id, so a copy
     *  that fails partway is removed, as [copyAside]'s is, and Claude Code finds no half-made session to resume. A
     *  subdir this head already held is not this copy's to remove. The source is read only. */
    private fun copyIn(
        callingConfigDir: Path,
        plan: ResumePlan.Copy,
        sessionId: String,
        roster: CallingRoster,
        log: LogSink,
    ): SessionAdoption {
        val target = plan.into
        val targetDir = target.parent
        val targetSubdir = targetDir.resolve(sessionId)
        val subdirHeld = Files.exists(targetSubdir, NOFOLLOW_LINKS)
        val copied = Cancellables.runCatchingCancellable {
            Files.createDirectories(targetDir)
            // A real byte copy, never a link: a link would put the source head's tree inside the
            // calling head's, which is exactly the leak this row removes.
            Files.copy(plan.from, target, REPLACE_EXISTING)
            val sourceSubdir = plan.from.resolveSibling(sessionId)
            if (Files.isDirectory(sourceSubdir, NOFOLLOW_LINKS)) copyTree(sourceSubdir, targetSubdir, log)
            rewriter.rewriteCopy(target, roster)
        }
        val rewritten = copied.getOrElse { cause ->
            discardCopy(target, targetSubdir.takeUnless { subdirHeld })
            return SessionAdoption.Refused(sessionId, plan.fromHead, SafeFailureText.render(cause))
        }
        log(
            "[resume] adopted session $sessionId from ${plan.fromHead} into $callingConfigDir " +
                "($rewritten assistant rows rewritten to ${roster.pinned}); the source tree is untouched\n",
        )
        return SessionAdoption.Adopted(sessionId, plan.from, target, rewritten)
    }

    /** Directories, then files, copying CONTENT: a symlink recreated verbatim would point back into
     *  the source head's tree and re-open the leak, so it is resolved and its bytes are copied. A
     *  link whose target is not a readable regular file has no bytes to copy and is named once. */
    private fun copyTree(src: Path, dst: Path, log: LogSink) {
        Files.walk(src).use { stream ->
            stream.forEach { entry ->
                val target = dst.resolve(src.relativize(entry).toString())
                when {
                    Files.isDirectory(entry, NOFOLLOW_LINKS) -> Files.createDirectories(target)
                    Files.isSymbolicLink(entry) -> copyLinked(entry, target, log)
                    else -> Files.copy(entry, target, REPLACE_EXISTING)
                }
            }
        }
    }

    private fun copyLinked(entry: Path, target: Path, log: LogSink) {
        val copied = Cancellables.runCatchingCancellable {
            // isRegularFile follows the link: a dangling or non-file target is the skip case below.
            if (!Files.isRegularFile(entry)) throw IOException("link target is missing or not a regular file")
            Files.copy(entry, target, REPLACE_EXISTING)
        }
        copied.exceptionOrNull()?.let { cause ->
            log(
                "[resume] skipped linked $entry (${SafeFailureText.render(cause)}): it is not part of " +
                    "the copy; the session still resumes without it\n",
            )
        }
    }

    /** A projects dir that does not exist is an ordinary miss for a search: a head that has never run a
     *  session has none, so it is read as empty and logs nothing. Any other failure to list it (access
     *  denied, an I/O error, a path that is not a directory) is named once and read as empty, never a
     *  thrown launch. */
    private fun directoryEntries(path: Path, log: LogSink): List<Path> {
        val entries = Cancellables.runCatchingCancellable { listing.list(path) }
        entries.exceptionOrNull()?.takeUnless { it is NoSuchFileException }?.let { cause ->
            log(
                "[resume] could not list $path (${SafeFailureText.render(cause)}), so that tree was NOT " +
                    "searched for the session; fix its permissions and relaunch to search it\n",
            )
        }
        return entries.getOrDefault(emptyList()).sortedBy { it.fileName.toString() }
    }
}
