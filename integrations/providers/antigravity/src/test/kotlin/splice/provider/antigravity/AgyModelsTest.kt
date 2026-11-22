// What splice makes of asking agy, against a scripted agent that answers as agy does: the models it offers, a
// signed-out account, an agent that dies saying why, and no agent at all.
package splice.provider.antigravity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

internal class AgyModelsTest {
    @TempDir
    lateinit var tmp: Path

    private val session =
        """{"sessionId":"s1","configOptions":[""" +
            """{"id":"model","currentValue":"gemini-4-neon",""" +
            """"options":[{"value":"gemini-4-neon","name":"Gemini 4 Neon"}]},""" +
            """{"id":"thought_level","currentValue":"high","options":[{"value":"low","name":"Low"},""" +
            """{"value":"high","name":"High"}]}],""" +
            """"models":{"availableModels":[{"modelId":"gemini-4-neon-low"},{"modelId":"gemini-4-neon-high"}]}}"""

    @Test
    fun `the models agy offers come back as a catalog with the window agy reports`() {
        val read = read(
            """
            read l; echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}'
            read l; echo '{"jsonrpc":"2.0","id":2,"result":$session}'
            echo '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":{"sessionUpdate":"usage_update","used":0,"size":500000}}}'
            read l; echo '{"jsonrpc":"2.0","id":3,"result":{}}'
            """,
        )
        val catalog = (read as AgyRead.Offered).catalog
        assertEquals(listOf("gemini-4-neon"), catalog.models.map { it.id })
        assertEquals(listOf("low", "high"), catalog.models.single().listed?.listing?.efforts)
        assertEquals(500_000L, catalog.models.single().contextWindow)
    }

    @Test
    fun `an account agy is not signed in to reads as signed out`() {
        val read = read(
            """
            read l; echo '{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"Authentication required"}}'
            read l
            """,
        )
        assertEquals(AgyRead.SignedOut, read)
    }

    @Test
    fun `an agent that dies says so, with the last thing it wrote`() {
        val read = read("echo 'Update available'; echo 'cannot reach Google' >&2; exit 3")
        val reason = (read as AgyRead.Failed).reason
        assertTrue("agy ended before it answered" in reason, reason)
        assertTrue("agy said: cannot reach Google" in reason, reason)
        assertTrue("agy wrote, unreadable: Update available" in reason, reason)
    }

    @Test
    fun `no agy at all reads as absent`() {
        val read = runBlocking {
            AgyModels(LogSink {}, this, Dispatchers.IO, listOf(tmp.resolve("no-agy").toString())).read()
        }
        assertTrue("could not be started" in (read as AgyRead.Absent).reason)
    }

    private fun read(script: String): AgyRead {
        val agent = tmp.resolve("agent.sh")
        Files.writeString(agent, script.trimIndent())
        return runBlocking { AgyModels(LogSink {}, this, Dispatchers.IO, listOf("sh", agent.toString())).read() }
    }
}
