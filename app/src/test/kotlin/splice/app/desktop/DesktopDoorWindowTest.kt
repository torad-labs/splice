// splice.toml's `[desktop]` windows reach the counts a Claude Desktop Code-tab turn reports (issue #791), on a row the
// picker spells with the 1M hint: Desktop's Claude Code sends the model without the hint and with the 1M beta, the door
// routes it to the provider's `[1m]` row, and the counts it reports are scaled to the configured big window, the one
// this install's Desktop divides by, rather than to the client's own million.
package splice.app.desktop

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import splice.core.config.ConfigService
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
import splice.head.scripted.HeadlessProvider
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadStatusline
import splice.usage.UsageHeadWarn
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import splice.usage.statusline.StatuslineRoute
import java.nio.file.Files
import java.nio.file.Path

class DesktopDoorWindowTest {
    /** The scripted provider's one row, spelled with the 1M hint; it declares a 200,000 window. */
    private val row = "muse-spark[1m]"
    private val prefix = "claude-scripted-headless-chat--"

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
            val servedSuffix = Ask(row, emptyList(), model = "$prefix$row")
            val literal = asked(tmp.resolve("literal"), runtime, DesktopConfig(), TURN, servedSuffix)
            assertEquals(60L, reportedInput(literal))
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

    @Test
    fun `a legacy Desktop record cannot make a turn or count underreport its input`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatAnswer("answered") })
            val model = "claude-opus-5-5"
            val runtime = headless.chat(HeadShape(model = model)).runtime
            val request = Ask(model, headers = emptyList())
            val state = tmp.resolve("upgrade")
            val file = StatePaths(baseOverride = state.resolve("state")).desktopFile
            Files.createDirectories(file.parent)
            Files.writeString(file, """{"on":true,"window":80000}""")
            assertEquals(12L, reportedInput(asked(state, runtime, DesktopConfig(), TURN, request)))
            val stream = asked(state, runtime, DesktopConfig(), TURN, request.copy(stream = true))
            assertEquals(12L, reportedInput(streamUsage(stream)))
            val raw = countOf(asked(tmp.resolve("fresh"), runtime, DesktopConfig(), COUNT, request))
            assertEquals(raw, countOf(asked(state, runtime, DesktopConfig(), COUNT, request)))
        }
    }

    @Test
    fun `a gateway selector uses the launch header for turns streams and token estimates`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatAnswer("answered") })
            val runtime = headless.chat(HeadShape(model = "muse-spark")).runtime
            val bare = Ask("muse-spark", headers = emptyList(), model = "${prefix}muse-spark")
            val raw = countOf(asked(tmp, runtime, DesktopConfig(), COUNT, bare))
            val beta = bare.copy(headers = listOf("anthropic-beta" to "context-1m-2025-08-07"))
            assertEquals(raw * 5, countOf(asked(tmp, runtime, DesktopConfig(), COUNT, beta)))
            val launch = bare.copy(headers = listOf("x-splice-client-window" to "400000"))
            assertEquals(24L, reportedInput(asked(tmp, runtime, DesktopConfig(), TURN, launch)))
            val stream = asked(tmp, runtime, DesktopConfig(), TURN, launch.copy(stream = true))
            assertEquals(24L, reportedInput(streamUsage(stream)))
            assertEquals(raw * 2, countOf(asked(tmp, runtime, DesktopConfig(), COUNT, launch)))
        }
    }

    @Test
    fun `a beta-free app request on a preferred 1M row reports real counts`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatAnswer("answered") })
            val row = "claude-fable-5-2[1m]"
            val runtime = headless.chat(HeadShape(model = row), contextWindow = 1_000_000).runtime
            val bare = Ask(row, emptyList(), model = "claude-fable-5-2", window = 1_000_000)
            val app = bare.copy(headers = listOf("x-splice-desktop" to "app"))
            val discovery = Json.parseToJsonElement(asked(tmp, runtime, DesktopConfig(), MODELS, app)).jsonObject
            val published = discovery.getValue("data").jsonArray.single().jsonObject
            assertEquals("true", published.getValue("prefer_1m").jsonPrimitive.content)
            assertEquals(12L, reportedInput(asked(tmp, runtime, DesktopConfig(), TURN, app)))
            val raw = countOf(asked(tmp, runtime, DesktopConfig(), COUNT, bare))
            assertEquals(raw, countOf(asked(tmp, runtime, DesktopConfig(), COUNT, app)))
        }
    }

    @Test
    fun `statusline displays the real usage from the reply not the window posted with it`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatAnswer("answered") })
            val model = "muse-spark"
            val provider = headless.chat(HeadShape(model = model))
            val session = "code-session"
            val headers = listOf("x-claude-code-session-id" to session)
            val ask = Ask(model, headers, model = "$prefix$model")
            val raw = asked(tmp, provider.runtime, DesktopConfig(), TURN, ask)
            val launch = ask.copy(headers = headers + ("x-splice-client-window" to "100000"))
            val scaled = asked(tmp, provider.runtime, DesktopConfig(), TURN, launch)
            assertEquals(12L, reportedInput(raw))
            assertEquals(6L, reportedInput(scaled))
            val streamed = streamUsage(asked(tmp, provider.runtime, DesktopConfig(), TURN, launch.copy(stream = true)))
            assertEquals(6L, reportedInput(streamed))
            listOf(raw, scaled, streamed).forEach { reply ->
                val line = posted(tmp, provider, ask, session, reply)
                assertTrue("12/200k" in line, line)
            }
        }
    }

    private fun posted(tmp: Path, provider: HeadlessProvider, ask: Ask, session: String, reply: String): String {
        val served = Served(provider.runtime, ask)
        val head = UsageHead(
            key = served.key,
            label = served.key,
            usage = served.usage,
            warn = UsageHeadWarn(80, 0),
            statusline = UsageHeadStatusline(served.catalog, provider.clientWindows),
        )
        val route = StatuslineRoute(UsageHeadLookup { listOf(head) }, ConfigService(StatePaths(baseOverride = tmp)))
        val usage = Json.parseToJsonElement(reply).jsonObject.getValue("usage")
        var line = ""
        testApplication {
            routing { post("/statusline/{head}") { route.statusline(call) } }
            line = client.post("/statusline/${served.key}") {
                setBody(
                    """{"session_id":"$session","model":{"id":"${ask.model}"},"context_window":""" +
                        """{"context_window_size":100000,"current_usage":$usage}}""",
                )
            }.bodyAsText()
        }
        return line
    }

    private data class Ask(
        val row: String,
        val headers: List<Pair<String, String>>,
        val model: String = row,
        val stream: Boolean = false,
        val window: Long = 200_000,
    ) {
        val body: String
            get() = """{"model":"$model","max_tokens":64,"stream":$stream,""" +
                """"messages":[{"role":"user","content":"hello"}]}"""
    }

    private fun asked(
        state: Path,
        runtime: ProviderRuntime,
        desktop: DesktopConfig,
        path: String,
        ask: Ask = Ask(
            row,
            listOf("x-splice-desktop" to "app", "anthropic-beta" to "context-1m-2025-08-07"),
            model = "${prefix}muse-spark",
        ),
    ): String =
        UserHome.within(state.resolve("home")) {
            val log = LogSink {}
            val paths = StatePaths(baseOverride = state.resolve("state"))
            val key = DesktopKey(paths.desktopKeyFile, log)
            val records = DesktopRecords(paths.desktopFile, log).also { r -> r.update { it.copy(on = true) } }
            val providers = DesktopProviders(
                serving = ServingProviders { listOf(Served(runtime, ask)) },
                desktopOnly = ServingProviders { emptyList() },
                aliases = SelectorAliases(emptyList()),
                published = PublishedCatalog(CatalogBuild(), CatalogFile(paths.modelCatalogFile, log), Dispatchers.IO),
            )
            val door = DesktopDoor(listOf(DesktopCaller(key, records, log)), providers, desktop = desktop)
            var text = ""
            testApplication {
                providers.publish()
                routing {
                    get(MODELS) { door.models(call) }
                    post(TURN) { door.messages(call) }
                    post(COUNT) { door.countTokens(call) }
                }
                val bearer = "Bearer ${key.get()}"
                val response = if (path == MODELS) {
                    client.get(path) { header("Authorization", bearer) }
                } else {
                    client.post(path) {
                        contentType(ContentType.Application.Json)
                        header("Authorization", bearer)
                        header("anthropic-beta", "claude-code-20250219")
                        ask.headers.forEach { (name, value) -> header(name, value) }
                        setBody(ask.body)
                    }
                }
                text = response.bodyAsText()
                assertEquals(200, response.status.value, text)
            }
            text
        }

    private fun streamUsage(stream: String): String =
        stream.lineSequence().filter { it.startsWith("data: ") }
            .map { it.removePrefix("data: ") }
            .first { Json.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.content == "message_delta" }

    private fun reportedInput(answer: String): Long =
        Json.parseToJsonElement(answer).jsonObject.getValue("usage").jsonObject
            .getValue("input_tokens").jsonPrimitive.content.toLong()

    private fun countOf(answer: String): Long =
        Json.parseToJsonElement(answer).jsonObject.getValue("input_tokens").jsonPrimitive.content.toLong()

    /** The scripted provider as the door reads it: its `[1m]` row under its selector, its turns run by [runtime]. */
    private inner class Served(private val runtime: ProviderRuntime, ask: Ask) : ServedProvider {
        override val key: String = "scripted-headless-chat"
        override val catalog: ModelCatalog = ModelCatalog(
            discoveryPrefix = prefix,
            models = listOf(ModelEntry(ask.row, label = "Scripted", contextWindow = ask.window)),
            defaultContextWindow = ask.window,
        )
        override val tiers: Map<String, String> = emptyMap()
        override val pinnedModel: String = ask.row
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
private const val MODELS = "/door/v1/models"
