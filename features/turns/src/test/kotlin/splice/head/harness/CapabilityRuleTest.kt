// NEW: which requests a capability is called for (docs/specs/harness.md, decision 2), as a head decides it from the
// requests Claude Code sends: its user agent, its agent header, whether it streams with tools, whether its history
// holds an answer. Each test sends real requests through a head and reads whether, and in what order, the worker
// outside splice was posted, and what the provider got.
package splice.head.harness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.capability.CapabilityMode
import splice.core.capability.CapabilityPlace
import splice.core.capability.CapabilityResult
import splice.core.capability.CapabilityRule
import splice.core.capability.CapabilityThread
import splice.core.capability.CapabilityTurn
import splice.core.parse.AnthropicTurnBody
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.TurnEnd
import java.nio.file.Path

class CapabilityRuleTest(@param:TempDir private val tmp: Path) {
    private val rig = CapabilityRig(tmp)

    @AfterEach
    fun close() = rig.close()

    private fun asked(session: String) = rig.asked.filter { it.session == session }.map { it.capability }

    @Test
    fun `a terminal session's first turn is called once, and its next turn is not`() = runBlocking {
        val head = rig.head(listOf(rig.capability("brief")))

        rig.send(head, Turn("s1", opening("Fix the bug")))
        rig.send(head, Turn("s1", later("Fix the bug", "and test it")))

        assertEquals(listOf("brief"), asked("s1"), "asked for the first turn alone")
    }

    // Neither request holds an answer, so only the thread's record can tell the second one is not its first turn.
    @Test
    fun `a first turn sent again before any answer is not called again, after the first or while it waits`() =
        runBlocking {
            val head = rig.head(listOf(rig.capability("brief")))

            rig.send(head, Turn("again", opening("Fix the bug")))
            rig.send(head, Turn("again", opening("Fix the bug in src/main.ts")))

            rig.reply = { WorkerReply(listOf("""{"type":"result","text":"late"}"""), held = true) }
            val waiting = launch(Dispatchers.IO) { rig.send(head, Turn("typed-over", opening("Fix the bug"))) }
            rig.awaitUntil("the first turn is held") { asked("typed-over").isNotEmpty() }
            rig.send(head, Turn("typed-over", opening("Fix the bug in src/main.ts")))
            waiting.join()

            assertEquals(listOf("brief"), asked("again"), "the first was answered before the second came")
            assertEquals(listOf("brief"), asked("typed-over"), "the second came while the first waited")
        }

    @Test
    fun `the client name in the user agent is compared whole`() = runBlocking {
        val interactive = rig.capability("terminal")
        val print = rig.capability("printed").copy(rule = CapabilityRule(mode = CapabilityMode.PRINT))
        val head = rig.head(listOf(interactive, print))

        rig.send(head, Turn("in-terminal", opening("Fix the bug")))
        rig.send(head, Turn("printed", opening("Fix the bug"), userAgent = PRINT_AGENT))
        rig.send(head, Turn("unnamed", opening("Fix the bug"), userAgent = "curl/8.5.0"))

        assertEquals(listOf("terminal"), asked("in-terminal"), "cli is a session in a terminal")
        assertEquals(listOf("printed"), asked("printed"), "sdk-cli is print mode, though it ends in cli")
        assertEquals(emptyList<String>(), asked("unnamed"), "a client that names neither is neither")
    }

    @Test
    fun `each subagent is a thread of its own, with a first turn of its own`() = runBlocking {
        val main = rig.capability("main")
        val subagents = rig.capability("sub").copy(rule = CapabilityRule(thread = CapabilityThread.SUBAGENT))
        val head = rig.head(listOf(main, subagents))

        rig.send(head, Turn("s1", opening("Fix the bug")))
        rig.send(head, Turn("s1", opening("Read the code"), agent = "a1"))
        rig.send(head, Turn("s1", later("Read the code", "go on"), agent = "a1"))
        rig.send(head, Turn("s1", opening("Write the test"), agent = "a2"))

        assertEquals(listOf("main", "sub", "sub"), asked("s1"), "the main thread once, then each subagent's first turn")
    }

    @Test
    fun `a session first seen mid-conversation has no first turn, and a capability on any turn is called on each`() =
        runBlocking {
            val first = rig.capability("first")
            val every = rig.capability("every").copy(
                rule = CapabilityRule(turn = CapabilityTurn.ANY),
                result = CapabilityResult(place = CapabilityPlace.NONE),
            )
            val head = rig.head(listOf(first, every))

            rig.send(head, Turn("resumed", later("Fix the bug", "go on")))
            rig.send(head, Turn("resumed", later("Fix the bug", "and again")))

            assertEquals(listOf("every", "every"), asked("resumed"))
        }

    @Test
    fun `a request without tools is not a turn, and does not take the thread's first turn`() = runBlocking {
        val head = rig.head(listOf(rig.capability("brief")))

        rig.send(head, Turn("s1", opening("Write a title for this session"), tools = false))
        assertEquals(emptyList<String>(), asked("s1"), "Claude Code's side request")
        rig.send(head, Turn("s1", opening("Fix the bug")))

        assertEquals(listOf("brief"), asked("s1"), "the turn after it is the first turn")
    }

    @Test
    fun `two capabilities run in the order splice-toml declares them and their texts stay in that order`() =
        runBlocking {
            rig.reply = { name -> result("from $name") }
            val head = rig.head(listOf(rig.capability("lint"), rig.capability("brief")))

            rig.send(head, Turn("s1", opening("Fix the bug")))
            rig.send(head, Turn("s1", later("Fix the bug", "go on")))

            assertEquals(listOf("lint", "brief"), asked("s1"))
            rig.provider.forEach { body ->
                assertEquals(listOf("Fix the bug", "from lint", "from brief"), firstUserTexts(body), body)
            }
        }

    @Test
    fun `models name the model splice resolved the request to`() = runBlocking {
        val small = rig.capability("small").copy(rule = CapabilityRule(models = listOf("astra-small")))
        val head = rig.head(listOf(small))

        rig.send(head, Turn("large", opening("Fix the bug")))
        rig.send(head, Turn("small", opening("Fix the bug"), model = "claude-astra--astra-small"))

        assertEquals(emptyList<String>(), asked("large"))
        assertEquals(listOf("small"), asked("small"))
    }

    @Test
    fun `no capability is called for the auto-mode review or for a turn holding a model-server slot`() = runBlocking {
        val any = rig.capability("any").copy(
            rule = CapabilityRule(CapabilityTurn.ANY, CapabilityThread.ANY, CapabilityMode.ANY),
            result = CapabilityResult(place = CapabilityPlace.NONE),
        )
        val head = rig.head(listOf(any))
        val slotted = rig.head(listOf(any)) { tuning ->
            SlotHolding(PassthroughProvider(tuning, PassthroughQuirks(providerTag = HEAD)))
        }

        rig.send(head, Turn("review", opening("May this run?"), system = REVIEW))
        rig.send(slotted, Turn("slot", opening("Fix the bug")))
        rig.send(head, Turn("plain", opening("Fix the bug")))

        assertEquals(emptyList<String>(), asked("review"), "the auto-mode review")
        assertEquals(emptyList<String>(), asked("slot"), "a turn that holds a model-server slot")
        assertEquals(listOf("any"), asked("plain"), "the same capability on an ordinary turn")
    }

    /** A provider whose every built turn holds a model-server slot of its own, as a llama-server lease does. */
    private class SlotHolding(private val delegate: Provider) : Provider by delegate {
        override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn =
            delegate.buildTurn(body, compact, sessionId).copy(onEnd = TurnEnd { })
    }
}

/** why: the opening of the policy Claude Code 2.1.325 sends as the auto-mode review's system text. */
private const val REVIEW = "You are a security monitor for autonomous AI coding agents. Judge the action."
