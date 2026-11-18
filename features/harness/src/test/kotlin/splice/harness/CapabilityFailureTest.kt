// NEW: a capability that fails lets the turn go on or refuses it, as declared (docs/specs/harness.md, decisions 3 and
// 6). Each test drives turns through a head against a worker outside splice that fails one way or another, and reads
// what the client's stream carried and what the provider got.
package splice.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.capability.Capability
import splice.core.capability.CapabilityFailure
import splice.core.capability.CapabilityMay
import splice.core.capability.CapabilityResult
import splice.core.capability.CapabilityWorker
import java.nio.file.Path

class CapabilityFailureTest(@param:TempDir private val tmp: Path) {
    private val rig = CapabilityRig(tmp)

    @AfterEach
    fun close() = rig.close()

    /** One way a worker fails: its capability as declared, and how the worker answers it. */
    private data class Failing(val what: String, val capability: Capability, val reply: WorkerReply)

    private fun failings(): List<Failing> {
        val ok = WorkerReply(emptyList())
        return listOf(
            Failing("cannot be reached", rig.capability("unreachable", rig.nobody), ok),
            Failing("answers 500", rig.capability("broken"), WorkerReply(emptyList(), status = 500)),
            Failing("sends a line that is none of the three", rig.capability("garbled"), WorkerReply(listOf(DONE))),
            Failing("ends with no result", rig.capability("unended"), WorkerReply(listOf(PROGRESS))),
            Failing("refuses where it may only add", rig.capability("adder"), refusal("this names a customer")),
            Failing(
                "passes timeout_s",
                rig.capability("slow").let { it.copy(limits = it.limits.copy(timeoutS = 1)) },
                WorkerReply(listOf(PROGRESS), held = true),
            ),
        )
    }

    private fun failing(on: CapabilityFailure): List<Failing> = failings().map { f ->
        f.copy(capability = f.capability.copy(limits = f.capability.limits.copy(onFailure = on)))
    }.also { all -> rig.reply = { name -> all.first { it.capability.name == name }.reply } }

    @Test
    fun `closed, each failure refuses the turn naming the capability, and nothing goes to the provider`() =
        runBlocking {
            failing(CapabilityFailure.CLOSED).forEach { f ->
                val head = rig.head(listOf(f.capability))
                val before = rig.provider.size

                val stream = rig.send(head, Turn(f.what, opening("Fix the bug")))

                assertRefused(stream, "${f.capability.name} could not finish its work", f.what)
                assertEquals(before, rig.provider.size, "${f.what}: the provider got nothing")
            }
        }

    @Test
    fun `open, each failure lets the turn go with today's bytes, and the thread is not asked for again`() =
        runBlocking {
            val today = rig.head(emptyList())
            failing(CapabilityFailure.OPEN).forEach { f ->
                val head = rig.head(listOf(f.capability))
                val first = Turn(f.what, opening("Fix the bug"))
                val next = Turn(f.what, later("Fix the bug", "go on"))

                rig.send(today, first)
                rig.send(today, next)
                val expected = rig.provider.takeLast(2)
                val streams = listOf(rig.send(head, first), rig.send(head, next))

                assertEquals(expected, rig.provider.takeLast(2), "${f.what}: the provider got today's bytes")
                streams.forEach { assertTrue(it.contains("ok from the provider"), "${f.what}: $it") }
                // A worker nothing listens for is never posted; every other one is posted once, for the first turn.
                val once = if ((f.capability.work.worker as CapabilityWorker.Http).url == rig.workerUrl) 1 else 0
                assertEquals(once, rig.asked.count { it.session == f.what }, "${f.what}: not asked for again")
            }
        }

    @Test
    fun `a refusal from a capability that may refuse refuses the turn with its reason, and the next turn is called`() =
        runBlocking {
            val gate = rig.capability("gate").copy(result = CapabilityResult(may = CapabilityMay.ADD_OR_REFUSE))
            val head = rig.head(listOf(gate))
            rig.reply = { refusal("the request names no file to change.") }

            val refused = rig.send(head, Turn("s1", opening("Fix it")))
            assertRefused(refused, "gate refused this turn: the request names no file to change", "a refusal")
            assertEquals(emptyList<String>(), rig.provider, "the provider got nothing")

            rig.reply = { result("src/main.ts holds it.") }
            rig.send(head, Turn("s1", opening("Fix the bug in src/main.ts")))

            assertEquals(2, rig.asked.size, "the refused turn was not the thread's first turn")
            val placed = firstUserTexts(rig.provider.single())
            assertEquals(listOf("Fix the bug in src/main.ts", "src/main.ts holds it."), placed)
        }

    /** The client's [stream] says [said] first as a finished notice, then as the stream's error in Anthropic's error
     *  shape, which Claude Code prints rather than sending the turn again. */
    private fun assertRefused(stream: String, said: String, what: String) {
        val notice = stream.indexOf(said)
        val stop = stream.indexOf("event: content_block_stop", notice)
        val error = stream.indexOf("event: error")
        assertTrue(notice in 0 until stop && stop < error, "$what: the notice is finished before the error: $stream")
        val data = stream.substring(error).lineSequence().first { it.startsWith("data:") }.removePrefix("data:")
        val body = Json.parseToJsonElement(data).jsonObject
        val inner = body.getValue("error").jsonObject
        assertEquals("error", body.getValue("type").jsonPrimitive.content, what)
        assertEquals("invalid_request_error", inner.getValue("type").jsonPrimitive.content, what)
        assertTrue(inner.getValue("message").jsonPrimitive.content.contains(said), "$what: $data")
    }
}

private const val PROGRESS = """{"type":"progress","message":"Reading"}"""
private const val DONE = """{"type":"done"}"""
