package splice.provider.codex.stream.early

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.GatewayCustomCall
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink
import kotlin.random.Random

/**
 * Goal (Marcos, Nov 3, 2026, "like native claude code"): the Write block a script's opening tools.Write streams into is
 * the one Claude Code runs only when it is exactly the call the script made; any other ending reaches Claude Code as an
 * input it refuses, so it writes nothing. Whatever order the script's deltas, the runtime's claim and finish, the
 * round's own blocks, detaches and the step's end come in, every early block closes exactly once, with the claimed
 * call's arguments or refused, no frame names it after it closed or was dropped, and none opens inside a block the
 * round holds.
 */
class CodeModeEarlyBlocksTest {
    @Test
    fun `every early block closes once, as the claimed call or refused, and never inside a round block`() {
        repeat(RUNS) { seed ->
            val run = Run(Random(seed))
            runBlocking { run.play() }
            run.sink.violations.forEach { fail -> throw AssertionError("seed $seed: $fail\n${run.log}") }
            run.sink.writes().forEach { block ->
                assertEquals(1, block.stops, "seed $seed: a Write block closes once\n${run.log}")
                val input = runCatching { Json.parseToJsonElement(block.input.toString()).jsonObject }.getOrNull()
                val claimed = run.finished[block.id]
                assertTrue(
                    input != null && (NOT_WRITTEN in input || input == claimed),
                    "seed $seed: ${block.input} is neither refused nor the claimed call $claimed\n${run.log}",
                )
                assertTrue(!block.insideRound, "seed $seed: a Write block opened inside a round block\n${run.log}")
            }
        }
    }

    /** One generated step: a script or two streaming, the round's own blocks, the runtime's calls, and the step's end. */
    private class Run(private val random: Random) {
        val sink = RecordingSink()
        val log = StringBuilder()
        val finished = mutableMapOf<String, JsonObject>()
        private val early = CodeModeEarlyBlocks().apply { enabled = true }
        private val script = scripts()
        private val claims = mutableListOf<Pair<String, JsonObject>>()
        private var attached = true
        private var round: WireBlockIndex? = null

        suspend fun play() {
            repeat(random.nextInt(1, MAX_ACTIONS)) { act(random.nextInt(SCRIPT_WEIGHT + OTHER_ACTIONS)) }
            log.append("step end\n")
            early.abandon()
            sink.closeAll()
        }

        /** The script streams most often; a discard, which drops the step's draft, is the rarest. */
        private suspend fun act(action: Int) {
            when (action) {
                in 0 until SCRIPT_WEIGHT -> script.removeFirstOrNull()?.let { observe(it) }
                SCRIPT_WEIGHT -> roundBlock()
                SCRIPT_WEIGHT + 1 -> claim()
                SCRIPT_WEIGHT + 2 -> finish()
                SCRIPT_WEIGHT + 3 -> refuse()
                SCRIPT_WEIGHT + 4 -> attachment()
                else -> discard()
            }
        }

        private suspend fun observe(event: CustomToolSource) {
            log.append("observe $event attached=$attached round=${round != null}\n")
            early.observe(event, sink.takeIf { attached }, idle = round == null)
        }

        /** The round opens a block of its own (its core refuses the early block first), or closes the one it holds. */
        private suspend fun roundBlock() {
            val held = round
            if (held != null) {
                log.append("round closes its block\n")
                sink.closeBlock(held)
                round = null
                sink.roundOpen = false
                return
            }
            log.append("round opens a block\n")
            early.refuse()
            round = sink.openText()
            sink.roundOpen = true
        }

        private fun claim() {
            val arguments = arguments()
            val id = early.claim(CODE_MODE_EARLY_WRITE_TOOL, arguments)
            log.append("claim $arguments -> $id\n")
            if (id != null) claims += id to arguments
        }

        private suspend fun finish() {
            val (id, arguments) = claims.removeFirstOrNull() ?: return
            val done = early.finish(sink, id, arguments)
            log.append("finish $id -> $done\n")
            if (done) finished[id] = arguments
        }

        private suspend fun refuse() {
            log.append("refuse\n")
            early.refuse()
        }

        private fun attachment() {
            attached = !attached
            log.append("attached=$attached\n")
        }

        private fun discard() {
            log.append("discard\n")
            early.discard()
            sink.discard()
            claims.clear()
            round = null
            sink.roundOpen = false
        }

        /** The call the runtime makes: the script's own, one that adds to it, or one that differs from the start. */
        private fun arguments(): JsonObject {
            val content = when (random.nextInt(CALL_KINDS)) {
                0 -> CONTENT + "more"
                1 -> "other $CONTENT"
                else -> CONTENT
            }
            val path = if (random.nextInt(CALL_KINDS) == 0) "/w/elsewhere.txt" else PATH
            return buildJsonObject {
                put("file_path", path)
                put("content", content)
            }
        }

        /** The opening script cut into deltas, sealed or not, and now and then a retry written again from the top. */
        private fun scripts(): ArrayDeque<CustomToolSource> {
            val events = ArrayDeque<CustomToolSource>()
            listOf("call-1", "call-2").take(random.nextInt(1, SCRIPTS + 1)).forEach { id ->
                val pieces = pieces(SCRIPT)
                events += CustomToolSource.Started(exec(id, pieces.first()))
                pieces.drop(1).forEach { events += CustomToolSource.Delta(id, it) }
                if (random.nextBoolean()) {
                    events += CustomToolSource.Completed(exec(id, SCRIPT))
                }
            }
            return events
        }

        private fun exec(id: String, input: String) = GatewayCustomCall(id, "exec", input, JsonObject(emptyMap()))

        private fun pieces(text: String): List<String> {
            val parts = mutableListOf<String>()
            var at = 0
            while (at < text.length) {
                val end = minOf(text.length, at + random.nextInt(1, MAX_PIECE))
                parts += text.substring(at, end)
                at = end
            }
            return parts
        }
    }

    /** A client stream: the blocks opened on it, what each was written, how often it closed, and any frame that named
     *  a block after it closed or was dropped. */
    private class RecordingSink : WireSink {
        var roundOpen = false
        val violations = mutableListOf<String>()
        private val blocks = mutableListOf<Recorded>()

        fun writes(): List<Recorded> = blocks.filter { it.name == CODE_MODE_EARLY_WRITE_TOOL && !it.dropped }

        override suspend fun openThinking(): WireBlockIndex = open("", "thinking")

        override suspend fun openText(): WireBlockIndex = open("", "text")

        override suspend fun openTool(id: String, name: String): WireBlockIndex = open(id, name)

        override suspend fun textDelta(index: WireBlockIndex, text: String) = write(index, text)

        override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = write(index, thinking)

        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = write(index, partialJson)

        override suspend fun addTextBlock(text: String) = Unit

        override suspend fun addRedactedThinking(data: String) = Unit

        override suspend fun closeBlock(index: WireBlockIndex) {
            val block = blocks[index.value]
            if (block.dropped) violations += "a dropped block was closed: ${block.id}"
            block.stops++
        }

        override suspend fun closeAll() {
            blocks.filter { it.stops == 0 && !it.dropped }.forEach { it.stops++ }
        }

        override fun discard() {
            blocks.filter { it.stops == 0 }.forEach { it.dropped = true }
        }

        private fun open(id: String, name: String): WireBlockIndex {
            blocks += Recorded(id, name, insideRound = roundOpen)
            return WireBlockIndex(blocks.size - 1)
        }

        private fun write(index: WireBlockIndex, text: String) {
            val block = blocks[index.value]
            when {
                block.dropped -> violations += "a dropped block was written: ${block.id}"
                block.stops > 0 -> violations += "a closed block was written: ${block.id} <- $text"
                else -> block.input.append(text)
            }
        }
    }

    private data class Recorded(val id: String, val name: String, val insideRound: Boolean) {
        val input = StringBuilder()
        var stops = 0
        var dropped = false
    }
}

private const val RUNS = 2000
private const val MAX_ACTIONS = 90
private const val SCRIPT_WEIGHT = 30
private const val OTHER_ACTIONS = 6
private const val CALL_KINDS = 4
private const val SCRIPTS = 2
private const val MAX_PIECE = 12
private const val PATH = "/w/site/notes.txt"
private const val CONTENT = "first line\nsecond line"
private const val SCRIPT = "text(await tools.Write({file_path:\"$PATH\",content:\"first line\\nsecond line\"}));"
private const val NOT_WRITTEN = "splice_not_written"
