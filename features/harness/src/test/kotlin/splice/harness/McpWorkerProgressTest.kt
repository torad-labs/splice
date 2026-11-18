// NEW: what an MCP worker says while its tool works (docs/specs/harness.md, decision 13): the call asks for progress by
// a token, and each progress notification of that token is a message, over a process on stdio and over a URL alike,
// whether the URL sends it in the answer or on the session's own stream; one with no message says the last one again.
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
import java.util.concurrent.CountDownLatch

class McpWorkerProgressTest(@param:TempDir private val tmp: Path) {
    private fun worker(entry: JsonObject) =
        McpWorker(WorkerMcpServers { _, _ -> mapOf("notes" to entry) }, HttpClient.newHttpClient(), Dispatchers.IO)

    /** What [entry]'s tool ended on, and what the held turn was told: each message, and `(again)` for a line with none.
     *  [heard] is counted down as each line is heard. */
    private fun call(entry: JsonObject, heard: CountDownLatch? = null): Pair<WorkerEnd, List<String>> = runBlocking {
        val said = mutableListOf<String>()
        val progress = object : WorkerProgress {
            override fun said(message: String) {
                said += message
                heard?.countDown()
            }

            override fun again() {
                said += AGAIN
                heard?.countDown()
            }
        }
        val end = worker(entry).call(
            CapabilityWorker.Mcp("notes", "answer"),
            "astra",
            tmp,
            McpToolCall(JsonObject(emptyMap()), JsonObject(emptyMap())),
            progress,
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

    @Test
    fun `a server that answers in one object says the progress its session's stream carries, a line with none again`() {
        HttpMcp(onSession = true).use { server ->
            val (end, said) = call(server.entry(), server.heard)

            assertEquals(listOf("Reading the notes", AGAIN), said)
            assertEquals(WorkerEnd.Result(FOUND), end)
        }
    }
}

private const val AGAIN = "(again)"
