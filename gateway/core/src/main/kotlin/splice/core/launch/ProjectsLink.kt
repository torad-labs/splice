// NEW: head-private transcripts (V4-115, 2026-09-17). This file used to be the reverse of what it is
// now. Commit 91d68f3e (V4-64/V4-65, 2026-09-16) made it point every head's CLAUDE_CONFIG_DIR/projects
// at the operator's vanilla ~/.claude/projects so a session could be resumed on another head —
// hardlink-merging a head's real tree into the vanilla one and then replacing the head path with a
// symlink, live-writer-safe, with a straggler sweep and two rollbacks. Measured 2026-09-17: three
// heads linked there, 95 transcripts carrying head model ids sat in the vanilla tree (gpt-6-astra 36,
// gpt-5.6-sol 33, deepseek-flash 24, k3-256k 8), and the vanilla client printed "Session model
// deepseek-flash could not be restored" on every restore.
//
// OPERATOR RULING (2026-09-17): head configurations and details must NEVER leak into other heads,
// their wrappers, or the core claude binary sessions — fully isolated. So the merge is unwound. This
// class's one job is now the opposite one: guarantee that a head's projects dir is a REAL directory
// inside that head, and un-link it if an earlier launch left it pointing somewhere else.
//
// The migration, per path shape:
//   - SYMLINK  -> the link itself is deleted (never the tree it points at) and an empty real
//                 directory takes its place. Deleted rather than repointed because the tree behind
//                 it is the vanilla one: the operator's history is repaired on their side, by
//                 attribution, after install. This fix stops the leak going forward and leaves the
//                 old links harmless; it does not relocate anyone's transcripts.
//   - REAL DIR -> left exactly as found. It is the head's own tree and it is already private.
//   - ABSENT   -> created. Claude Code writes transcripts there from the next message on.
//   - ANYTHING ELSE (a regular file, a device) -> preserved and logged, never deleted to make room.
//
// Why the un-link is not a MOVE: Claude Code appends each message with an open-per-append appendFile
// and every head on the operator machine has live sessions at all times, so moving a transcript
// splits it the instant its session writes again — and the pre-91d68f3e head trees were already
// merged into the vanilla one, so there is nothing left at the head path to move.
//
// The un-link is intentionally NOT policy-driven. `projects` is no longer a shared item at all
// (ClaudeConfigKeys.sharedLinkItems), and the migration must run for EVERY head whatever its policy
// says, including a head whose splice.toml still names projects in `share` — that spelling is inert
// now, and a policy cannot resurrect the link.
package splice.core.launch

import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink

/** The two filesystem operations the un-link migration must fail deterministically (or observe) in
 *  tests without a custom provider. */
internal interface ProjectsFs {
    fun deleteSymbolicLink(link: Path)
    fun createDirectories(dir: Path): Path
}

private object ProcessProjectsFs : ProjectsFs {
    override fun deleteSymbolicLink(link: Path): Unit = Files.delete(link)

    override fun createDirectories(dir: Path): Path = Files.createDirectories(dir)
}

internal class ProjectsLink(
    private val fs: ProjectsFs = ProcessProjectsFs,
) {

    /** [dst] as the materializer's launch path calls it: ensurePrivate logs its own declines, this
     *  catches what it THROWS mid-flight (DR-39) so a transcript-tree failure never aborts the rest
     *  of the head's materialize. */
    fun ensurePrivateOrLog(dst: Path, log: LogSink) {
        Cancellables.runCatchingCancellable { ensurePrivate(dst, log) }.exceptionOrNull()?.let { cause ->
            log(
                // SAFE-RENDER-EXEMPT[2026-09-17]: path work only — the failure names a directory, never transcript content
                "[materialize] projects dir $dst could not be made head-private " +
                    "(${SafeFailureText.render(cause)}) — the head still launches, but its transcripts " +
                    "may be shared; move $dst aside and relaunch for a private tree\n",
            )
        }
    }

    /** Guarantee [dst] is a real directory this head owns. Returns true when it is one on return.
     *  Declining is never silent (DR-1): an un-link, a refusal and a creation failure each land in
     *  the log with their cause. */
    fun ensurePrivate(dst: Path, log: LogSink = LogSink(DaemonLog::write)): Boolean = when {
        dst.isSymbolicLink() -> unlinkThenCreate(dst, log)
        Files.isDirectory(dst, NOFOLLOW_LINKS) -> true
        Files.exists(dst, NOFOLLOW_LINKS) -> decline(dst, log)
        else -> create(dst)
    }

    /** The un-link: delete the LINK (never its target), say where it pointed, then give the head a
     *  real empty tree. The target is reported for the operator's own repair; an unreadable link
     *  target is named as unreadable rather than guessed. */
    private fun unlinkThenCreate(dst: Path, log: LogSink): Boolean {
        val target = Cancellables.runCatchingCancellable { Files.readSymbolicLink(dst).toString() }
            .getOrNull() ?: "<unreadable target>"
        fs.deleteSymbolicLink(dst)
        log(
            "[projects] UNLINKED $dst — it pointed at $target, which this head does not own; this head " +
                "now keeps its own transcripts there. $target is untouched: any history that belongs to " +
                "this head is still there for the operator to move back by attribution\n",
        )
        return create(dst)
    }

    /** Unexpected content is preserved, but never SILENTLY (DR-39): the caller's contract is that
     *  ensurePrivate logs its own declines. */
    private fun decline(dst: Path, log: LogSink): Boolean {
        log(
            "[materialize] projects NOT made head-private — $dst is unexpected non-directory content, " +
                "kept as-is, so this head's transcripts are not private; move it aside to fix that\n",
        )
        return false
    }

    private fun create(dst: Path): Boolean {
        fs.createDirectories(dst)
        return true
    }
}
