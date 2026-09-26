// NEW: V4-257 — a claude argv as splice may log it: every flag, and every value but a prompt's. The
// launch audit line carried the argv whole, so a prompt typed on the command line (a -p query, a
// positional prompt, a system prompt, an agent definition) reached daemon.log and daemon-boot.log.
//
// Which words are prompt text is the CLIENT's grammar, so it is read the way the client reads it:
// commander, as `claude --help` of 2.1.283 declares each option (read 2026-09-26: 2.1.282's list plus
// --client-data-url, the only line the two versions' --help differ by). That version is
// [ClaudeArgv.GRAMMAR_FROM], and a test holds it equal to TESTED_CLAUDE_CODE: when V4-256 moves the pin,
// the table is re-read from the new version's --help before the test goes green again.
// A switch takes no value, so the bare word after `-p` is the prompt; `<x>` takes the next word;
// `[x]` takes the next word unless it is a flag; `<x...>` takes every following word up to the next
// flag; everything after `--` is positional. A word it cannot place (after a flag the client does not
// list, or a positional) is withheld, never guessed: an unlisted flag fails closed, not open.
package splice.client

public object ClaudeArgv {

    /** The Claude Code release whose `--help` [OPTIONS] was read from. */
    public const val GRAMMAR_FROM: String = "2.1.283"

    /** [argv] with every prompt's text, and every word it cannot place, replaced by its length. The
     *  program (argv[0]), every flag and every listed option's non-prompt value are kept verbatim, so
     *  an argv that carries no prompt comes back unchanged. */
    public fun promptFree(argv: List<String>): List<String> {
        val out = ArrayList<String>(argv.size)
        var expects = Expects.NOTHING
        var positionalOnly = false
        argv.forEachIndexed { index, word ->
            out += when {
                index == 0 -> word
                positionalOnly -> withheld(word)
                word == END_OF_OPTIONS -> word.also { positionalOnly = true }
                isFlag(word) -> flag(word).also { expects = expectsAfter(word) }
                else -> value(word, expects).also { expects = expects.next() }
            }
        }
        return out
    }

    private fun isFlag(word: String): Boolean = word.length > 1 && word.startsWith("-")

    /** A flag as logged: verbatim, but an inline `=value` of a prompt option, or of a flag the client
     *  does not list, is withheld. */
    private fun flag(word: String): String {
        val eq = word.indexOf('=')
        if (!word.startsWith(LONG) || eq < 0) return word
        val spec = OPTIONS[word.substring(0, eq)]
        return if (spec == null || spec.prompt) word.substring(0, eq + 1) + withheld(word.substring(eq + 1)) else word
    }

    /** What the word after [word] is, by the option [word] names. */
    private fun expectsAfter(word: String): Expects = when {
        word.startsWith(LONG) -> if ('=' in word) Expects.NOTHING else OPTIONS[word]?.expects() ?: Expects.UNPLACED
        else -> shortExpects(word)
    }

    /** `-c`, `-r id`, `-rID` or the cluster `-cp`: letters are switches until one takes a value, and
     *  the rest of the word, if any, is that value. */
    private fun shortExpects(word: String): Expects {
        for (i in 1 until word.length) {
            val spec = OPTIONS["-${word[i]}"] ?: return Expects.UNPLACED
            if (spec.takes != Takes.NOTHING) return if (i == word.lastIndex) spec.expects() else Expects.NOTHING
        }
        return Expects.NOTHING
    }

    private fun value(word: String, expects: Expects): String =
        if (expects == Expects.VALUE || expects == Expects.VALUES) word else withheld(word)

    private fun withheld(word: String): String = "<${word.length} chars withheld>"
}

private enum class Takes { NOTHING, ONE, OPTIONAL, MANY }

/** What the next bare word is: an option's value (kept), a prompt option's value, one more value of a
 *  variadic option, or a word nothing places, which is a positional prompt or fails closed. */
private enum class Expects {
    NOTHING,
    VALUE,
    VALUES,
    PROMPT,
    UNPLACED,
    ;

    fun next(): Expects = if (this == VALUES) VALUES else NOTHING
}

private data class OptionSpec(val takes: Takes, val prompt: Boolean = false) {
    fun expects(): Expects = when {
        takes == Takes.NOTHING -> Expects.NOTHING
        prompt -> Expects.PROMPT
        takes == Takes.MANY -> Expects.VALUES
        else -> Expects.VALUE
    }
}

private const val LONG = "--"
private const val END_OF_OPTIONS = "--"

private val SWITCH = OptionSpec(Takes.NOTHING)
private val ONE = OptionSpec(Takes.ONE)
private val OPTIONAL = OptionSpec(Takes.OPTIONAL)
private val MANY = OptionSpec(Takes.MANY)
private val PROMPT_ONE = OptionSpec(Takes.ONE, prompt = true)
private val PROMPT_OPTIONAL = OptionSpec(Takes.OPTIONAL, prompt = true)

/** Every option `claude --help` of 2.1.283 declares, by spelling. The prompt options: --system-prompt
 *  and --append-system-prompt (<prompt>), --agents (its JSON carries each agent's prompt) and --cloud
 *  (a session's description, free text). --client-data-url is withheld the same way though it is no
 *  prompt: its value is a signed URL, which the client's own help keeps out of the process list. */
private val OPTIONS: Map<String, OptionSpec> = mapOf(
    "--add-dir" to MANY,
    "--agent" to ONE,
    "--agents" to PROMPT_ONE,
    "--allow-dangerously-skip-permissions" to SWITCH,
    "--allowedTools" to MANY,
    "--allowed-tools" to MANY,
    "--append-system-prompt" to PROMPT_ONE,
    "--autocompact" to ONE,
    "--ax-screen-reader" to SWITCH,
    "--bg" to SWITCH,
    "--background" to SWITCH,
    "--bare" to SWITCH,
    "--betas" to MANY,
    "--brief" to SWITCH,
    "--chrome" to SWITCH,
    "--client-data-url" to PROMPT_ONE,
    "--cloud" to PROMPT_OPTIONAL,
    "-c" to SWITCH,
    "--continue" to SWITCH,
    "--dangerously-skip-permissions" to SWITCH,
    "-d" to OPTIONAL,
    "--debug" to OPTIONAL,
    "--debug-file" to ONE,
    "--disable-slash-commands" to SWITCH,
    "--disallowedTools" to MANY,
    "--disallowed-tools" to MANY,
    "--effort" to ONE,
    "--environment" to ONE,
    "--exclude-dynamic-system-prompt-sections" to SWITCH,
    "--fallback-model" to ONE,
    "--file" to MANY,
    "--fork-session" to SWITCH,
    "--forward-subagent-text" to SWITCH,
    "--from-pr" to OPTIONAL,
    "-h" to SWITCH,
    "--help" to SWITCH,
    "--ide" to SWITCH,
    "--include-hook-events" to SWITCH,
    "--include-partial-messages" to SWITCH,
    "--input-format" to ONE,
    "--json-schema" to ONE,
    "--max-budget-usd" to ONE,
    "--mcp-config" to MANY,
    "--model" to ONE,
    "-n" to ONE,
    "--name" to ONE,
    "--no-chrome" to SWITCH,
    "--no-session-persistence" to SWITCH,
    "--output-format" to ONE,
    "--permission-mode" to ONE,
    "--permission-prompts" to ONE,
    "--plugin-dir" to ONE,
    "--plugin-url" to ONE,
    "-p" to SWITCH,
    "--print" to SWITCH,
    "--prompt-suggestions" to OPTIONAL,
    "--remote-control" to OPTIONAL,
    "--remote-control-session-name-prefix" to ONE,
    "--replay-user-messages" to SWITCH,
    "--restricted" to SWITCH,
    "-r" to OPTIONAL,
    "--resume" to OPTIONAL,
    "--safe-mode" to SWITCH,
    "--session-id" to ONE,
    "--setting-sources" to ONE,
    "--settings" to ONE,
    "--strict-mcp-config" to SWITCH,
    "--system-prompt" to PROMPT_ONE,
    "--system-prompt-snapshot" to ONE,
    "--teleport" to OPTIONAL,
    "--tmux" to SWITCH,
    "--tools" to MANY,
    "--verbose" to SWITCH,
    "-v" to SWITCH,
    "--version" to SWITCH,
    "-w" to OPTIONAL,
    "--worktree" to OPTIONAL,
)
