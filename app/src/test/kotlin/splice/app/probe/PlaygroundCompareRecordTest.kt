// A Compare models send leaves a Requests row like any request: its tokens, its Compare flag, no session, and a budget
// refusal that sends nothing.
package splice.app.probe

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.Wired
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.budget.BudgetBlock
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.core.util.AsyncFileIo
import splice.diagnostics.playground.PlaygroundBlocked
import splice.diagnostics.playground.PlaygroundResult
import splice.head.usage.CompareAnswer
import splice.head.usage.CompareSends
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class PlaygroundCompareRecordTest {
    @TempDir
    lateinit var root: Path

    private fun fixture(scope: CoroutineScope) = PlaygroundHeadFixture(root).build(scope)

    private val reportedUsage =
        """"usage":{"input_tokens":100,"output_tokens":7,"input_tokens_details":{"cached_tokens":40}},"""

    private fun completed(usage: String = reportedUsage) =
        """{"type":"response.completed","response":{"id":"r","status":"completed",$usage"output":[]}}"""

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }

    private fun sseClient(body: String) = HttpClient(
        MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream")) },
    )

    private fun rowsOf(head: splice.app.control.ManagedHead): List<splice.usage.perf.PerfRow> {
        AsyncFileIo.drain()
        return requireNotNull(head.sources.perfRows).window(0).rows
    }

    @Test
    fun `a Compare send is recorded with the tokens its dialect's stream reported, flagged Compare, with no session`() =
        runTest {
            val fixture = fixture(backgroundScope)
            sseClient(sse(completed())).use { client ->
                val sent = UpstreamPlaygroundProbe(fixture.second, client)
                    .run(playgroundHead("claudex"), "synthetic prompt", null)
                assertTrue(sent is PlaygroundResult, sent.toString())
            }
            val row = rowsOf(fixture.first).single()
            assertEquals(1L, row.fields[PerfKeys.COMPARE])
            assertEquals(OutcomeTag.OK.wire, row.outcome)
            assertEquals(7L, row.fields[PerfKeys.OUT_TOKENS])
            assertEquals(40L, row.fields[PerfKeys.CACHED_TOKENS])
            assertNotNull(row.fields[PerfKeys.IN_TOKENS])
            assertNull(row.facts.session)
            fixture.first.head.stop()
        }

    @Test
    fun `a Compare stream that reported no usage leaves a row with no token counts, never zeros`() = runTest {
        val fixture = fixture(backgroundScope)
        sseClient(sse(completed(usage = ""))).use { client ->
            UpstreamPlaygroundProbe(fixture.second, client).run(playgroundHead("claudex"), "synthetic prompt", null)
        }
        val row = rowsOf(fixture.first).single()
        assertEquals(1L, row.fields[PerfKeys.COMPARE])
        assertNull(row.fields[PerfKeys.IN_TOKENS])
        assertNull(row.fields[PerfKeys.OUT_TOKENS])
        fixture.first.head.stop()
    }

    @Test
    fun `a blocked budget refuses a Compare send before anything is posted`() = runTest {
        val fixture = fixture(backgroundScope)
        val registry = fixture.second
        val block = BudgetBlock("head spent its budget", "spent_usd=2.00 limit_usd=2.00")
        val refusing = object : CompareSends {
            override fun admit(model: String, account: String?) = block

            override fun answered(answer: CompareAnswer) = error("a refused send is never answered")
        }
        val target = requireNotNull(registry.target("claudex"))
        val auth = object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials = Credentials.Bearer("synthetic")

            override suspend fun refresh(): Credentials = credentials()

            override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
        }
        val wired = Wired(target.provider, auth)
        registry.bind("claudex", wired, null, requireNotNull(target.observer), refusing)
        HttpClient(MockEngine { error("a refused send never reaches the upstream") }).use { client ->
            val probe = UpstreamPlaygroundProbe(registry, client)
            val outcome = probe.run(playgroundHead("claudex"), "synthetic prompt", null)
            assertEquals(PlaygroundBlocked("claudex", block.message), outcome)
        }
        fixture.first.head.stop()
    }
}
