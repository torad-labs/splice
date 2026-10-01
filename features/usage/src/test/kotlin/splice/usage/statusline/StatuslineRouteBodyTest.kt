// NEW: LAYOUT-01 — the statusline route's body read, at the feature boundary. The two refusals splice
// decides (a declared length over the cap, a streamed body that passes it) are sealed outcomes, not
// exceptions (kt-no-exception-as-outcome); these arms pin the status each one answers with, and the
// streamed arm is the only one that reaches the per-chunk cap at all.
package splice.usage.statusline

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.CodexCompactionReserves
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Path

class StatuslineRouteBodyTest {
    @TempDir
    lateinit var tmp: Path

    private fun route(): StatuslineRoute {
        val head = UsageHead(
            key = "codex",
            label = "codex",
            usage = HeadUsageSource { UsageView(0, 0, null) },
            warnPct = 80,
            warnTokens5h = 0,
        )
        return StatuslineRoute(
            UsageHeadLookup { name -> if (name == "codex") listOf(head) else emptyList() },
            ConfigService(StatePaths(baseOverride = tmp.resolve("state"))),
        )
    }

    private fun ApplicationTestBuilder.serve(route: StatuslineRoute) {
        application {
            routing {
                post("/statusline/{head}") { route.statusline(call) }
            }
        }
    }

    @Test
    fun `a body under the cap renders the statusline`() = testApplication {
        serve(route())

        val response = client.post("/statusline/codex") {
            setBody("""{"model":{"display_name":"Codex 5.6 Sol"}}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Codex 5.6 Sol"))
    }

    @Test
    fun `a cached route renderer follows the current head catalog`() = testApplication {
        var catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry("gpt-6.1-sol", contextWindow = 272_000)),
            defaultContextWindow = 272_000,
            pinnedModel = "gpt-6.1-sol",
            compactionReserveDefaults = CodexCompactionReserves,
        )
        val route = StatuslineRoute(
            UsageHeadLookup {
                listOf(UsageHead("codex", "codex", HeadUsageSource { UsageView(0, 0, null) }, 80, 0, catalog = catalog))
            },
            ConfigService(StatePaths(baseOverride = tmp.resolve("state"))),
        )
        serve(route)
        val stdin = """{"model":{"id":"gpt-6.1-sol"},"context_window":{"context_window_size":872000,
            "current_usage":{"input_tokens":100000,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}}"""
        val first = client.post("/statusline/codex") { setBody(stdin) }.bodyAsText()
        assertTrue("/272k" in first, first)
        catalog = catalog.copy(models = listOf(ModelEntry("gpt-6.1-sol", contextWindow = 400_000)))
        val next = client.post("/statusline/codex") { setBody(stdin) }.bodyAsText()
        assertTrue("/400k" in next, next)
    }

    @Test
    fun `a declared length over the cap is refused before the body is read`() = testApplication {
        serve(route())

        val response = client.post("/statusline/codex") { setBody("x".repeat(70_000)) }

        assertEquals(413, response.status.value)
    }

    @Test
    fun `a streamed body with no declared length is refused once it passes the cap`() = testApplication {
        serve(route())

        // Nine 8 KiB chunks with no Content-Length: the declared-length check cannot see it, so only
        // the per-chunk cap stands between this body and the renderer.
        val response = client.post("/statusline/codex") {
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        repeat(9) { channel.writeFully(ByteArray(8 * 1024) { 'x'.code.toByte() }) }
                    }
                },
            )
        }

        assertEquals(413, response.status.value)
        assertTrue(response.bodyAsText().contains("exceeds"))
    }
}
