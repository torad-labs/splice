// NEW: what an MCP worker says while its tool works (docs/specs/harness.md, decision 13): the call asks for progress by
// a token, and each progress notification of that token is a message, over a process on stdio and over a URL alike.
package splice.harness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.capability.CapabilityWorker
import java.net.http.HttpClient
import java.nio.file.Path

class McpWorkerProgressTest(@param:TempDir private val tmp: Path) {
    private fun worker(entry: JsonObject) =
        McpWorker(WorkerMcpServers { _, _ -> mapOf("notes" to entry) }, HttpClient.newHttpClient(), Dispatchers.IO)

    private fun call(entry: JsonObject): Pair<WorkerEnd, List<String>> = runBlocking {
        val said = mutableListOf<String>()
        val end = worker(entry).call(
            CapabilityWorker.Mcp("notes", "answer"),
            "astra",
            tmp,
            McpToolCall(JsonObject(emptyMap()), JsonObject(emptyMap())),
            WorkerProgress { said += it },
        )
        end to said
    }

    @Test
    fun `a process on stdio says each message it sends and ends with its text`() {
        val (end, said) = call(StdioMcp(tmp.resolve("stdio"), Does.ANSWERS).entry())

        assertEquals(listOf("Reading the notes"), said)
        assertEquals(WorkerEnd.Result("found in ${tmp.toRealPath()}"), end)
    }

    @Test
    fun `a server at a URL says each message its stream of events carries and ends with its text`() {
        HttpMcp().use { server ->
            val (end, said) = call(server.entry())

            assertEquals(listOf("Reading the notes"), said)
            assertEquals(WorkerEnd.Result(FOUND), end)
        }
    }
}
