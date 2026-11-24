// splice.toml's `[desktop]` windows reach the counts a Claude Desktop Code-tab turn reports (issue #791), on a row the
// picker spells with the 1M hint: Desktop's Claude Code sends the model without the hint and with the 1M beta, the door
// routes it to the provider's `[1m]` row, and the counts it reports are scaled to the configured big window, the one
// this install's Desktop divides by, rather than to the client's own million.
package splice.app.desktop

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.desktop.catalog.CatalogBuild
import splice.app.desktop.catalog.CatalogFile
import splice.app.desktop.catalog.DesktopProviders
import splice.app.desktop.catalog.PublishedCatalog
import splice.app.desktop.catalog.SelectorAliases
import splice.app.desktop.record.DesktopKey
import splice.app.desktop.record.DesktopRecords
import splice.app.provider.ProviderLogin
import splice.app.provider.ServedProvider
import splice.app.provider.ServingProviders
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.desktop.DesktopConfig
import splice.core.usage.QuotaFull
import splice.core.util.LogSink
import splice.head.GatewayTurn
import splice.head.ProviderRuntime
import splice.head.scripted.HeadShape
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Path

class DesktopDoorWindowTest {
    /** The scripted provider's one row, spelled with the 1M hint; it declares a 200,000 window. */
    private val row = "muse-spark[1m]"
    private val prefix = "claude-scripted-headless-chat--"

    /** What Claude Desktop's Code tab asks for that row: the selector without the hint, which its Claude Code strips,
     *  and not streamed, so the counts come back in one body. */
    private val codeTabAsk =
        """{"model":"${prefix}muse-spark","max_tokens":64,"stream":false,""" +
            """"messages":[{"role":"user","content":"hello"}]}"""

    @Test
    fun `a 1M row's Code-tab counts scale to the configured big window, not the client's million`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatAnswer("answered") })
            val runtime = headless.chat(HeadShape(model = row)).runtime
            // The provider reports 12 input tokens on a row that declares 200,000. Desktop's shipped million scales
            // them by 1,000,000/200,000, which is today's count with no `[desktop]` table.
            assertEquals(60L, reportedInput(asked(tmp.resolve("shipped"), runtime, DesktopConfig(), TURN)))
            // With `code_window_1m = 640000`, the window this install's Desktop gives a 1M pick, they scale by
            // 640,000/200,000: the client compacts at the row's own window under the window it really holds.
            val configured = DesktopConfig(codeWindow1m = 640_000)
            assertEquals(38L, reportedInput(asked(tmp.resolve("configured"), runtime, configured, TURN)))
            // A count of the same request is scaled the same way: the shipped answer is five times the raw
            // estimate, the configured one 3.2 times it.
            val shipped = countOf(asked(tmp.resolve("shipped-count"), runtime, DesktopConfig(), COUNT))
            val counted = countOf(asked(tmp.resolve("configured-count"), runtime, configured, COUNT))
            assertEquals((shipped / 5 * 3.2).toLong(), counted)
        }
    }

    /** The answer [codeTabAsk] gets at [path] of a door whose `[desktop]` is [desktop], as Claude Desktop's app mode
     *  asks it: its key, its app-mode header and the 1M beta its Claude Code adds for a model picked as its 1M
     *  variant. */
    private fun asked(state: Path, runtime: ProviderRuntime, desktop: DesktopConfig, path: String): String =
        UserHome.within(state.resolve("home")) {
            val log = LogSink {}
            val paths = StatePaths(baseOverride = state.resolve("state"))
            val key = DesktopKey(paths.desktopKeyFile, log)
            val records = DesktopRecords(paths.desktopFile, log).also { r -> r.update { it.copy(on = true) } }
            val providers = DesktopProviders(
                serving = ServingProviders { listOf(Served(runtime)) },
                desktopOnly = ServingProviders { emptyList() },
                aliases = SelectorAliases(emptyList()),
                published = PublishedCatalog(CatalogBuild(), CatalogFile(paths.modelCatalogFile, log), Dispatchers.IO),
            )
            val door = DesktopDoor(listOf(DesktopCaller(key, records, log)), providers, desktop = desktop)
            var text = ""
            testApplication {
                routing {
                    post(TURN) { door.messages(call) }
                    post(COUNT) { door.countTokens(call) }
                }
                val response = client.post(path) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer ${key.get()}")
                    header("x-splice-desktop", "app")
                    header("anthropic-beta", "claude-code-20250219")
                    header("anthropic-beta", "context-1m-2025-08-07")
                    setBody(codeTabAsk)
                }
                text = response.bodyAsText()
                assertEquals(200, response.status.value, text)
            }
            text
        }

    private fun reportedInput(answer: String): Long =
        Json.parseToJsonElement(answer).jsonObject.getValue("usage").jsonObject
            .getValue("input_tokens").jsonPrimitive.content.toLong()

    private fun countOf(answer: String): Long =
        Json.parseToJsonElement(answer).jsonObject.getValue("input_tokens").jsonPrimitive.content.toLong()

    /** The scripted provider as the door reads it: its `[1m]` row under its selector, its turns run by [runtime]. */
    private inner class Served(private val runtime: ProviderRuntime) : ServedProvider {
        override val key: String = "scripted-headless-chat"
        override val catalog: ModelCatalog = ModelCatalog(
            discoveryPrefix = prefix,
            models = listOf(ModelEntry(row, label = "Scripted", contextWindow = 200_000L)),
            defaultContextWindow = 200_000L,
        )
        override val tiers: Map<String, String> = emptyMap()
        override val pinnedModel: String = row
        override val dialect: String = "openai-chat"
        override val usage: HeadUsageSource = HeadUsageSource { UsageView(0L, 0, null) }
        override val usesLogin: Boolean = false
        override val forwardsClientAuth: Boolean = false
        override val maxRequestBytes: Int = 1_000_000
        override val requestReadTimeoutMs: Long = 10_000L
        override fun quotaFull(): QuotaFull? = null
        override suspend fun login(): ProviderLogin = ProviderLogin.Ready
        override suspend fun serve(call: ApplicationCall, turn: GatewayTurn) = runtime.serve(call, turn)
        override suspend fun countTokens(call: ApplicationCall, turn: GatewayTurn) = runtime.countTokens(call, turn)
    }
}

private const val TURN = "/door/v1/messages"
private const val COUNT = "/door/v1/messages/count_tokens"
