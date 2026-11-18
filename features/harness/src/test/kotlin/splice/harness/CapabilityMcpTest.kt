// NEW: a capability whose worker is a tool of an MCP server (docs/specs/harness.md, decision 13). Each test drives
// turns through a head against a real server outside splice, a program on stdio started in the session's folder or a
// server at a URL, and reads what the server was sent and what the provider got.
package splice.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.capability.Capability
import splice.core.capability.CapabilityFailure
import splice.core.capability.CapabilityField
import splice.core.capability.CapabilityWork
import splice.core.capability.CapabilityWorker
import java.nio.file.Files
import java.nio.file.Path

class CapabilityMcpTest(@param:TempDir private val tmp: Path) {
    private val rig = CapabilityRig(tmp)
    private val work: Path = Files.createDirectories(tmp.resolve("work")).toRealPath()
    private val https = mutableListOf<HttpMcp>()

    @AfterEach
    fun close() {
        rig.close()
        https.forEach(HttpMcp::close)
    }

    /** A capability calling `notes/answer`, sent the first user message and the model, failing as [onFailure] says. */
    private fun capability(onFailure: CapabilityFailure = CapabilityFailure.OPEN, timeoutS: Int = 30): Capability =
        rig.capability("brief").let {
            it.copy(
                limits = it.limits.copy(timeoutS = timeoutS, onFailure = onFailure),
                work = CapabilityWork(
                    CapabilityWorker.Mcp("notes", "answer"),
                    listOf(CapabilityField.FIRST_USER_MESSAGE, CapabilityField.MODEL),
                ),
            )
        }

    private fun loading(entry: JsonObject?) = WorkerMcpServers { _, _ ->
        if (entry == null) emptyMap() else mapOf("notes" to entry)
    }

    private fun stdio(does: Does) = StdioMcp(tmp.resolve("server-$does"), does)

    @Test
    fun `the tool is called in the session's folder with the declared fields, and its text is placed`() =
        runBlocking {
            val server = stdio(Does.ANSWERS)
            val head = rig.head(listOf(capability()), folder = work, mcpServers = loading(server.entry()))

            val stream = rig.send(head, Turn("s1", opening("Add a flag")))

            assertTrue(stream.contains("ok from the provider"))
            assertEquals(listOf("Add a flag", "found in $work"), firstUserTexts(rig.provider.single()))
            val call = server.calls().single().getValue("params").jsonObject
            assertEquals("answer", call.getValue("name").jsonPrimitive.content)
            // the declared fields and nothing else: the capability, session and turn ride in `_meta`
            assertEquals(setOf("first_user_message", "model"), call.getValue("arguments").jsonObject.keys)
            assertEquals(
                "Add a flag",
                call.getValue("arguments").jsonObject.getValue("first_user_message").jsonPrimitive.content,
            )
            val meta = call.getValue("_meta").jsonObject
            assertEquals("brief", meta.getValue("capability").jsonPrimitive.content)
            assertEquals("s1", meta.getValue("session").jsonPrimitive.content)
            assertTrue(meta.getValue("turn").jsonPrimitive.content.isNotBlank())
            assertTrue(meta.containsKey("progressToken"), "the call asks for progress")
        }

    @Test
    fun `a server at a URL is spoken to there, with its entry's headers, and its session is ended after`() =
        runBlocking {
            val server = HttpMcp().also(https::add)
            val head = rig.head(listOf(capability()), folder = work, mcpServers = loading(server.entry()))

            rig.send(head, Turn("s1", opening("Add a flag")))

            assertEquals(listOf("Add a flag", FOUND), firstUserTexts(rig.provider.single()))
            assertTrue(server.posts.all { (headers, _) -> headers["authorization"] == "Bearer the-mcp-key" })
            val call = server.posts.last { (_, body) -> body["method"]?.jsonPrimitive?.content == "tools/call" }
            assertEquals("session-1", call.first["mcp-session-id"], "the call is made in the session the server gave")
            assertEquals("2025-06-18", call.first["mcp-protocol-version"])
            rig.awaitUntil("the server is told the session is over") { server.ended }
        }

    @Test
    fun `closed, each failure refuses the turn naming the capability, and the provider gets nothing`() = runBlocking {
        failures().forEach { f ->
            val what = f.what
            val head = rig.head(
                listOf(capability(CapabilityFailure.CLOSED, timeoutS = 1)),
                folder = f.folder,
                mcpServers = loading(f.entry),
            )

            val stream = rig.send(head, Turn(what, opening("Add a flag")))

            assertTrue(stream.contains("brief could not finish its work"), "$what: $stream")
            assertTrue(stream.contains("event: error"), "$what: refused as an error")
            assertFalse(stream.contains("ok from the provider"), what)
            assertEquals(emptyList<String>(), rig.provider, "$what: the provider got nothing")
            f.server?.let { assertEquals(1, it.calls().size, what) }
        }
    }

    @Test
    fun `open, each failure lets the turn go on as it is today`() = runBlocking {
        failures().forEach { f ->
            val what = f.what
            val head = rig.head(listOf(capability(timeoutS = 1)), folder = f.folder, mcpServers = loading(f.entry))

            val stream = rig.send(head, Turn(what, opening("Add a flag")))

            assertTrue(stream.contains("ok from the provider"), "$what: $stream")
            assertEquals(listOf("Add a flag"), firstUserTexts(rig.provider.last()), "$what: nothing was placed")
        }
    }

    @Test
    fun `a call that passes its time limit is cancelled at the server, which is stopped`() = runBlocking {
        val server = stdio(Does.HANGS)
        val head = rig.head(listOf(capability(timeoutS = 1)), folder = work, mcpServers = loading(server.entry()))

        rig.send(head, Turn("slow", opening("Add a flag")))

        rig.awaitUntil("the server hears it was cancelled") { server.cancels().isNotEmpty() }
        val asked = server.calls().single().getValue("id")
        assertEquals(asked, server.cancels().single().getValue("params").jsonObject.getValue("requestId"))
        val pid = requireNotNull(server.pid())
        rig.awaitUntil("the server is stopped") { ProcessHandle.of(pid).map { !it.isAlive }.orElse(true) }
    }

    /** One way a call fails, the server that made it (none when there is none to call), the entry the session loads,
     *  and the folder the session works in. */
    private data class Failing(val what: String, val server: StdioMcp?, val entry: JsonObject?, val folder: Path?)

    private fun failures(): List<Failing> {
        fun by(does: Does, what: String) = stdio(does).let { Failing(what, it, it.entry(), work) }
        return listOf(
            by(Does.ERROR_RESULT, "answers an error result"),
            by(Does.NO_TEXT, "answers with no text"),
            by(Does.RPC_ERROR, "answers a JSON-RPC error"),
            by(Does.HANGS, "passes its time limit"),
            Failing("names a server the session does not load", null, null, work),
            Failing("runs in a session whose folder is unknown", null, stdio(Does.ANSWERS).entry(), null),
        )
    }
}
