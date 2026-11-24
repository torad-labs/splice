// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 5). The command line Claude Desktop starts a
// Code tab session with, as `splice desktop launch` receives it: the words that start the program (Claude Code, or
// another wrapper with flags of its own in front of it) and Claude Code's own arguments after them. It says which model
// the session starts on, which session it takes up and whether it speaks stream-json, and it is rewritten for the
// program a live model switch starts next: Claude Code again with `--resume`, or `splice desktop acp` standing in.
package splice.app.desktop.pipe

import splice.provider.antigravity.ANTIGRAVITY_MODEL_PREFIX
import splice.provider.antigravity.STANDS_IN_FOR

/** Desktop's command line: [words] start the program, [args] are Claude Code's. */
internal data class ClaudeLine(val words: List<String>, val args: List<String>) {
    /** The model the session starts on, as `--model=<id>` or `--model <id>` names it last. */
    val model: String? get() = last(LINE_MODEL_FLAG)

    /** Whether the session runs on agy rather than Claude Code. */
    val antigravity: Boolean get() = model.orEmpty().startsWith(ANTIGRAVITY_MODEL_PREFIX)

    /** The session this line starts or takes up, when it names one. */
    val sessionId: String? get() = last(LINE_SESSION_ID_FLAG) ?: last(LINE_RESUME_FLAG)

    /** Whether Desktop talks to the program in stream-json on its standard input, which a live switch needs. */
    val streamJson: Boolean get() = last(LINE_INPUT_FORMAT_FLAG) == LINE_STREAM_JSON

    /** `splice desktop acp`'s arguments for this line: the Claude Code it stands in for, then Desktop's own. */
    fun acpArgs(): List<String> = listOf("$STANDS_IN_FOR=${words.last()}") + args

    /** This line for a session switched to [model], taking up session [resume] when it is known. Whatever chose the
     *  session before (a fork, `--continue`) is dropped: the session is the one [resume] names now. */
    fun switchedTo(model: String, resume: String?): ClaudeLine {
        val kept = without(setOf(LINE_MODEL_FLAG, LINE_SESSION_ID_FLAG, LINE_RESUME_FLAG))
            .filterNot { it in LINE_SESSION_CHOICE_FLAGS }
        val taken = resume?.let { listOf(LINE_RESUME_FLAG, it) }.orEmpty()
        return copy(args = kept + listOf(LINE_MODEL_FLAG, model) + taken)
    }

    /** The value [flag] is given last, written `--flag=value` or `--flag value`. */
    private fun last(flag: String): String? = args.indices.mapNotNull { at ->
        val arg = args[at]
        when {
            arg.startsWith("$flag=") -> arg.substringAfter('=')
            arg == flag -> args.getOrNull(at + 1)?.takeUnless { it.startsWith("-") }
            else -> null
        }
    }.lastOrNull()?.takeIf(String::isNotBlank)

    /** [args] without [flags] and the value each was given. */
    private fun without(flags: Set<String>): List<String> {
        val kept = mutableListOf<String>()
        var at = 0
        while (at < args.size) {
            val arg = args[at++]
            if (arg.substringBefore('=') !in flags) {
                kept += arg
            } else if ('=' !in arg && args.getOrNull(at)?.startsWith("-") == false) {
                at++
            }
        }
        return kept
    }
}

/** Splits the command `splice desktop launch` was given into a [ClaudeLine]. */
internal class ClaudeLineReader {
    /** [command] as words and arguments. Claude Code's own word ends the words, so a wrapper in front keeps its own
     *  flags; with no word named `claude`, the arguments start at the first flag after the program's word. */
    fun of(command: List<String>): ClaudeLine {
        val claude = command.indexOfFirst { it.substringAfterLast('/').substringAfterLast('\\') in LINE_CLAUDE_NAMES }
        val first = command.drop(1).indexOfFirst { it.startsWith("-") }
        val split = when {
            claude >= 0 -> claude + 1
            first < 0 -> command.size
            else -> first + 1
        }
        return ClaudeLine(command.take(split), command.drop(split))
    }
}

// Claude Code's flags the pipe reads or rewrites, and the format Desktop drives a session in.
private const val LINE_MODEL_FLAG = "--model"
private const val LINE_SESSION_ID_FLAG = "--session-id"
private const val LINE_RESUME_FLAG = "--resume"
private const val LINE_INPUT_FORMAT_FLAG = "--input-format"
private const val LINE_STREAM_JSON = "stream-json"

// Claude Code's flags, taking no value, that choose a session other than the one named: a switch drops them.
private val LINE_SESSION_CHOICE_FLAGS = setOf("--fork-session", "--continue", "-c")

// The file names Claude Code's program goes by.
private val LINE_CLAUDE_NAMES = setOf("claude", "claude.exe")
