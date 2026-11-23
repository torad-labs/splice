// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 5). The command line Claude Desktop starts a
// Code tab session with, as `splice desktop launch` receives it: the words that start the program (Claude Code, or
// another wrapper in front of it) and Claude Code's own arguments after them. It says which model the session starts
// on, which session it takes up and whether it speaks stream-json, and it is rewritten for the program a live model
// switch starts next: Claude Code again with `--resume`, or `splice desktop acp` standing in for it.
package splice.app.desktop.pipe

import splice.provider.antigravity.ANTIGRAVITY_MODEL_PREFIX

/** Desktop's command line: [words] start the program, [args] are Claude Code's. */
internal data class ClaudeLine(val words: List<String>, val args: List<String>) {
    /** The model the session starts on, as `--model=<id>` or `--model <id>` names it last. */
    val model: String? get() = last(MODEL)

    /** Whether the session runs on agy rather than Claude Code. */
    val antigravity: Boolean get() = model.orEmpty().startsWith(ANTIGRAVITY_MODEL_PREFIX)

    /** The session this line starts or takes up, when it names one. */
    val sessionId: String? get() = last(SESSION_ID) ?: last(RESUME)

    /** Whether Desktop talks to the program in stream-json on its standard input, which a live switch needs. */
    val streamJson: Boolean get() = last(INPUT_FORMAT) == STREAM_JSON

    /** `splice desktop acp`'s arguments for this line: the Claude Code it stands in for, then Desktop's own. */
    fun acpArgs(): List<String> = listOf("$STANDS_IN_FOR=${words.last()}") + args

    /** This line for a session switched to [model], taking up session [resume] when it is known. */
    fun switchedTo(model: String, resume: String?): ClaudeLine {
        val kept = without(setOf(MODEL, SESSION_ID, RESUME))
        return copy(args = kept + listOf(MODEL, model) + listOfNotNull(resume?.let { RESUME }, resume))
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
    /** [command] as words and arguments: the arguments start at its first flag after the program's own word. */
    fun of(command: List<String>): ClaudeLine {
        val first = command.drop(1).indexOfFirst { it.startsWith("-") }
        val split = if (first < 0) command.size else first + 1
        return ClaudeLine(command.take(split), command.drop(split))
    }
}

private const val MODEL = "--model"
private const val SESSION_ID = "--session-id"
private const val RESUME = "--resume"
private const val INPUT_FORMAT = "--input-format"
private const val STREAM_JSON = "stream-json"

/** The flag `splice desktop acp` reads the Claude Code it stands in for from (ClaudeArgsReader). */
private const val STANDS_IN_FOR = "--stands-in-for"
