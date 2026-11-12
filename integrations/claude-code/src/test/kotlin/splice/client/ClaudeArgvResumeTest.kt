// NEW: Oct 26, 2026 (a relaunch brings a session back as it was) — the words a relaunch resumes a session with, read
// from the words it was started with. The relaunch of Oct 26 brought review sessions back without their agent: it
// resumed every session on `-r <id> --model <model>` alone. A relaunch keeps every option the session was started with
// (its agent, its name, its permissions), resumes it by its own id on the model its row reports, and never sends its
// first prompt again. What picks or starts another conversation is dropped, and a word the client's grammar cannot place
// stops the relaunch rather than risk sending a prompt.
package splice.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

private const val SESSION = "ea0ed4de-1111-4222-8333-444455556666"
private const val ROW_MODEL = "claude-opus-5-5"

// why: how many argvs the property draws; each is a few dozen words, so the whole run takes well under a second
private const val RUNS = 3_000
private const val SEED = 20_261_026L

/** Options Claude Code lists, by what they take, as `claude --help` of 2.1.296 declares them: switches, one value, an
 *  optional value, every value up to the next flag. The dropped ones pick or start another conversation. */
private const val KEPT_SWITCHES = "--verbose --allow-dangerously-skip-permissions --dangerously-skip-permissions " +
    "--ide --chrome --strict-mcp-config --disable-slash-commands --bare"
private const val KEPT_ONE = "--agent --name --effort --permission-mode --settings --system-prompt " +
    "--append-system-prompt --fallback-model --plugin-dir --agents"
private const val KEPT_OPTIONAL = "--debug --remote-control --prompt-suggestions"
private const val KEPT_MANY = "--add-dir --mcp-config --allowedTools --tools --betas --file"
private const val DROPPED_SWITCHES = "--continue --fork-session --print --tmux"
private const val DROPPED_OPTIONAL = "--resume --from-pr --teleport --cloud --worktree"

/** What a person's option carries: a word with a space, a quote, a brace, an accent or an equals sign among them. */
private val VALUES = listOf("eli", "high", "/tmp/a b", "x=y", "it's", "{\"a\":1}", "ünï")

/** One thing a session was started with, as the client reads it: the words given, the words a resume keeps of them,
 *  and whether a bare word right after them would be read as their value. */
private data class Given(val words: List<String>, val kept: List<String>, val swallows: Boolean)

class ClaudeArgvResumeTest {

    private fun resumed(vararg args: String, model: String? = ROW_MODEL): List<String>? =
        ClaudeArgv.resumed(args.toList(), SESSION, model)

    private fun tail(model: String? = ROW_MODEL): List<String> =
        listOf("-r", SESSION) + model?.let { listOf("--model", it) }.orEmpty()

    @Test
    fun `a review comes back with its agent and its name, and never with the prompt it was started with`() {
        assertEquals(
            listOf("--agent", "eli", "-n", "splice-review-3") + tail(),
            resumed("--agent", "eli", "-n", "splice-review-3", "/review-pr https://github.com/torad/splice/pull/251"),
        )
    }

    @Test
    fun `a seat keeps its own options, and the name it was resumed by gives way to its id`() {
        val skip = "--allow-dangerously-skip-permissions"
        assertEquals(listOf(skip, skip) + tail(), resumed(skip, skip, "-r", "splice-lead"))
    }

    @Test
    fun `it resumes on the model its row reports, or on the one it was started with when its row names none`() {
        assertEquals(listOf("--effort", "high") + tail(), resumed("--model", "claude-fable-5-1", "--effort", "high"))
        assertEquals(
            listOf("--model=claude-fable-5-1", "--effort", "high") + tail(null),
            resumed("--model=claude-fable-5-1", "--effort", "high", model = null),
        )
    }

    @Test
    fun `what picks or starts another conversation is dropped, in every spelling`() {
        assertEquals(
            tail(),
            resumed(
                "-c", "--continue", "--resume=old", "-rold", "--session-id", "u", "--fork-session", "-p", "--print",
                "--from-pr", "12", "--teleport", "--cloud", "-w", "tree", "--worktree", "--tmux",
            ),
        )
        assertEquals(listOf("-v") + tail(), resumed("-vc"), "a cluster keeps the letters that are kept")
    }

    @Test
    fun `everything after -- is the prompt, and is dropped`() {
        assertEquals(listOf("--verbose") + tail(), resumed("--verbose", "--", "--not-a-flag", "fix it"))
    }

    @Test
    fun `a word after a flag the client does not list is never guessed at, so nothing is resumed`() {
        assertNull(resumed("--brand-new-flag", "fix the build"), "that word could be the prompt")
        assertNull(resumed("-X", "fix the build"))
        assertNull(resumed("-vX"), "a cluster with a letter the client does not list cannot be read")
        assertEquals(
            listOf("--brand-new-flag", "--verbose") + tail(),
            resumed("--brand-new-flag", "--verbose"),
            "a flag nothing follows is kept as given",
        )
    }

    /** Whatever a session was started with, drawn from the client's own grammar: every option is kept as given, in
     *  order, every prompt is gone, it is resumed by its id on its model, and a relaunch of a relaunch resumes it on
     *  exactly the same words. */
    @Test
    fun `whatever it was started with, a resume keeps every option, drops every prompt, and resumes it by its id`() {
        val random = Random(SEED)
        repeat(RUNS) { run ->
            val model = if (random.nextBoolean()) ROW_MODEL else null
            val draw = Draw(random, model)
            val (args, expected) = draw.argv()

            val resumed = ClaudeArgv.resumed(args, SESSION, model)

            assertEquals(expected, resumed, "run $run, started with $args")
            assertTrue(draw.prompts.none { it in resumed.orEmpty() }, "run $run: no prompt of $args is sent again")
            val again = ClaudeArgv.resumed(resumed.orEmpty(), SESSION, model)
            assertEquals(resumed, again, "run $run: a relaunch of a relaunch resumes it the same way")
        }
    }

    /** Draws what a session was started with from the client's grammar, and the words a resume of it keeps. */
    private class Draw(private val random: Random, private val model: String?) {
        private var n = 0

        /** Every prompt drawn, which no resume may carry. */
        val prompts = mutableListOf<String>()

        fun argv(): Pair<List<String>, List<String>> {
            val args = mutableListOf<String>()
            val kept = mutableListOf<String>()
            var swallows = false
            repeat(random.nextInt(0, 12)) {
                // a prompt only where the client reads a bare word as one, never where an option would take it
                val given = if (!swallows && random.nextInt(5) == 0) prompt() else option()
                args += given.words
                kept += given.kept
                swallows = given.swallows
            }
            if (random.nextInt(4) == 0) args += listOf("--") + List(random.nextInt(0, 3)) { prompt().words.single() }
            return args to kept + listOf("-r", SESSION) + model?.let { listOf("--model", it) }.orEmpty()
        }

        private fun value(): String = VALUES.random(random) + n++

        /** One flag out of [flags], a space-separated list of the flags Claude Code lists. */
        private fun oneOf(flags: String): String = flags.split(' ').random(random)

        private fun prompt(): Given {
            val text = "fix ${n++} /review-pr https://example.test/pull/$n"
            prompts += text
            return Given(listOf(text), emptyList(), swallows = false)
        }

        private fun option(): Given = when (random.nextInt(7)) {
            0 -> oneOf(KEPT_SWITCHES).let { Given(listOf(it), listOf(it), swallows = false) }
            1 -> valued(oneOf(KEPT_ONE), keep = true, optional = false)
            2 -> valued(oneOf(KEPT_OPTIONAL), keep = true, optional = true)
            3 -> many()
            4 -> dropped()
            5 -> valued("--model", keep = model == null, optional = false)
            else -> short()
        }

        /** A long option with its value spaced or after `=`, or an optional value left out. */
        private fun valued(name: String, keep: Boolean, optional: Boolean): Given {
            val words = when (random.nextInt(if (optional) 3 else 2)) {
                0 -> listOf(name, value())
                1 -> listOf("$name=${value()}")
                else -> listOf(name)
            }
            return Given(words, if (keep) words else emptyList(), swallows = words == listOf(name))
        }

        private fun many(): Given {
            val words = listOf(oneOf(KEPT_MANY)) + List(random.nextInt(1, 4)) { value() }
            return Given(words, words, swallows = true)
        }

        private fun dropped(): Given = when (random.nextInt(3)) {
            0 -> Given(listOf(oneOf(DROPPED_SWITCHES)), emptyList(), swallows = false)
            1 -> valued("--session-id", keep = false, optional = false)
            else -> valued(oneOf(DROPPED_OPTIONAL), keep = false, optional = true)
        }

        /** A short flag or a cluster: switch letters (v and h kept, c and p dropped), then maybe one letter that
         *  takes a value (n and d kept, r and w dropped), glued, spaced, or, when optional, left out. */
        private fun short(): Given {
            val switches = List(random.nextInt(0, 4)) { "vhcp"[random.nextInt(4)] }
            val keptSwitches = switches.filter { it in "vh" }.map { "-$it" }
            val last = if (switches.isEmpty() || random.nextBoolean()) "ndrw"[random.nextInt(4)] else null
            val word = "-" + switches.joinToString("") + (last?.toString() ?: "")
            if (last == null) return Given(listOf(word), keptSwitches, swallows = false)
            val keep = last in "nd"
            val v = value()
            return when (random.nextInt(if (last == 'n') 2 else 3)) {
                0 -> Given(listOf(word + v), keptSwitches + listOf("-$last$v").filter { keep }, swallows = false)
                1 -> Given(listOf(word, v), keptSwitches + listOf("-$last", v).filter { keep }, swallows = false)
                else -> Given(listOf(word), keptSwitches + listOf("-$last").filter { keep }, swallows = true)
            }
        }
    }
}
