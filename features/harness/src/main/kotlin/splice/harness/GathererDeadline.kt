// NEW: the Context Getter's own deadline (docs/specs/harness.md, decision 9). splice ends a gatherer itself at its time
// limit, when the client leaves, when the head stops and when the daemon shuts down. This is the floor beneath those
// for a daemon killed outright, which ends nothing: the gatherer is started under coreutils' `timeout`, a process of
// its own that outlives the daemon and, a little past the limit, ends the gatherer and everything it started (its
// process group), asked first and then made to.
package splice.harness

import java.io.File

internal object GathererDeadline {
    /** [argv] under a deadline [PAST_LIMIT_S] past [limitS], with `timeout` (`gtimeout` where coreutils carry that
     *  name, as Homebrew installs them) as the folders of [path] find it. [argv] as it is where neither is found: the
     *  gatherer then ends only by splice's hand. */
    fun around(argv: List<String>, limitS: Int, path: String?): List<String> {
        val folders = path.orEmpty().split(File.pathSeparator).filter(String::isNotBlank)
        val timeout = TOOLS.firstNotNullOfOrNull { tool ->
            folders.map { File(it, tool) }.firstOrNull { it.isFile && it.canExecute() }
        } ?: return argv
        return listOf(timeout.path, "--kill-after=$KILL_AFTER_S", "${limitS + PAST_LIMIT_S}") + argv
    }
}

/** why: coreutils' name for it, then the name Homebrew installs it under on macOS. */
private val TOOLS = listOf("timeout", "gtimeout")

/** why: splice's own end at the time limit comes first and is the one recorded; the deadline is only the floor. */
private const val PAST_LIMIT_S = 5

/** why: a Claude Code session asked to stop exits well within this; one that does not is killed. */
private const val KILL_AFTER_S = 5
