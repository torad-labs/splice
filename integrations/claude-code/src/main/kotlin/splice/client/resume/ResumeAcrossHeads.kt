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
// the tree, every foreign id is HeadOwned through the link and nothing here copies.
//
// (A) BOUNDED BY HEAD. Nothing here runs unless the launch asked for one specific session id. The
//     whole point of a head-private tree is that `-c` and the `-r` PICKER see only this head's
//     sessions; nothing in this class can change that, because it is only ever called with an id.
// (B) ON DEMAND. `-r SESSION_ID` on a head whose own tree does not hold the id resolves it across
//     the OTHER heads' projects trees, copies SESSION_ID.jsonl and its SESSION_ID/ subdir into the
//     calling head's tree, and launches. It is a COPY — never a link, never a move, and the source
//     is left byte-identical — so the two heads never share an inode, an append, or a deletion.
// (C) MODEL FOLLOWS THE HEAD. On that copy — and, since V4-169, IN PLACE on a session the calling
//     head already sees through the shared tree (V4-168) — every assistant row's `message.model` is
//     rewritten to the calling head's pinned model (TranscriptModelRewrite, shared with the resume hook). Claude Code restores a resumed session's model from the
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
// THE CALLER'S OWN TREE IS THE CWD SIGNAL. The launch request carries only argv (the app/src/main/dist/bin/splice-launch
// shim sends {"args": [...]}; LaunchRequest has no cwd), so "same encoded cwd first" is read off the
// one thing that IS available: an encoded-cwd directory this head already holds a transcript tree for
// is the session's likely home, and a head whose tree is freshly empty has no signal at all — which
// is exactly when falling through to any head is right.
//
// SAFE TEXT. The id becomes a path component and reaches operator-facing text, so it is validated
// BEFORE either: every id any outcome carries matches SESSION_ID_SHAPE (`[A-Za-z0-9_-]{1,128}`),
// which cannot traverse out of the projects tree and cannot forge a line in the launch warning. A
// rejected id is reported without echoing it.
package splice.client.resume

import splice.client.Keys
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** A Claude Code session id, and the only shape allowed to become a path component or a message. */
private val SESSION_ID_SHAPE = Regex("[A-Za-z0-9_-]{1,128}")

/** The answer to "where did this launch's `-r SESSION_ID` come from?". A sealed OUTCOME, not an
 *  exception: every refusal is a value the caller's `when` has to handle. */
public sealed class SessionAdoption {
    /** The id is already in the calling head's own tree — nothing was copied. Its assistant rows were
     *  moved onto the head's model in place (V4-169); [modelsRewritten] is 0 when they already named it,
     *  or when the rewrite failed, which is logged and never blocks the launch. */
    public data class HeadOwned(public val transcript: Path, public val modelsRewritten: Int = 0) : SessionAdoption()

    /** A foreign transcript was copied in, and its assistant rows were rewritten to the head's model. */
    public data class Adopted(
        public val sessionId: String,
        public val from: Path,
        public val into: Path,
        public val modelsRewritten: Int,
    ) : SessionAdoption()

    /** The id is in no head's projects tree. Carries WHICH trees were searched, so the caller can
     *  name them: a refusal that does not say where it looked leaves the operator nothing to do. */
    public data class Absent(
        public val sessionId: String,
        public val searchedHeads: List<String>,
    ) : SessionAdoption()

    /** The id WAS found but the copy or the model rewrite failed. Carries the source tree and the
     *  rendered cause, so the caller can name what failed, where, and what is still true. */
    public data class Refused(
        public val sessionId: String,
        public val from: Path,
        public val cause: String,
    ) : SessionAdoption()

    /** The requested text is not a session id at all. Carries no echo of the rejected argument. */
    public data class Invalid(public val cause: String) : SessionAdoption()
}

public class ResumeAcrossHeads(private val rewriter: TranscriptModelRewrite = TranscriptModelRewrite()) {

    /** Resolve `-r [sessionId]` for the head launching from [callingConfigDir], looking in every
     *  other head's CLAUDE_CONFIG_DIR. [pinnedModel] is that head's model, and [served] its whole roster:
     *  a row on a served model is left as it is (TranscriptModelRewrite). */
    public fun adopt(
        callingConfigDir: Path,
        otherConfigDirs: List<Path>,
        sessionId: String,
        pinnedModel: String,
        served: Collection<String>,
        log: LogSink = LogSink(DaemonLog::write),
    ): SessionAdoption {
        // The id becomes a path component and reaches operator-facing text, so it is validated
        // before either.
        if (!SESSION_ID_SHAPE.matches(sessionId)) {
            return SessionAdoption.Invalid("a session id is letters, digits, '-' and '_' only, up to 128 characters")
        }
        val others = otherConfigDirs.filter { it != callingConfigDir }.distinct()
        val own = findAllIn(callingConfigDir, sessionId, log).firstOrNull()
        val foreign = others.flatMap { dir -> findAllIn(dir, sessionId, log) }
        return when {
            own != null ->
                SessionAdoption.HeadOwned(own.transcript, rewriteInPlace(own.transcript, pinnedModel, served, log))
            foreign.isEmpty() -> SessionAdoption.Absent(sessionId, (listOf(callingConfigDir) + others).map(::headName))
            else -> {
                val chosen = preferSameCwd(callingConfigDir, foreign, log)
                copyIn(callingConfigDir, chosen, sessionId, pinnedModel, served, log)
            }
        }
    }

    /** V4-169: a session this head already sees — its own, or any head's through the shared tree — is
     *  moved onto this head's model where it lies. Nothing else is at stake in a failure here (the
     *  session still resumes, on the head's default, with Claude Code's one-line notice), so it is
     *  said in the log and the launch goes on. */
    private fun rewriteInPlace(transcript: Path, pinnedModel: String, served: Collection<String>, log: LogSink): Int {
        val outcome = Cancellables.runCatchingCancellable { rewriter.rewrite(transcript, pinnedModel, served) }
        outcome.exceptionOrNull()?.let { cause ->
            log(
                "[resume] $transcript could not be moved onto $pinnedModel (${SafeFailureText.render(cause)}) — " +
                    "the session resumes on this head's default model after Claude Code's restore notice\n",
            )
        }
        return outcome.getOrDefault(0)
    }

    /** A config dir as the operator names it in a message: the directory itself, never a guess. */
    private fun headName(configDir: Path): String = configDir.fileName?.toString() ?: configDir.toString()

    /** The encoded-cwd directory a transcript was found under — Claude Code's name for the session's
     *  own working directory, which the copy must preserve or the resumed session resolves no project. */
    private data class Located(val headConfigDir: Path, val cwdDir: String, val transcript: Path)

    /** EVERY transcript of [sessionId] in [configDir]'s OWN projects tree — one per encoded-cwd
     *  directory it appears under, because a session can be recorded under more than one. Only ever
     *  this head's tree: the picker is bounded by head and so is the search that feeds it. */
    private fun findAllIn(configDir: Path, sessionId: String, log: LogSink): List<Located> =
        directoryEntries(configDir.resolve(Keys.PROJECTS), log)
            .filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
            .mapNotNull { cwdDir ->
                val transcript = cwdDir.resolve(sessionId + TRANSCRIPT_SUFFIX)
                if (Files.isRegularFile(transcript, NOFOLLOW_LINKS)) {
                    Located(configDir, cwdDir.fileName.toString(), transcript)
                } else {
                    null
                }
            }

    /** Same encoded cwd first, then any — see the header for why the calling head's own tree is the
     *  only cwd signal available. [found] is non-empty by every caller's check. */
    private fun preferSameCwd(callingConfigDir: Path, found: List<Located>, log: LogSink): Located {
        val known = directoryEntries(callingConfigDir.resolve(Keys.PROJECTS), log)
            .map { it.fileName.toString() }
            .toSet()
        return found.firstOrNull { it.cwdDir in known } ?: found.first()
    }

    /** The copy, the subdir tree and the model rewrite are ONE transaction: a partial adoption would
     *  hand Claude Code a transcript that is half another head's. The source is read only. */
    private fun copyIn(
        callingConfigDir: Path,
        chosen: Located,
        sessionId: String,
        pinnedModel: String,
        served: Collection<String>,
        log: LogSink,
    ): SessionAdoption {
        val targetDir = callingConfigDir.resolve(Keys.PROJECTS).resolve(chosen.cwdDir)
        val target = targetDir.resolve(sessionId + TRANSCRIPT_SUFFIX)
        val targetSubdir = targetDir.resolve(sessionId)
        val copied = Cancellables.runCatchingCancellable {
            Files.createDirectories(targetDir)
            // A real byte copy, never a link: a link would put the source head's tree inside the
            // calling head's, which is exactly the leak this row removes.
            Files.copy(chosen.transcript, target, REPLACE_EXISTING)
            val sourceSubdir = chosen.transcript.resolveSibling(sessionId)
            if (Files.isDirectory(sourceSubdir, NOFOLLOW_LINKS)) copyTree(sourceSubdir, targetSubdir, log)
            rewriter.rewrite(target, pinnedModel, served)
        }
        copied.exceptionOrNull()?.let { cause ->
            return SessionAdoption.Refused(sessionId, chosen.headConfigDir, SafeFailureText.render(cause))
        }
        val rewritten = copied.getOrDefault(0)
        log(
            "[resume] adopted session $sessionId from ${chosen.headConfigDir} into $callingConfigDir " +
                "($rewritten assistant rows rewritten to $pinnedModel); the source tree is untouched\n",
        )
        return SessionAdoption.Adopted(sessionId, chosen.transcript, target, rewritten)
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
                "[resume] skipped linked $entry (${SafeFailureText.render(cause)}) — it is not part of " +
                    "the copy; the session still resumes without it\n",
            )
        }
    }

    /** A missing or unreadable projects dir is an ordinary miss for a search (a head that has never
     *  run a session has none), so it is named once and read as empty — never a thrown launch. */
    private fun directoryEntries(path: Path, log: LogSink): List<Path> {
        val entries = Cancellables.runCatchingCancellable {
            Files.newDirectoryStream(path).use { stream -> stream.toList() }
        }
        entries.exceptionOrNull()?.let { cause ->
            log(
                "[resume] could not list $path (${SafeFailureText.render(cause)}) — that tree was NOT " +
                    "searched for the session; fix its permissions and relaunch to search it\n",
            )
        }
        return entries.getOrDefault(emptyList()).sortedBy { it.fileName.toString() }
    }
}
