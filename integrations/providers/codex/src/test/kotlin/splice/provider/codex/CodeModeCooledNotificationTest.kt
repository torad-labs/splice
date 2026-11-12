package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.GatewayCustomCall
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.JsonScalars
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeNotification
import splice.upstream.codemode.NativeCodeModeNotificationDelivery
import splice.upstream.codemode.NativeCodeModeOptions
import splice.upstream.sse.WireSink

class CodeModeCooledNotificationTest : CodeModeBridgeTestSupport() {
    @ParameterizedTest
    @ValueSource(strings = ["gpt-6-astra", "gpt-6-sol"])
    fun `a yielded live cell notifies a later active turn after its record leaves memory`(model: String) = runTest {
        val host = NotifyingHost()
        val bridge = bridge(host, retention = CodeModeRetention(bytes = 1))
        try {
            val replies = ArrayDeque(listOf(outcome(outer("live", "await longBuild()")), completedOutcome()))
            bridge.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
                RoundResult.Outcome(replies.removeFirst())
            }.turn()
            bridge.registry.cache.trim()
            assertTrue(bridge.registry.records.isEmpty(), "the completed exec must actually leave the cache")

            val receivedText = mutableListOf<String>()
            val received = object : WireSink by RecordingSink() {
                override suspend fun addTextBlock(text: String) { receivedText += text }
            }
            bridge.interceptor(turn(model = model), disableParallel = false).intercept(request(1), received) {
                assertEquals(NativeCodeModeNotificationDelivery.Delivered, host.notify("build is ready"))
                RoundResult.Outcome(completedOutcome())
            }.turn()
            assertEquals(listOf("build is ready"), receivedText, "the active turn must receive the live notification")
            var sent = JsonArray(emptyList())
            // A different model has no original exec in its history; carry the text delivered to its client.
            val next = request(2, if (model == "gpt-6-astra") null else receivedText.single())
            bridge.interceptor(turn(model = model), disableParallel = false).intercept(next, RecordingSink()) { body ->
                sent = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
                RoundResult.Outcome(completedOutcome())
            }.turn()
            val notices = sent.filterIsInstance<JsonObject>().filter { "build is ready" in it.toString() }
            assertEquals(1, notices.size, "accepted notify text was absent or duplicated in the next model request")
            if (model == "gpt-6-astra") {
                assertEquals("custom_tool_call_output", JsonScalars.str(notices.single(), "type"))
                assertEquals("live", JsonScalars.str(notices.single(), "call_id"))
            }
        } finally {
            bridge.onHeadStop()
        }
    }

    private fun request(messages: Int, received: String? = null): String {
        val root = Json.parseToJsonElement(BASE_REQUEST).jsonObject
        val added = (1..messages).flatMap { n ->
            buildList {
                if (n == 2 && received != null) {
                    add(buildJsonObject { put("role", "assistant"); put("content", received) })
                }
                add(buildJsonObject { put("role", "user"); put("content", "next $n") })
            }
        }
        return JsonObject(root + ("input" to JsonArray(root.getValue("input").jsonArray + added))).toString()
    }

    private fun outcome(call: GatewayCustomCall) = TurnOutcome.Success(
        false,
        false,
        Usage(),
        handoffs = RoundHandoffs(customCalls = listOf(call)),
    )

    private class NotifyingHost : NativeCodeModeLifecycle {
        private lateinit var options: NativeCodeModeOptions
        private lateinit var session: String

        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell = error("Native lifecycle must be used")

        override suspend fun startNativeSession(
            sessionKey: String,
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
            options: NativeCodeModeOptions,
        ): NativeCodeModeCell {
            session = sessionKey
            this.options = options
            return YieldedCell()
        }

        suspend fun notify(text: String): NativeCodeModeNotificationDelivery =
            checkNotNull(options.notifications).deliver(
                NativeCodeModeNotification(session, "live-cell", checkNotNull(options.toolCallId), text),
            )

        override fun close() = Unit
    }

    private class YieldedCell : NativeCodeModeCell {
        override val cellId = "live-cell"
        override suspend fun submitResults(results: List<CodeModeResult>) = Unit
        override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = observe(0)
        override suspend fun observe(yieldTimeMs: Long): CodeModeStep = CodeModeStep.Yielded(cellId, "build started")
        override suspend fun terminate(): CodeModeStep = CodeModeStep.Completed("", terminated = true)
        override fun close() = Unit
    }
}
