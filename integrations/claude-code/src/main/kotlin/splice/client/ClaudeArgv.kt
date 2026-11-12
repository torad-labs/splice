// NEW: V4-257 — a claude argv as splice may log it: every flag, and every value but a prompt's. The
// launch audit line carried the argv whole, so a prompt typed on the command line (a -p query, a
// positional prompt, a system prompt, an agent definition) reached daemon.log and daemon-boot.log.
//
// Which words are prompt text is the CLIENT's grammar, so it is read the way the client reads it:
// commander, as `claude --help` of 2.1.296 declares each option (read 2026-09-26 on 2.1.283: 2.1.282's
// list plus --client-data-url, the only line the two versions' --help differ by; re-read 2026-09-28 on
// 2.1.284, whose --help differs from 2.1.283's only in --model's description text, and 2026-09-29 on
// 2.1.285, whose --help adds --desktop, a switch, and nothing else; re-read 2026-10-01 on
// 2.1.286, whose --help is byte-identical to 2.1.285, and 2.1.287, which removes --client-data-url;
// re-read 2026-10-05 on 2.1.289: its option table is unchanged, and only subcommands differ; re-read
// 2026-10-10 on 2.1.296: the flag set and each flag's argument form match 2.1.289's line for line).
// When V4-256 moves the Claude Code pin, the table is re-read from the new version's --help.
// A switch takes no value, so the bare word after `-p` is the prompt; `<x>` takes the next word;
// `[x]` takes the next word unless it is a flag; `<x...>` takes every following word up to the next
// flag; everything after `--` is positional. A word it cannot place (after a flag the client does not
// list, or a positional) is withheld, never guessed: an unlisted flag fails closed, not open.
//
// Oct 26, 2026 (a relaunch brings a session back as it was): the same grammar reads the words a session was started
// with back into the words that resume it. The relaunch of Oct 26 resumed every session on `-r <id> --model <model>`
// alone, so reviews came back without their agent. A resume keeps every option and drops every prompt, so a session
// never gets its first prompt again, and a word the grammar cannot place stops the relaunch the same way it is withheld.
package splice.client

public object ClaudeArgv {

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

    /** The words that resume [session] from [args], the words its client was started with after the program: every
     *  option kept as given and in order, the prompt and everything after `--` dropped, and whatever picks or starts
     *  another conversation dropped, then `-r` [session] and, when it is known, `--model` [model] in place of the model
     *  it was started with. Null when a word cannot be placed, since it could be the prompt, and when an option misses
     *  the value it needs, since it would take the resume's own words. */
    public fun resumed(args: List<String>, session: String, model: String?): List<String>? {
        val drop = if (model == null) OTHER_SESSION else OTHER_SESSION + MODEL_FLAG
        val kept = ArrayList<String>(args.size)
        var at = 0
        while (at < args.size) {
            val word = args[at]
            val read = when {
                word == END_OF_OPTIONS -> Read(emptyList(), args.size)
                !isFlag(word) -> Read(emptyList(), at + 1)
                word.startsWith(LONG) -> longWords(args, at, drop)
                else -> shortWords(args, at, drop)
            } ?: return null
            kept += read.words
            at = read.next
        }
        return kept + listOf(RESUME_FLAG, session) + model?.let { listOf(MODEL_FLAG, it) }.orEmpty()
    }

    /** The long option at [at] and the values it takes, kept as given or dropped when [drop] names it. Null when the
     *  client does not list it and a bare word follows, which could be its value or the prompt. */
    private fun longWords(args: List<String>, at: Int, drop: Set<String>): Read? {
        val name = args[at].substringBefore('=')
        val takes = OPTIONS[name]?.takes
        val next = if (takes == null || '=' in args[at]) at + 1 else valuesAfter(args, at + 1, takes)
        val unplaced = if (takes == null) bare(args, next) else next > args.size
        return when {
            unplaced -> null
            name in drop -> Read(emptyList(), next)
            else -> Read(args.subList(at, next), next)
        }
    }

    /** The short flag at [at], or a cluster of them, letter by letter: switches, until the first letter that takes a
     *  value ends it, with the rest of the word, or else the words after it, as that value. Each letter comes back as a
     *  flag of its own, a value glued to it as given. */
    private fun shortWords(args: List<String>, at: Int, drop: Set<String>): Read? {
        val word = args[at]
        val letters = word.drop(1).map { "-$it" }
        val stop = letters.indexOfFirst { OPTIONS[it]?.takes != Takes.NOTHING }
        if (stop < 0) return Read(letters - drop, at + 1)
        val letter = letters[stop]
        val takes = OPTIONS[letter]?.takes ?: return unlistedShort(args, at)
        val rest = word.substring(stop + 2)
        val next = if (rest.isEmpty()) valuesAfter(args, at + 1, takes) else at + 1
        val switches = letters.take(stop) - drop
        return when {
            next > args.size -> null
            letter in drop -> Read(switches, next)
            rest.isEmpty() -> Read(switches + letter + args.subList(at + 1, next), next)
            else -> Read(switches + (letter + rest), next)
        }
    }

    /** A short flag the client does not list: alone, it is kept unless a bare word follows that it could take; in a
     *  cluster, the letters after it could be its value, so the word cannot be read. */
    private fun unlistedShort(args: List<String>, at: Int): Read? =
        if (args[at].length > 2 || bare(args, at + 1)) null else Read(listOf(args[at]), at + 1)

    /** The index past the values an option that [takes] them reads from [from] on, the way the client reads them: one
     *  word whatever it is, a word unless it is a flag, or one word and then every word up to the next flag. Past the
     *  end when the option needs a value and none is left. */
    private fun valuesAfter(args: List<String>, from: Int, takes: Takes): Int = when (takes) {
        Takes.NOTHING -> from
        Takes.OPTIONAL -> if (bare(args, from)) from + 1 else from
        Takes.ONE -> from + 1
        Takes.MANY -> {
            var next = from + 1
            while (bare(args, next)) next++
            next
        }
    }

    /** Whether the word at [index] is there and is no flag, so an option before it may take it as a value. */
    private fun bare(args: List<String>, index: Int): Boolean = args.getOrNull(index)?.let { !isFlag(it) } ?: false
}

/** The words read from one place in an argv, and where the next read starts. */
private data class Read(val words: List<String>, val next: Int)

private const val MODEL_FLAG = "--model"
private const val RESUME_FLAG = "-r"

/** What picks or starts another conversation, in every spelling: a resume names its session itself. */
private val OTHER_SESSION = setOf(
    "-c", "--continue", "-r", "--resume", "--session-id", "--fork-session", "-p", "--print", "--from-pr", "--teleport",
    "--cloud", "-w", "--worktree", "--tmux",
)

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

/** Every option `claude --help` of 2.1.296 declares, by spelling. The prompt options: --system-prompt
 *  and --append-system-prompt (<prompt>), --agents (its JSON carries each agent's prompt) and --cloud
 *  (a session's description, free text). Removed options fail closed like any other unlisted flag. */
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
    "--cloud" to PROMPT_OPTIONAL,
    "-c" to SWITCH,
    "--continue" to SWITCH,
    "--dangerously-skip-permissions" to SWITCH,
    "-d" to OPTIONAL,
    "--debug" to OPTIONAL,
    "--debug-file" to ONE,
    "--desktop" to SWITCH,
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
