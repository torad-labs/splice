package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.provider.codex.catalog.CodeModePresentation
import splice.provider.codex.diagnostics.CodeModeGatewayBridgeSink
import splice.provider.codex.state.lease.CodeModeCellLease
import splice.provider.codex.state.lease.CodeModeSessionAlive
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.availability.CodeModeAvailability
import splice.upstream.codemode.availability.CodeModeToolMode
import java.io.IOException
import kotlin.time.Duration.Companion.hours

class CodeModeAvailabilityTest : CodeModeBridgeTestSupport() {
    private val availabilityClock = MutableClock(1_000)

    @Test
    fun `preferred unavailable mode preserves direct declarations and only mode keeps exec`() {
        val manager = unavailable()
        try {
            val original = built("gpt-6-astra", lite = true)
            val preferred = CodexCodeModeTurnBuilder(manager, media(), listOf("gpt-6-astra"))
                .prepare(toolBody(), "session", original)
            assertEquals(original.requestBody, preferred.requestBody)
            assertFalse(preferred.meta.tools.codeMode)
            val required = CodexCodeModeTurnBuilder(manager, media(), codeModeOnly = backendCodeModeOnly)
                .prepare(toolBody(), "session", original)
            assertTrue(required.meta.tools.codeMode)
            val tools = required.requestBody.getValue("input").jsonArray.first().jsonObject.getValue("tools").jsonArray
            val namespace = tools.single().jsonObject
            assertEquals("namespace", JsonScalars.str(namespace, "type"))
            assertEquals("functions", JsonScalars.str(namespace, "name"))
            assertEquals(
                listOf("exec", "wait"),
                namespace.getValue("tools").jsonArray.map { JsonScalars.str(it.jsonObject, "name") },
            )
        } finally {
            manager.onProviderStop()
        }
    }

    @Test
    fun `unavailable executions return exact source refusals without synthetic client content`() = runTest {
        for (mode in CodeModeToolMode.entries) {
            val manager = unavailable()
            val posted = mutableListOf<JsonArray>()
            val sink = CodeModeGatewayBridgeSink()
            val owner = turn().copy(
                conversationKey = "mode-${mode.name}",
                catalog = turn().catalog.copy(policy = policy(mode)),
            )
            try {
                val result = manager.interceptor(owner, disableParallel = false).intercept(BASE_REQUEST, sink) { body ->
                    val input = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
                    posted += input
                    val calls = if (input.any {
                            (it as? JsonObject)?.get(
                                "type",
                            )?.jsonPrimitive?.content == "custom_tool_call_output"
                        }
                    ) {
                        emptyList()
                    } else {
                        listOf(outer("unavailable-${mode.name}", "throw Error('must not execute')"))
                    }
                    RoundResult.Outcome(
                        TurnOutcome.Success(
                            false,
                            false,
                            noRequestUsage,
                            handoffs = RoundHandoffs(customCalls = calls),
                        ),
                    )
                }.turn()
                assertTrue(result is TurnOutcome.Success, result.toString())
                val output = posted.last().mapNotNull { it as? JsonObject }
                    .single { JsonScalars.str(it, "type") == "custom_tool_call_output" }
                val expected = if (mode == CodeModeToolMode.CODE_MODE_ONLY) {
                    "code-mode host is disabled"
                } else {
                    "unsupported custom tool call: exec"
                }
                assertEquals(expected, JsonScalars.str(output, "output"))
                assertEquals("", sink.text.toString())
                assertTrue(sink.callbackIds.isEmpty())
            } finally {
                manager.onProviderStop()
            }
        }
    }

    @Test
    fun `warning is separate head diagnostics once per live exact conversation and resets on stop`() = runTest {
        val manager = unavailable()
        val owner = turn().copy(catalog = turn().catalog.copy(policy = policy(CodeModeToolMode.CODE_MODE)))
        try {
            visit(manager, owner)
            visit(manager, owner.copy(model = "another-model"))
            assertEquals(listOf(preferredWarning), notices())
            visit(manager, owner.copy(conversationKey = "separate-agent-conversation"))
            visit(manager, owner.copy(sessionId = "separate-client-session"))
            assertEquals(listOf(preferredWarning, preferredWarning, preferredWarning), notices())
            manager.onHeadStop()
            visit(manager, owner)
            assertEquals(List(4) { preferredWarning }, notices())
        } finally {
            manager.onProviderStop()
        }
    }

    @Test
    fun `idle record retention history replacement and interruption never reset a live service warning`() = runTest {
        val manager = unavailable()
        val owner = turn().copy(catalog = turn().catalog.copy(policy = policy(CodeModeToolMode.CODE_MODE)))
        try {
            visit(manager, owner)
            availabilityClock.now += 48.hours.inWholeMilliseconds
            manager.registry.timed.sweep()
            assertEquals(
                CodeModeInterruptOutcome.Interrupted(emptyList()),
                manager.interruptActiveCells(owner.sessionId, owner.conversationKey),
            )
            visit(manager, owner, """{"input":[{"role":"user","content":"a compacted continuation"}]}""")
            assertEquals(listOf(preferredWarning), notices())
            deadSessions += owner.sessionId
            manager.registry.timed.sweep()
            assertEquals(
                CodeModeInterruptOutcome.Unavailable,
                manager.interruptActiveCells(owner.sessionId, owner.conversationKey),
            )
            deadSessions -= owner.sessionId
            visit(manager, owner)
            assertEquals(listOf(preferredWarning, preferredWarning), notices())
        } finally {
            manager.onProviderStop()
        }
    }

    @Test
    fun `failure opening an available provider remains startup failure without availability warning`() = runTest {
        val manager = manager(CodeModeAvailability.Available)
        try {
            val result = manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
                RoundResult.Outcome(
                    TurnOutcome.Success(
                        false,
                        false,
                        noRequestUsage,
                        handoffs = RoundHandoffs(customCalls = listOf(outer("open-failure", "1"))),
                    ),
                )
            }.turn()
            assertTrue(result is TurnOutcome.Failure, result.toString())
            assertEquals(emptyList<String>(), notices())
        } finally {
            manager.onProviderStop()
        }
    }

    private fun unavailable(): CodexCodeModeBridge = manager(
        CodeModeAvailability.Unavailable("code-mode host is disabled"),
    )

    private fun manager(availability: CodeModeAvailability): CodexCodeModeBridge = CodexCodeModeBridge(
        CodeModeBridgeConfig(
            object : CodeModeRuntimes {
                override fun availability(): CodeModeAvailability = availability
                override fun open(): CodeModeRuntime = throw IOException("Actual opening failed")
            },
            stateLocation(),
            clock = availabilityClock,
            cellLease = CodeModeCellLease(
                sessionAlive = CodeModeSessionAlive { id -> id !in deadSessions },
            ),
            log = LogSink { logLines += it },
        ),
    )

    private suspend fun visit(
        manager: CodexCodeModeBridge,
        owner: CodexCodeModeBridge.Turn,
        request: String = BASE_REQUEST,
    ) {
        val sink = CodeModeGatewayBridgeSink()
        val result = manager.interceptor(owner, disableParallel = false).intercept(request, sink) {
            RoundResult.Outcome(TurnOutcome.Success(false, false, noRequestUsage))
        }.turn()
        assertTrue(result is TurnOutcome.Success)
        assertEquals("", sink.text.toString())
    }

    private fun policy(mode: CodeModeToolMode): CodeModeToolPolicy =
        CodeModeToolPolicy(presentation = CodeModePresentation(toolMode = mode))

    private fun notices(): List<String> = logLines.filter { it.contains("Code Mode is unavailable because") }

    private val preferredWarning = "[code-mode] Code Mode is unavailable because code-mode host is disabled. " +
        "Falling back to direct tools; enable `features.code_mode_host` and install `codex-code-mode-host`."
}
