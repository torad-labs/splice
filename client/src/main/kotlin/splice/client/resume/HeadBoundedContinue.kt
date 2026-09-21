// NEW: V4-183 (2026-09-20) — a bare `-c` / `--continue` is bounded to the head. Under the shared
// transcript tree Claude Code's --continue resumes the newest transcript in the cwd's directory
// whoever wrote it, and appends this head's turns into that file (SessionOwnership's header has the
// measurement). Here the flag is resolved BEFORE the client sees it: the newest session THIS head
// owns in the launch cwd becomes an explicit `--resume <id>`, which the shared tree then serves
// like any named resume. No session of this head there means a NEW session, said in one sentence —
// never the newest foreign one. A launch that names a session (`-r <id>`) or has no `-c` is
// untouched, and so is one from a shim too old to send its cwd: that shim is named as the fix.
package splice.client.resume

import java.nio.file.Path

private const val CONTINUE_SHORT = "-c"
private const val CONTINUE_LONG = "--continue"
private const val RESUME_LONG = "--resume"

public data class ContinueResolution(val args: List<String>, val warning: String?)

/**
 * [ownTree] is the head's own config tree, where [SessionOwnership] keeps the sessions this head
 * wrote — the one thing the resolution reads, so :daemon-control's launch spec stays on its side of
 * the module law (`:client -> :core` only).
 */
public class HeadBoundedContinue {
    public fun resolve(ownTree: Path, args: List<String>, cwd: String?): ContinueResolution {
        val index = args.indexOfFirst { it == CONTINUE_SHORT || it == CONTINUE_LONG }
        return when {
            index < 0 || args.any(::namesResume) -> ContinueResolution(args, null)
            cwd.isNullOrBlank() -> ContinueResolution(
                args,
                "this launch shim sent no cwd, so -c is not bounded to this head's own sessions — " +
                    "run `splice install` to refresh app/src/main/dist/bin/splice-launch",
            )
            else -> bounded(ownTree, args.filterIndexed { position, _ -> position != index }, cwd)
        }
    }

    private fun bounded(ownTree: Path, rest: List<String>, cwd: String): ContinueResolution {
        val newest = SessionOwnership(ownTree).newestFor(cwd)
            ?: return ContinueResolution(
                rest,
                "no session of this head in $cwd to continue — starting a new one " +
                    "(join another head's session by name with -r <id>)",
            )
        return ContinueResolution(rest + listOf(RESUME_LONG, newest.id), null)
    }

    /** Any resume spelling the client admits; with one present, `-c` is the client's own conflict. */
    private fun namesResume(arg: String): Boolean =
        arg == "-r" || arg == RESUME_LONG || arg.startsWith("-r=") || arg.startsWith("$RESUME_LONG=") ||
            (arg.startsWith("-r") && arg.length > 2 && arg[2] != '-')
}
