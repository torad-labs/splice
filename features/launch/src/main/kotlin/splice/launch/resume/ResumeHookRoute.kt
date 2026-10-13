// NEW: V4-169 (2026-09-19) — `POST /hooks/resume/{head}`, the receiving end of the SessionStart
// resume hook every head carries (core ResumeHook). The body is the hook JSON Claude Code pipes to
// the script: `session_id`, `transcript_path`, `source`, `cwd`. The route moves that transcript's
// assistant rows onto the head's pinned model (TranscriptModelRewrite), so a session written on
// another head resumes here without Claude Code's "Session model <X> could not be restored" notice.
//
// V4-183 (2026-09-20): the hook also fires on `source: "startup"`, and on BOTH sources the route
// records the session as owned by this head (SessionOwnership, in the head's own config dir) so a
// later bare `-c` on this head resumes this head's own newest session in that cwd. The rewrite
// still runs on a resume only; a startup has nothing to move.
//
// ALWAYS 200. This route sits inside a session's own start-up hook; an error status would be a
// splice-made reason for a resume to stall, which is strictly worse than the notice it removes. So
// every refusal — unknown head, a source that is not a resume, an id that is not a session id, a
// path that does not resolve under the head's own transcript tree, a rewrite that failed — answers
// `{}` and lands ONE line in the daemon log with its cause. The transcript path is the one value a
// caller could aim: it is resolved with the filesystem (symlinks followed — the head's projects dir IS
// a link to the shared tree under V4-168) and must land under the head's own projects tree, so the
// bearer this route guards with can only ever rewrite transcripts that head already reads.
// LAYOUT-01: heads arrive through the LaunchHeads port, by exact topology key.
package splice.launch.resume

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.client.resume.CallingRoster
import splice.client.resume.RESUME_SOURCE
import splice.client.resume.STARTUP_SOURCE
import splice.client.resume.SessionOwnership
import splice.client.resume.TranscriptModelRewrite
import splice.core.client.FOREGROUND_OWNER_HEADER
import splice.core.client.FOREGROUND_OWNER_LENGTH
import splice.core.client.ForegroundToolActivity
import splice.core.client.ForegroundToolCall
import splice.core.client.ForegroundToolPhase
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.launch.LaunchHead
import splice.launch.LaunchHeads
import splice.launch.LaunchSpec
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

private const val PROJECTS_DIR = "projects"

/** Claude Code names a session's transcript `<session id>.jsonl` inside the cwd's projects dir. */
private const val UNWRITTEN_TRANSCRIPT_EXT = ".jsonl"

/** The same shape ResumeAcrossHeads admits as a session id: a path component and a log word. */
private val SESSION_ID_SHAPE = Regex("[A-Za-z0-9_-]{1,128}")

/** Heads by TOPOLOGY KEY: the hook script is written with the head's key (ResumeHook.script), never
 *  its wrapper label, so the lookup is exact and never ambiguous. */
public class ResumeHookRoute(
    private val heads: LaunchHeads,
    private val log: LogSink,
    private val rewriter: TranscriptModelRewrite = TranscriptModelRewrite(),
    private val foreground: ForegroundToolActivity? = null,
    /** Told of every startup with a folder: Teams binds a session to a slot by its rule there (SessionStarts). */
    private val starts: SessionStarts? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    public suspend fun resume(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val body = call.receiveText()
        // ALWAYS 200: a failure past the named refusals (the ownership index unwritable, say) is one more refusal
        // line, never a 500 (Oct 7-8: four of them reached the log as unclassified control failures)
        val owner = call.request.headers[FOREGROUND_OWNER_HEADER]
        val refusal = Cancellables.runCatchingCancellable { handle(key, body, owner) }
            .getOrElse { cause -> "the hook failed (${explained(cause)})" }
        // refusal is a sentence THIS class composes; the id it may carry passed SESSION_ID_SHAPE first.
        if (refusal != null) log("[resume] hook for ${LogSafe.str(key)} did nothing: ${LogSafe.str(refusal)}\n")
        call.respondText("{}", ContentType.Application.Json)
    }

    /** The reason nothing was rewritten, or null when the transcript was moved onto the head's model
     *  (or already named it). Internal so the decision is tested without a socket, as SessionsRoutes is. */
    internal fun handle(key: String, body: String, owner: String? = null): String? {
        val managed = heads.byKey(key)
        val spec = managed?.spec
        val hook = parse(body)?.let { ResumeCall(it) }
        return when {
            managed == null -> "no head is keyed that"
            spec == null -> "that head has no launch spec, so no transcript tree"
            hook == null -> "the body is not a JSON object"
            hook.source != RESUME_SOURCE && hook.source != STARTUP_SOURCE ->
                "the hook source is neither a startup nor a resume"
            !SESSION_ID_SHAPE.matches(hook.sessionId) -> "the session id is not a session id"
            else -> {
                started(key, hook, owner)
                located(managed, spec, hook)
            }
        }
    }

    private fun started(key: String, hook: ResumeCall, owner: String?) {
        owner?.takeIf { it.length == FOREGROUND_OWNER_LENGTH }?.let {
            foreground?.record(ForegroundToolCall(hook.sessionId, null, ForegroundToolPhase.SESSION_START, it))
        }
        if (hook.source == STARTUP_SOURCE && hook.cwd.isNotBlank()) {
            starts?.started(key, hook.sessionId, hook.cwd)?.let { log("[teams] ${LogSafe.str(it)}\n") }
        }
    }

    /** The four fields of the hook JSON this route reads, absent ones as empty strings. */
    private class ResumeCall(hook: JsonObject) {
        val source: String = JsonScalars.str(hook, "source").orEmpty()
        val sessionId: String = JsonScalars.str(hook, "session_id").orEmpty()
        val transcriptPath: String = JsonScalars.str(hook, "transcript_path").orEmpty()
        val cwd: String = JsonScalars.str(hook, "cwd").orEmpty()
    }

    private fun located(managed: LaunchHead, spec: LaunchSpec, hook: ResumeCall): String? {
        val configDir = spec.trees.own
        // 2026-09-21: a startup fires before Claude Code has written the session's first row, so the
        // transcript is a path that does not exist yet (measured: every startup hook since V4-183
        // landed was refused here, one second after its launch, and no head ever owned a session —
        // the bare -c crossing V4-183 was written against stayed live). A startup therefore admits
        // the not-yet-written file: its DIRECTORY must be a project directory of the head's tree, made
        // or about to be, and its name must be the hook's own session id. A resume rewrites rows, so it
        // still needs the file.
        val transcript = transcriptInsideHead(hook.transcriptPath, configDir)
            ?: hook.takeIf { it.source == STARTUP_SOURCE }?.let { unwrittenTranscriptInsideHead(it, configDir) }
            ?: return "the transcript path is not a file under this head's transcript tree"
        // V4-183: ownership first, on both sources — a resume that then fails to rewrite is still
        // this head's session. A hook without a cwd records nothing (a launch resolves -c by cwd).
        if (hook.cwd.isNotBlank()) {
            SessionOwnership(configDir, log = log).record(hook.sessionId, hook.cwd, transcript)
        }
        return if (hook.source == RESUME_SOURCE) rewrite(managed, transcript, spec, hook.sessionId) else null
    }

    private fun rewrite(managed: LaunchHead, transcript: Path, spec: LaunchSpec, sessionId: String): String? {
        val pinnedModel = spec.models.pinnedModel
        val rewritten = Cancellables.runCatchingCancellable {
            val offered = spec.models.availableModelIds.takeUnless { spec.gateway.forwardClientAuth }
            rewriter.rewrite(transcript, CallingRoster(pinnedModel, offered, managed.head.key))
        }
            .getOrElse { cause ->
                return "session $sessionId could not be moved onto $pinnedModel (${explained(cause)})"
            }
        if (rewritten > 0) {
            log(
                "[resume] ${LogSafe.str(managed.head.key)}: session ${LogSafe.str(sessionId)} resumed here; " +
                    "${LogSafe.str(rewritten.toString())} assistant rows moved onto ${LogSafe.str(pinnedModel)}\n",
            )
        }
        return null
    }

    /** [path] with its links resolved; null when it cannot be (absent, unreadable). That is the refusal case, and the
     *  caller names it in one sentence; a head whose projects tree cannot be resolved owns no transcript. */
    private fun resolved(path: Path): Path? = try {
        path.toRealPath()
    } catch (_: IOException) {
        null
    }

    /** [raw] as a path; null for text the filesystem cannot even spell (a NUL byte), the same refusal case. */
    private fun spelled(raw: String): Path? = try {
        Path.of(raw)
    } catch (_: InvalidPathException) {
        null
    }

    /** [claimed] resolved with symlinks followed, and only when it is a regular file under the head's
     *  own projects tree resolved the same way. Anything else — absent, a directory, a path that
     *  merely starts with the same text, a link out of the tree — is null. */
    private fun transcriptInsideHead(claimed: String, configDir: Path): Path? {
        if (claimed.isBlank()) return null
        val real = spelled(claimed)?.let(::resolved)
        val tree = resolved(configDir.resolve(PROJECTS_DIR))
        if (real == null || tree == null) return null
        val inside = real.startsWith(tree) && Files.isRegularFile(real, NOFOLLOW_LINKS)
        return if (inside) real else null
    }

    /** A startup's transcript before its first row: the claimed path's PARENT must be a project directory
     *  of the head's own projects tree ([projectDir]), the file name must be `<session id>.jsonl` (the id
     *  already passed SESSION_ID_SHAPE), and nothing may sit at the path yet other than a regular file. The
     *  recorded path is that directory plus that name, which is exactly what Claude Code creates on the
     *  first write. */
    private fun unwrittenTranscriptInsideHead(hook: ResumeCall, configDir: Path): Path? {
        val claimed = hook.transcriptPath.takeIf { it.isNotBlank() }?.let(::spelled)
        val name = claimed?.fileName?.toString()?.takeIf { it == hook.sessionId + UNWRITTEN_TRANSCRIPT_EXT }
        val tree = resolved(configDir.resolve(PROJECTS_DIR))
        val dir = if (tree == null) null else claimed?.parent?.let { projectDir(it, tree) }
        val real = if (name != null && dir != null) dir.resolve(name) else null
        return real?.takeIf { !Files.exists(it, NOFOLLOW_LINKS) || Files.isRegularFile(it, NOFOLLOW_LINKS) }
    }

    /** [dir] resolved with symlinks followed when it is a directory directly under [tree]: Claude Code keeps a
     *  session's transcript one level down, so a deeper folder is refused whether or not it exists. A folder's first
     *  session starts before Claude Code makes its project directory (Oct 12: five of the week's seven refused
     *  startups came the minute their project directory was made), so a directory that does not exist yet
     *  counts when it is one plain name directly under [tree]. */
    private fun projectDir(dir: Path, tree: Path): Path? {
        val made = resolved(dir)
        if (made != null) return made.takeIf { it.parent == tree && Files.isDirectory(it, NOFOLLOW_LINKS) }
        val name = dir.fileName?.toString()?.takeUnless { it == "." || it == ".." } ?: return null
        val unmade = tree.resolve(name)
        val free = !Files.exists(unmade, NOFOLLOW_LINKS) || Files.isDirectory(unmade, NOFOLLOW_LINKS)
        return unmade.takeIf { free && dir.parent?.let(::resolved) == tree }
    }

    /** What failed, said so the next one explains itself and still quotes nothing it read: the exception's class, a
     *  filesystem failure's reason (never its path), the class of what it wraps, and the line in splice that threw,
     *  where a message splice wrote is spelled out. A message itself is withheld, as [SafeFailureText] withholds it:
     *  this route reads transcripts, and a parser's message can quote their bytes. */
    private fun explained(failure: Throwable): String {
        val reason = (failure as? FileSystemException)?.reason?.let { ": $it" }.orEmpty()
        val wrapped = failure.cause?.let { " from ${it::class.simpleName}" }.orEmpty()
        return "${failure::class.simpleName}$reason$wrapped${SafeFailureText.site(failure)}"
    }

    private fun parse(body: String): JsonObject? = try {
        json.parseToJsonElement(body) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }
}
