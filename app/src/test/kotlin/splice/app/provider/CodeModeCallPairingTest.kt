// Goal (fix 1 of the Oct 27 Responses fixes, artifacts/responses-gaps-2026-10-27/goals.md): a person using GPT through
// an openai-responses head never has a turn refused because splice sent a tool call without its result, and GPT never
// reads its own reasoning twice. After an interruption (an overload mid-stream, a re-anchor, a websocket round that
// falls back to HTTP, a compaction), the next request carries each call once, followed by its result, and each
// reasoning item once, in the order GPT wrote them. Where a call's result was sent (the client's tool result, or a
// script's output), that result goes out again, never a stand-in; a call whose result never existed is answered the way
// Codex answers it.
//
// The shapes are the two refusals of Oct 26 and 27. At 04:55 CT an overload mid-stream re-anchored a code-mode turn,
// continuing from partial output; the rebuild kept 198 records as ordinary tool calls, and the next request carried
// each reasoning item twice and 11 wait calls with no result, refused over the websocket and again over HTTP with "No
// tool output found for function call". At 23:23 CT a compaction turn was refused the same way.
//
// These drive a codex head in code mode the way Claude Code does, through /v1/messages, in front of a scripted
// Responses backend that streams as GPT does (each reasoning item with the stream's empty content, a script that
// yields, a wait on it, a script that asks the client for a tool call) and a scripted code-mode runtime. Everything
// between them is the head's own. What they check is what the backend reads for every request: an HTTP body's input,
// and for a chained websocket frame the input of the response it continues, that response's output, then the frame's
// own input.
package splice.app.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonScalars
import splice.head.scripted.Transport
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path
import kotlin.random.Random

private const val CLIENT_RESULT = "synthetic file contents"
private const val PARTIAL = "partial words"
private const val REST = " and the rest"
private const val SUMMARY = "synthetic summary of the conversation"

/** The words a head's re-anchor asks GPT to carry an interrupted answer on with, instead of starting it again. */
private const val REANCHOR_MARKER = "Your previous stream was interrupted mid-answer."

/** What the head logs when a re-anchor carries the round on from what arrived, as the 04:55 turn's did. */
private const val CONTINUED = "continuing from partial output"

/** The result Claude Code sends for the first prompt's tool call once it changed that part of its history. */
private const val CHANGED_RESULT = "synthetic file contents as the client now sends them"
private const val FIRST_ANSWER = "synthetic first answer"
private const val NEXT_PROMPT = "Go on with the synthetic task."

private const val PROPERTY_RUNS = 24
private const val MAX_TOOL_TURNS = 3

class CodeModeCallPairingTest {

    @Test
    fun `an overload mid-stream that the head re-anchors leaves every call of a code-mode turn answered once`(
        @TempDir tmp: Path,
    ) = runBlocking { overloadThenReanchor(tmp, webSocket = false) }

    @Test
    fun `an overload mid-stream on the websocket, carried on over HTTP, leaves every call answered once`(
        @TempDir tmp: Path,
    ) = runBlocking { overloadThenReanchor(tmp, webSocket = true) }

    @Test
    fun `a compaction in the middle of a code-mode tool loop sends every call with its result over HTTP`(
        @TempDir tmp: Path,
    ) = runBlocking { compactionMidLoop(tmp, webSocket = false) }

    @Test
    fun `a compaction in the middle of a code-mode tool loop sends every call with its result over the websocket`(
        @TempDir tmp: Path,
    ) = runBlocking { compactionMidLoop(tmp, webSocket = true) }

    @Test
    fun `an overload mid-stream past a result Claude Code changed goes on over HTTP with every call answered`(
        @TempDir tmp: Path,
    ) = runBlocking { overloadPastChangedResult(tmp, webSocket = false) }

    @Test
    fun `an overload mid-stream past a result Claude Code changed goes on over the websocket, answered`(
        @TempDir tmp: Path,
    ) = runBlocking { overloadPastChangedResult(tmp, webSocket = true) }

    @Test
    fun `a compaction whose stream drops, past a result Claude Code changed, sends each call's result over HTTP`(
        @TempDir tmp: Path,
    ) = runBlocking { droppedCompactionPastChangedResult(tmp, webSocket = false) }

    @Test
    fun `a compaction whose stream drops, past a result Claude Code changed, sends each result on the socket`(
        @TempDir tmp: Path,
    ) = runBlocking { droppedCompactionPastChangedResult(tmp, webSocket = true) }

    @Test
    fun `every request a code-mode head sends pairs each call with one result and each reasoning id once`(
        @TempDir tmp: Path,
    ) = runBlocking {
        withScriptedHeads(tmp) {
            val gpt = ScriptedGpt()
            val ledger = BackendLedger(this, gpt)
            val http = CodeModeRig.start(this, tmp, ledger, webSocket = false)
            val socket = CodeModeRig.start(this, tmp, ledger, webSocket = true)
            val stories = mutableListOf<String>()
            repeat(PROPERTY_RUNS) { run ->
                val random = Random(run)
                val rig = if (random.nextBoolean()) socket else http
                stories += "run $run: " + GeneratedSession(rig, gpt, random, run).play()
            }
            ledger.assertPaired("generated sessions:\n${stories.joinToString("\n")}", http, socket)
        }
    }

    /** The 04:55 shape: a script that yields, a wait on it that asks the client to Read, the client's result, then GPT
     *  reasons and the backend overloads mid-stream. Over HTTP the head continues from the reasoning that arrived
     *  ("continuing from partial output"); on the websocket the round falls back to HTTP, as the 04:55 turn's did.
     *  Either way the next request is the head's own rebuild of the history. */
    private suspend fun overloadThenReanchor(tmp: Path, webSocket: Boolean) = withScriptedHeads(tmp) {
        val gpt = ScriptedGpt()
        val ledger = BackendLedger(this, gpt)
        val rig = CodeModeRig.start(this, tmp, ledger, webSocket)
        val talk = CodeModeConversation("synthetic-reanchor-session", "Start the synthetic code-mode task.")

        val first = rig.turn(talk, gpt.exec("cell:c1 yield read"), gpt.waitOn("c1"))
        talk.result(ledger.toolCall(talk, first, rig), CLIENT_RESULT)
        val overloaded = ledger.requestCount()
        val second = rig.turn(talk, gpt.overloadAfterReasoning(), gpt.answer(REST))

        // The person reads the answer, not the overload.
        assertEquals(REST, second.text, "the turn went on after the overload: ${second.describe()}")
        // The head carried the turn on: over HTTP from what arrived, on the websocket over HTTP.
        val next = ledger.reads().drop(overloaded + 1)
        assertTrue(
            next.any { if (webSocket) it.transport == Transport.HTTP else REANCHOR_MARKER in it.body },
            "the head carried the turn on after the overload: ${ledger.summary(rig)}",
        )
        ledger.assertPaired("the 04:55 shape over ${if (webSocket) "the websocket" else "HTTP"}", rig)
    }

    /** The 23:23 shape: Claude Code compacts between a tool call and its run, so the compaction carries the previous
     *  request with the summary instruction appended, and the answer with the new call is dropped. */
    private suspend fun compactionMidLoop(tmp: Path, webSocket: Boolean) = withScriptedHeads(tmp) {
        val gpt = ScriptedGpt()
        val ledger = BackendLedger(this, gpt)
        val rig = CodeModeRig.start(this, tmp, ledger, webSocket)
        val talk = CodeModeConversation("synthetic-compaction-session", "Start the synthetic compaction task.")

        val first = rig.turn(talk, gpt.exec("cell:c1 yield read"), gpt.waitOn("c1"))
        talk.result(ledger.toolCall(talk, first, rig), CLIENT_RESULT)
        val second = rig.turn(talk, gpt.exec("cell:c2 read"))
        assertEquals(
            1,
            CodeModeConversation.toolUses(CodeModeConversation.content(second)).size,
            "${second.describe()}\n${ledger.summary(rig)}",
        )
        talk.compact()
        val summary = rig.turn(talk, gpt.answer(SUMMARY))

        assertTrue(SUMMARY in summary.text, "the compaction was answered: ${summary.describe()}")
        assertTrue(ledger.reads().any { SUMMARY_ASK in it.body }, "the compaction reached the backend")
        ledger.assertPaired("the 23:23 shape over ${if (webSocket) "the websocket" else "HTTP"}", rig)
    }

    /** The 04:55 shape whole: GPT answered one prompt after a script and a wait, the next prompt ran a script and a
     *  wait that asked the client to Read, Claude Code changed the first prompt's result, and the backend overloads
     *  mid-stream, after GPT's reasoning. The head carries the round on from what arrived, over a history where the
     *  changed script goes out as Claude Code's plain tool call and the other as GPT's script.
     *  On the websocket the first overload sends the round over HTTP, where the second one re-anchors it, and the
     *  round carried on goes out on the websocket again, as rounds 2 to 4 of the 04:55 turn went. (An overload while
     *  the script itself streams fails the turn on this head, a decision of the failure-handling work this fix leaves
     *  alone.) */
    private suspend fun overloadPastChangedResult(tmp: Path, webSocket: Boolean) = withScriptedHeads(tmp) {
        val gpt = ScriptedGpt()
        val ledger = BackendLedger(this, gpt)
        val rig = CodeModeRig.start(this, tmp, ledger, webSocket)
        val talk = CodeModeConversation("synthetic-changed-overload", "Start the synthetic code-mode task.")
        val changed = twoPrompts(rig, ledger, gpt, talk)
        talk.changeResult(changed, CHANGED_RESULT)

        val answers = listOfNotNull(
            gpt.overloadAfterReasoning().takeIf { webSocket },
            gpt.overloadAfterReasoning(),
            gpt.answer(REST),
        )
        val overloaded = ledger.requestCount()
        val last = rig.turn(talk, *answers.toTypedArray())

        val context = "the 04:55 shape over ${if (webSocket) "the websocket" else "HTTP"}"
        assertTrue(REST in last.text, "the turn went on after the overload: ${last.describe()}\n${ledger.summary(rig)}")
        ledger.assertLogged(rig, CONTINUED, context)
        assertPlainCall(ledger, overloaded, changed, "$context\n${ledger.summary(rig)}")
        val carried = ledger.reads().drop(overloaded).filter { REANCHOR_MARKER in it.body }.map { it.transport }
        assertEquals(
            listOf(if (webSocket) Transport.WEBSOCKET else Transport.HTTP),
            carried,
            "$context: the request that carried the round on\n${ledger.summary(rig)}",
        )
        ledger.assertPaired(context, rig)
    }

    /** The 23:23 shape whole: past the same two prompts, Claude Code compacts between a tool call and its run, and the
     *  compaction's stream drops once its summary began, with no close frame on the websocket. Claude Code changed
     *  the first prompt's result, so that script goes out as its plain tool call. The head takes the round up again. */
    private suspend fun droppedCompactionPastChangedResult(tmp: Path, webSocket: Boolean) = withScriptedHeads(tmp) {
        val gpt = ScriptedGpt()
        val ledger = BackendLedger(this, gpt)
        val rig = CodeModeRig.start(this, tmp, ledger, webSocket)
        val talk = CodeModeConversation("synthetic-changed-compaction", "Start the synthetic compaction task.")
        val changed = twoPrompts(rig, ledger, gpt, talk)
        talk.changeResult(changed, CHANGED_RESULT)
        val compacting = ledger.requestCount()
        val dropped = rig.turn(talk, gpt.exec("cell:c3 read"))
        assertEquals(1, CodeModeConversation.toolUses(CodeModeConversation.content(dropped)).size, ledger.summary(rig))

        talk.compact()
        val summary = rig.turn(talk, gpt.cutMidAnswer(PARTIAL), gpt.answer(SUMMARY))

        val context = "the 23:23 shape over ${if (webSocket) "the websocket" else "HTTP"}"
        assertTrue(SUMMARY in summary.text) {
            "the compaction was answered: ${summary.describe()}\n${ledger.summary(rig)}"
        }
        assertPlainCall(ledger, compacting, changed, "$context\n${ledger.summary(rig)}")
        ledger.assertPaired(context, rig)
    }

    /** Some request from [from] on carries the client's tool call [id] as its plain tool call, with the result Claude
     *  Code changed it to, so the shape holds both kinds of history. */
    private fun assertPlainCall(ledger: BackendLedger, from: Int, id: String, context: String) {
        val plain = ledger.reads().drop(from).any { read ->
            val at = read.input.indexOfFirst { JsonScalars.str(it, "type") == "function_call" && callId(it) == id }
            at >= 0 && read.input.drop(at + 1).any { callId(it) == id && CHANGED_RESULT in it.toString() }
        }
        assertTrue(plain) { "$context: the changed result's call never went out as Claude Code's plain tool call" }
    }

    private fun callId(item: JsonObject): String? = JsonScalars.str(item, "call_id")

    /** One prompt GPT answered after a script and a wait on it, then a second prompt whose script and wait asked the
     *  client to Read, with the client's result sent. Returns the first prompt's tool call. */
    private suspend fun twoPrompts(
        rig: CodeModeRig,
        ledger: BackendLedger,
        gpt: ScriptedGpt,
        talk: CodeModeConversation,
    ): String {
        val first = rig.turn(talk, gpt.exec("cell:c1 yield read"), gpt.waitOn("c1"))
        val read = ledger.toolCall(talk, first, rig)
        talk.result(read, CLIENT_RESULT)
        val answered = rig.turn(talk, gpt.answer(FIRST_ANSWER))
        assertEquals(emptyList<String>(), talk.answered(answered)) {
            "the first prompt was answered: ${answered.describe()}"
        }
        talk.user(NEXT_PROMPT)
        val second = rig.turn(talk, gpt.exec("cell:c2 yield read"), gpt.waitOn("c2"))
        talk.result(ledger.toolCall(talk, second, rig), CLIENT_RESULT)
        return read
    }
}

/** A generated conversation: one to three scripts, each of which may yield and be waited on before it asks the client
 *  to Read, then GPT's answer; one round may be interrupted by an overload (before any output, after reasoning, while
 *  a script is written, or once the answer began), by a websocket close before output, or by a compaction. */
private class GeneratedSession(
    private val rig: CodeModeRig,
    private val gpt: ScriptedGpt,
    private val random: Random,
    private val run: Int,
) {
    private val talk = CodeModeConversation("synthetic-generated-$run", "Start synthetic generated task $run.")
    private val scripts = 1 + random.nextInt(MAX_TOOL_TURNS)
    private val interruptTurn = random.nextInt(scripts + 1)
    private val interruption = Interruption.entries[random.nextInt(Interruption.entries.size)]

    /** Plays the session and says what it did. */
    suspend fun play(): String {
        val story = mutableListOf("scripts=$scripts interrupt=$interruption@turn$interruptTurn")
        for (turn in 0..scripts) {
            if (compactsAt(turn)) return (story + compact()).joinToString(", ")
            val answers = if (turn < scripts) script(turn) else listOf(gpt.answer("synthetic answer $run"))
            val planned = if (turn == interruptTurn) interrupt(answers) else answers
            val answer = rig.turn(talk, *planned.toTypedArray())
            val ids = talk.answered(answer)
            if (turn >= scripts) {
                story += "answered: ${answer.text.isNotEmpty()} error: ${answer.error?.type}"
                continue
            }
            val id = ids.singleOrNull()
                ?: return (story + "turn $turn asked for no tool: ${answer.error?.type}").joinToString(", ")
            talk.result(id, "$CLIENT_RESULT $run-$turn")
        }
        return story.joinToString(", ")
    }

    /** A compaction needs a tool round behind it: it is what Claude Code sends between a call and its run. */
    private fun compactsAt(turn: Int): Boolean =
        turn > 0 && turn == interruptTurn && interruption == Interruption.COMPACTION

    /** Claude Code's compaction, which may drop the answer that asked for the tool it is waiting on. */
    private suspend fun compact(): List<String> {
        val dropped = random.nextBoolean()
        if (dropped) talk.dropLastToolTurn()
        talk.compact()
        val summary = rig.turn(talk, gpt.answer(SUMMARY))
        return listOf(
            "dropped the last tool turn: $dropped",
            "compacted: ${SUMMARY in summary.text} error: ${summary.error?.type}",
        )
    }

    /** GPT's answers for script [turn]: the script, then a wait on it when it yields. */
    private fun script(turn: Int): List<GptAnswer> {
        val cell = "g$run-$turn"
        return if (random.nextBoolean()) {
            listOf(gpt.exec("cell:$cell yield read"), gpt.waitOn(cell))
        } else {
            listOf(gpt.exec("cell:$cell read"))
        }
    }

    private fun interrupt(answers: List<GptAnswer>): List<GptAnswer> {
        val round = random.nextInt(answers.size)
        val before = answers.take(round)
        val after = answers.drop(round)
        val failing = when (interruption) {
            Interruption.OVERLOAD_BEFORE_OUTPUT -> listOf(gpt.overloadBeforeOutput())
            Interruption.OVERLOAD_AFTER_REASONING -> listOf(gpt.overloadAfterReasoning())
            Interruption.OVERLOAD_MID_SCRIPT ->
                listOf(gpt.overloadMidScript("cell:cut read", preface = PARTIAL.takeIf { random.nextBoolean() }))
            Interruption.OVERLOAD_AFTER_TEXT -> listOf(gpt.overloadAfterText(PARTIAL))
            Interruption.CLOSE_BEFORE_OUTPUT -> listOf(gpt.closeBeforeOutput())
            Interruption.COMPACTION -> emptyList()
        }
        return before + failing + after
    }
}

private enum class Interruption {
    OVERLOAD_BEFORE_OUTPUT,
    OVERLOAD_AFTER_REASONING,
    OVERLOAD_MID_SCRIPT,
    OVERLOAD_AFTER_TEXT,
    CLOSE_BEFORE_OUTPUT,
    COMPACTION,
}
