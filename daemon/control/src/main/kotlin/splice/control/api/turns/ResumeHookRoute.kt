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
package splice.control.api.turns

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.client.resume.RESUME_SOURCE
import splice.client.resume.STARTUP_SOURCE
import splice.client.resume.SessionOwnership
import splice.client.resume.TranscriptModelRewrite
import splice.control.LogSafe
import splice.control.ManagedHead
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

private const val PROJECTS_DIR = "projects"

/** Claude Code names a session's transcript `<session id>.jsonl` inside the cwd's projects dir. */
private const val UNWRITTEN_TRANSCRIPT_EXT = ".jsonl"

/** The same shape ResumeAcrossHeads admits as a session id: a path component and a log word. */
private val SESSION_ID_SHAPE = Regex("[A-Za-z0-9_-]{1,128}")

/** Heads by TOPOLOGY KEY: the hook script is written with the head's key (ResumeHook.script), never
 *  its wrapper label, so the lookup is exact and never ambiguous. */
internal class ResumeHookRoute(
    private val heads: Map<String, ManagedHead>,
    private val log: LogSink,
    private val rewriter: TranscriptModelRewrite = TranscriptModelRewrite(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resume(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val body = call.receiveText()
        val refusal = handle(key, body)
        // refusal is a sentence THIS class composes; the id it may carry passed SESSION_ID_SHAPE first.
        if (refusal != null) log("[resume] hook for ${LogSafe.str(key)} did nothing: ${LogSafe.str(refusal)}\n")
        call.respondText("{}", ContentType.Application.Json)
    }

    /** The reason nothing was rewritten, or null when the transcript was moved onto the head's model
     *  (or already named it). Internal so the decision is tested without a socket, as SessionsRoutes is. */
    internal fun handle(key: String, body: String): String? {
        val managed = heads[key]
        val spec = managed?.launchSpec
        val hook = parse(body)?.let { ResumeCall(it) }
        return when {
            managed == null -> "no head is keyed that"
            spec == null -> "that head has no launch spec, so no transcript tree"
            hook == null -> "the body is not a JSON object"
            hook.source != RESUME_SOURCE && hook.source != STARTUP_SOURCE ->
                "the hook source is neither a startup nor a resume"
            !SESSION_ID_SHAPE.matches(hook.sessionId) -> "the session id is not a session id"
            else -> located(managed, spec.trees.own, spec.pinnedModel, hook)
        }
    }

    /** The four fields of the hook JSON this route reads, absent ones as empty strings. */
    private class ResumeCall(hook: JsonObject) {
        val source: String = JsonScalars.str(hook, "source").orEmpty()
        val sessionId: String = JsonScalars.str(hook, "session_id").orEmpty()
        val transcriptPath: String = JsonScalars.str(hook, "transcript_path").orEmpty()
        val cwd: String = JsonScalars.str(hook, "cwd").orEmpty()
    }

    private fun located(managed: ManagedHead, configDir: Path, pinnedModel: String, hook: ResumeCall): String? {
        // 2026-09-21: a startup fires before Claude Code has written the session's first row, so the
        // transcript is a path that does not exist yet (measured: every startup hook since V4-183
        // landed was refused here, one second after its launch, and no head ever owned a session —
        // the bare -c crossing V4-183 was written against stayed live). A startup therefore admits
        // the not-yet-written file: its DIRECTORY must resolve under the head's tree and its name
        // must be the hook's own session id. A resume rewrites rows, so it still needs the file.
        val transcript = transcriptInsideHead(hook.transcriptPath, configDir)
            ?: hook.takeIf { it.source == STARTUP_SOURCE }?.let { unwrittenTranscriptInsideHead(it, configDir) }
            ?: return "the transcript path is not a file under this head's transcript tree"
        // V4-183: ownership first, on both sources — a resume that then fails to rewrite is still
        // this head's session. A hook without a cwd records nothing (a launch resolves -c by cwd).
        if (hook.cwd.isNotBlank()) {
            SessionOwnership(configDir, log = log).record(hook.sessionId, hook.cwd, transcript)
        }
        return if (hook.source == RESUME_SOURCE) rewrite(managed, transcript, pinnedModel, hook.sessionId) else null
    }

    private fun rewrite(managed: ManagedHead, transcript: Path, pinnedModel: String, sessionId: String): String? {
        val rewritten = Cancellables.runCatchingCancellable { rewriter.rewrite(transcript, pinnedModel) }
            .getOrElse { cause ->
                return "session $sessionId could not be moved onto $pinnedModel (${SafeFailureText.render(cause)})"
            }
        if (rewritten > 0) {
            log(
                "[resume] ${LogSafe.str(managed.head.key)}: session ${LogSafe.str(sessionId)} resumed here — " +
                    "${LogSafe.str(rewritten.toString())} assistant rows moved onto ${LogSafe.str(pinnedModel)}\n",
            )
        }
        return null
    }

    /** [claimed] resolved with symlinks followed, and only when it is a regular file under the head's
     *  own projects tree resolved the same way. Anything else — absent, a directory, a path that
     *  merely starts with the same text, a link out of the tree — is null. */
    private fun transcriptInsideHead(claimed: String, configDir: Path): Path? {
        if (claimed.isBlank()) return null
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-19 (V4-169): a path that cannot be resolved (absent, unreadable, malformed) is exactly the refusal case; the caller names it in one sentence and answers 200.
        val real = Cancellables.runCatchingCancellable { Path.of(claimed).toRealPath() }.getOrNull()
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-19 (V4-169): a head whose projects tree cannot be resolved has no transcript this route may touch; null is the complete answer.
        val tree = Cancellables.runCatchingCancellable { configDir.resolve(PROJECTS_DIR).toRealPath() }.getOrNull()
        if (real == null || tree == null) return null
        val inside = real.startsWith(tree) && Files.isRegularFile(real, NOFOLLOW_LINKS)
        return if (inside) real else null
    }

    /** A startup's transcript before its first row: the claimed path's PARENT resolved with symlinks
     *  followed must be a directory under the head's own projects tree, the file name must be
     *  `<session id>.jsonl` (the id already passed SESSION_ID_SHAPE), and nothing may sit at the
     *  path yet other than a regular file. The recorded path is the resolved parent plus that name,
     *  which is exactly what Claude Code creates on the first write. */
    private fun unwrittenTranscriptInsideHead(hook: ResumeCall, configDir: Path): Path? {
        val claimed = hook.transcriptPath.takeIf { it.isNotBlank() }?.let(Path::of)
        val name = claimed?.fileName?.toString()?.takeIf { it == hook.sessionId + UNWRITTEN_TRANSCRIPT_EXT }
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-21: a parent that cannot be resolved is the refusal case, named by the caller in one sentence.
        val parent = claimed?.parent?.let { dir ->
            Cancellables.runCatchingCancellable { dir.toRealPath() }.getOrNull()
        }
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-21: a head whose projects tree cannot be resolved owns no transcript; null is the complete answer.
        val tree = Cancellables.runCatchingCancellable { configDir.resolve(PROJECTS_DIR).toRealPath() }.getOrNull()
        val dir = parent?.takeIf { tree != null && it.startsWith(tree) && Files.isDirectory(it, NOFOLLOW_LINKS) }
        val real = if (name != null && dir != null) dir.resolve(name) else null
        return real?.takeIf { !Files.exists(it, NOFOLLOW_LINKS) || Files.isRegularFile(it, NOFOLLOW_LINKS) }
    }

    private fun parse(body: String): JsonObject? = try {
        json.parseToJsonElement(body) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }
}
