// How a head's effort reaches the Claude Code a launch starts: as Claude Code's own --effort where it has a word for
// the level, as an environment word that makes it send none where it has not, and not at all when the person named an
// effort of their own on the command line. Moved out of LaunchService (concentration, 2026-11-16).
package splice.launch.recipe

import splice.launch.LaunchSpec

internal class LaunchEffort {
    /** The words Claude Code's --effort takes (`claude --help`, 2.1.312). */
    private val clientEfforts = setOf("low", "medium", "high", "xhigh", "max")

    /** The efforts below low, which --effort has no word for. */
    private val belowClientEfforts = setOf("none", "minimal")

    /** [spec] as the person launches it: an effort of their own on the command line ([extraArgs], spaced or as
     *  `--effort=<level>`) is theirs, and splice names none beside it. */
    fun given(spec: LaunchSpec, extraArgs: List<String>): LaunchSpec =
        if (extraArgs.any { it == EFFORT_FLAG || it.startsWith("$EFFORT_FLAG=") }) spec.copy(effort = null) else spec

    /** Oct 28: the session starts at its head's effort as Claude Code's own --effort, so it shows there and /effort
     *  changes it. Nothing for a word --effort does not take. */
    fun args(spec: LaunchSpec): List<String> =
        spec.effort?.current()?.takeIf { it in clientEfforts }?.let { listOf(EFFORT_FLAG, it) }.orEmpty()

    /** A head set to none or minimal: a session Claude Code starts at no effort sends its own default, which every
     *  adapter takes over the head's setting, so Claude Code is told to send none (read in the 2.1.312 binary: "null
     *  when no effort parameter will be sent (... CLAUDE_CODE_EFFORT_LEVEL=unset ...)"), and the head's own rides. */
    fun env(spec: LaunchSpec): Map<String, String> =
        if (spec.effort?.current() in belowClientEfforts) mapOf(EFFORT_ENV to "unset") else emptyMap()
}

/** Claude Code's flag for the effort a session starts at. */
private const val EFFORT_FLAG = "--effort"

/** Claude Code's environment word for its effort; "unset" makes it send none. */
private const val EFFORT_ENV = "CLAUDE_CODE_EFFORT_LEVEL"
