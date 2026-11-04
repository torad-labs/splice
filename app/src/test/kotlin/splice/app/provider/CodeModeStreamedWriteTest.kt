// Goal (Marcos, Nov 3, 2026, option 1: "like native claude code"): when GPT's code-mode script starts by writing a
// file with tools.Write, Claude Code shows Write(path) as soon as the path is written, and the file's content streams
// into it while GPT writes it, as a native Write does. The file Claude Code writes is exactly the one the script asks
// for, and its result carries the script on. When the script turns out not to ask for that Write as streamed, Claude
// Code writes nothing from that block.
//
// The shape is the Mac session's 10:26 PM CT turn: one script wrote a 52 KB stylesheet, and the client saw nothing for
// five and a half minutes, because the script reached the runtime, and its Write the client, only once GPT finished it.
//
// These drive a codex head in code mode the way Claude Code does, through /v1/messages, in front of a scripted
// Responses backend that streams the script a piece at a time and pauses mid-file, and a scripted runtime that asks the
// client for the Write the script makes.
package splice.app.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.util.JsonScalars
import splice.head.scripted.ClientAnswer
import splice.head.scripted.withScriptedHeads
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions
import java.nio.file.Path

private const val STYLESHEET_PATH = "/w/site/styles.css"
private const val FIRST_PART = "@font-face{font-family:\"DM Sans\";src:url(dm.woff2)}\nbody{margin:0;"
private const val SECOND_PART = "color:#222}\n.card{padding:1rem}\n"

/** The stylesheet script as GPT streams it, a piece a delta; the backend pauses before [HOLD_AT]. */
private val STYLESHEET_PIECES = listOf(
    "text(await tools.Write({file_path:\"$STYLESHEET_PATH\",",
    "content:`@font-face{font-family:\"DM Sans\";",
    "src:url(dm.woff2)}\nbody{margin:0;",
    "color:#222}\n.card{padding:1rem}\n",
    "`}));",
)
private const val HOLD_AT = 3

/** How long, in real time, the client waits for the Write block while GPT is paused mid-file. */
private const val EARLY_WAIT_MS = 10_000L

private const val WROTE = "File created successfully"
private const val REFUSED = "InputValidationError: Write failed"
private const val DONE = "The stylesheet is written."
private const val FIXED = "The script had a syntax error; writing it again."

/** Claude Code's Read and Write, as the client declares them. */
private val WRITE_TOOLS = buildJsonArray {
    addJsonObject {
        put("name", "Read")
        put("description", "Read a file")
        putJsonObject("input_schema") { put("type", "object") }
    }
    addJsonObject {
        put("name", "Write")
        put("description", "Write a file")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("file_path") { put("type", "string") }
                putJsonObject("content") { put("type", "string") }
            }
            putJsonArray("required") {
                add("file_path")
                add("content")
            }
        }
    }
}

class CodeModeStreamedWriteTest {
    @ParameterizedTest(name = "over the websocket: {0}")
    @ValueSource(booleans = [false, true])
    fun `a script that starts with a Write fills the client's Write block while GPT writes the file`(
        webSocket: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        withScriptedHeads(tmp) {
            val gpt = ScriptedGpt()
            val ledger = BackendLedger(this, gpt)
            val call = write(STYLESHEET_PATH, FIRST_PART + SECOND_PART)
            val rig = CodeModeRig.start(this, tmp, ledger, CodeModeRigHead(webSocket, runtime = { calls(call) }))
            val talk = CodeModeConversation("streamed-write-$webSocket", "Write the stylesheet.", WRITE_TOOLS)
            val gate = CompletableDeferred<Unit>()
            var whileWriting: ClientWrite? = null

            val answer = rig.watch(talk, gpt.execInPieces(STYLESHEET_PIECES, HOLD_AT, gate)) { stream ->
                val seen = withTimeoutOrNull(EARLY_WAIT_MS) {
                    stream.awaitAnswer { writes(it).any { block -> json(FIRST_PART) in block.input } }
                }
                whileWriting = seen?.let { writes(it).singleOrNull() }
                gate.complete(Unit)
            }

            val early = whileWriting ?: fail(
                "the client held no Write block with the file's first part while GPT was still writing it: " +
                    answer.describe(),
            )
            assertEquals("Write", early.name)
            assertTrue(
                early.input.startsWith("{\"file_path\":\"$STYLESHEET_PATH\",\"content\":\""),
                "the block names the path before the content: ${early.input}",
            )
            val written = writes(answer).let { blocks ->
                assertEquals(1, blocks.size, "the client was given one Write, the streamed one: $blocks")
                blocks.single()
            }
            assertEquals(early.id, written.id, "the block that streamed is the one the client runs")
            assertEquals(call.arguments, Json.parseToJsonElement(written.input).jsonObject)

            talk.result(ledger.toolCall(talk, answer, rig), WROTE)
            val done = rig.turn(talk, gpt.answer(DONE))
            assertEquals(DONE, done.text, "the Write's result carried the script on: ${done.describe()}")
            ledger.assertPaired("a streamed Write over ${if (webSocket) "the websocket" else "HTTP"}", rig)
        }
    }

    @ParameterizedTest(name = "over the websocket: {0}")
    @ValueSource(booleans = [false, true])
    fun `a Write whose content the script changes after its literal writes only what the script asked for`(
        webSocket: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        withScriptedHeads(tmp) {
            val gpt = ScriptedGpt()
            val ledger = BackendLedger(this, gpt)
            val call = write("/w/notes.txt", "padded")
            val rig = CodeModeRig.start(this, tmp, ledger, CodeModeRigHead(webSocket, runtime = { calls(call) }))
            val talk = CodeModeConversation("changed-write-$webSocket", "Write the notes.", WRITE_TOOLS)
            val pieces = listOf(
                "text(await tools.Write({file_path:\"/w/notes.txt\",",
                "content:\"  padded  \"",
                ".trim()}));",
            )

            val answer = rig.turn(talk, gpt.execInPieces(pieces, pieces.size, CompletableDeferred(Unit)))

            val (runs, refused) = writes(answer).partition { runnable(it) }
            val ran = runs.map { Json.parseToJsonElement(it.input).jsonObject }
            assertEquals(listOf(call.arguments), ran, answer.describe())
            refused.forEach { assertRefused(it, answer) }
            val asked = talk.answered(answer)
            asked.forEach { id -> talk.result(id, if (runs.any { it.id == id }) WROTE else REFUSED) }
            val done = rig.turn(talk, gpt.answer(DONE))
            assertEquals(DONE, done.text, done.describe())
            ledger.assertPaired("a changed Write over ${if (webSocket) "the websocket" else "HTTP"}", rig)
        }
    }

    @ParameterizedTest(name = "over the websocket: {0}")
    @ValueSource(booleans = [false, true])
    fun `a script that never makes the Write it streamed leaves the client nothing to write`(
        webSocket: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        withScriptedHeads(tmp) {
            val gpt = ScriptedGpt()
            val ledger = BackendLedger(this, gpt)
            val broken = CodeModeStep.Completed("", error = "SyntaxError: Unexpected token ')'")
            val head = CodeModeRigHead(webSocket, runtime = { ScriptedRuntime(listOf(broken)) })
            val rig = CodeModeRig.start(this, tmp, ledger, head)
            val talk = CodeModeConversation("broken-write-$webSocket", "Write the script.", WRITE_TOOLS)
            val pieces = listOf(
                "text(await tools.Write({file_path:\"/w/broken.js\",",
                "content:\"let a = 1;\"}))",
                "))",
            )
            val script = gpt.execInPieces(pieces, pieces.size, CompletableDeferred(Unit))

            val answer = rig.turn(talk, script, gpt.answer(FIXED))

            writes(answer).forEach { assertRefused(it, answer) }
            val refused = talk.answered(answer)
            refused.forEach { id -> talk.result(id, REFUSED) }
            val reached = answer.text + if (refused.isEmpty()) "" else rig.turn(talk, gpt.answer(FIXED)).text
            assertTrue(FIXED in reached, "GPT never got to answer its script's error: ${answer.describe()}")
            ledger.assertPaired("a Write the script never made over ${if (webSocket) "the websocket" else "HTTP"}", rig)
        }
    }

    /** Claude Code refuses an input with a parameter its Write does not take, so it writes nothing. */
    private fun assertRefused(block: ClientWrite, answer: ClientAnswer) {
        assertTrue(
            !runnable(block),
            "the client was given a Write it would run, not the script's: ${block.input}\n${answer.describe()}",
        )
    }

    /** A Write Claude Code runs: its input holds only the parameters Write takes. */
    private fun runnable(block: ClientWrite): Boolean =
        setOf("file_path", "content").containsAll(Json.parseToJsonElement(block.input).jsonObject.keys)

    private fun write(path: String, content: String): CodeModeCall = CodeModeCall(
        "write-1",
        "Write",
        buildJsonObject {
            put("file_path", path)
            put("content", content)
        },
    )

    private fun calls(call: CodeModeCall): NativeCodeModeLifecycle =
        ScriptedRuntime(listOf(CodeModeStep.Calls(listOf(call)), CodeModeStep.Completed("wrote")))

    private fun json(text: String): String = JsonPrimitive(text).toString().removeSurrounding("\"")

    /** The client's tool_use blocks so far: each one's id, name, and the input its deltas spell. */
    private fun writes(answer: ClientAnswer): List<ClientWrite> {
        val opened = linkedMapOf<Int, ClientWrite>()
        answer.events.mapNotNull { runCatching { Json.parseToJsonElement(it.data).jsonObject }.getOrNull() }
            .forEach { data ->
                val index = JsonScalars.int(data, "index") ?: return@forEach
                val block = data["content_block"] as? JsonObject
                val delta = data["delta"] as? JsonObject
                when {
                    block != null && JsonScalars.str(block, "type") == "tool_use" ->
                        opened[index] =
                            ClientWrite(JsonScalars.strOrEmpty(block["id"]), JsonScalars.strOrEmpty(block["name"]), "")
                    delta != null && JsonScalars.str(delta, "type") == "input_json_delta" -> opened[index]?.let {
                        opened[index] = it.copy(input = it.input + JsonScalars.str(delta, "partial_json").orEmpty())
                    }
                }
            }
        return opened.values.toList()
    }
}

private data class ClientWrite(val id: String, val name: String, val input: String)

/** A runtime whose one cell takes [steps] in order, whatever the script says. */
private class ScriptedRuntime(private val steps: List<CodeModeStep>) : NativeCodeModeLifecycle {
    override suspend fun startNativeSession(
        sessionKey: String,
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String>,
        options: NativeCodeModeOptions,
    ): NativeCodeModeCell = ScriptedCodeModeCell("write", ArrayDeque(steps))

    override fun close() = Unit
}
