// NEW: a process killed after a statement is dispatched but before its first effect never reruns the source.
package splice.provider.codex

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.index.WireBlockIndex
import splice.core.turn.GatewayCustomCall
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.stream.CodeModeRoundInterceptor
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep
import splice.upstream.sse.WireSink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

@Timeout(30)
class CodeModeDispatchCrashTest {
    @TempDir
    lateinit var dir: Path

    private val location get() = CodeModeStateLocation(dir.resolve("state"), dir.resolve("legacy.json"))

    @Test
    fun `an abrupt process kill after dispatch but before the first effect never reruns source`() = runBlocking {
        val effect = dir.resolve("synthetic-effect.txt")
        val errors = dir.resolve("synthetic-child-errors.txt")
        val child = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-Xmx64m",
            "-cp",
            checkNotNull(System.getProperty("codex.testClasspath")),
            DispatchCrashProbe::class.java.name,
            dir.toString(),
            effect.toString(),
        ).redirectError(errors.toFile()).start()
        val dispatched: String
        try {
            dispatched = checkNotNull(child.inputStream.bufferedReader().readLine()) { Files.readString(errors) }
            assertTrue(dispatched.startsWith("DISPATCHED:"))
        } finally {
            child.destroyForcibly()
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "the owned synthetic child was not reaped")
        }
        assertTrue(Files.notExists(effect), "the first dispatched statement has not performed its effect")
        var starts = 0
        val runtime = object : CodeModeRuntime {
            override suspend fun start(
                source: String,
                tools: Set<String>,
                descriptions: Map<String, String>,
            ): CodeModeCell {
                starts++
                Files.writeString(effect, "rerun effect", CREATE, APPEND)
                return object : CodeModeCell {
                    override suspend fun advance(results: List<CodeModeResult>) = CodeModeStep.Completed("done")
                    override fun close() = Unit
                }
            }
            override fun close() = Unit
        }
        val manager = CodexCodeModeBridge(CodeModeBridgeConfig({ runtime }, location))
        try {
            manager.interceptor(crashTurn(), crashOuter(), disableParallel = false)
                .intercept(CRASH_BODY, QuietSink()) { RoundResult.Outcome(crashOutcome()) }
            assertEquals(0, starts, "dispatch without a client callback must still be durably no-rerun")
            assertTrue(Files.notExists(effect))
            assertEquals("DISPATCHED:2", dispatched, "both file and directory force must precede dispatch")
        } finally {
            manager.onHeadStop()
        }
    }

    /** Uses the production interceptor, driver and machine with the existing registry writer seam. */
    private class MeasuredBridge(config: CodeModeBridgeConfig, writer: CodeModeStateWrite) {
        private val json = Json { encodeDefaults = true }
        private val wire = CodexCodeModeWire(json, config.log)
        private val run = CodeModeRuntimeRun(config.runtimes)
        private val registry = CodexCodeModeRegistry(config, json, 1.hours, writer)
        private val validation = CodexCodeModeValidation(config)
        private val machine = CodexCodeModeMachine(config, registry, validation)
        private val driver = CodexCodeModeDriver(config, run, registry, wire, validation, machine)
        private val resume = CodexCodeModeResume(registry, wire, validation, machine, driver)
        private val controller = CodexCodeModeTurn(registry, wire, driver, resume, machine, validation, config.log)

        fun interceptor(turn: CodexCodeModeBridge.Turn) =
            CodeModeRoundInterceptor(turn, null, false, wire, controller, driver.streams)

        fun stop() {
            driver.streams.stop()
            registry.onHeadStop()
            run.end()
        }
    }

    /** The parent kills this daemon-shaped process while its first runtime dispatch waits before an effect. */
    object DispatchCrashProbe {
        @JvmStatic
        fun main(args: Array<String>) = runBlocking<Unit> {
            val root = Path.of(args[0])
            val effect = Path.of(args[1])
            var forces = 0
            val runtime = object : CodeModeRuntime {
                override suspend fun start(
                    source: String,
                    tools: Set<String>,
                    descriptions: Map<String, String>,
                ): CodeModeCell {
                    println("DISPATCHED:$forces")
                    System.out.flush()
                    System.`in`.read()
                    Files.writeString(effect, "first effect", CREATE, APPEND)
                    return object : CodeModeCell {
                        override suspend fun advance(results: List<CodeModeResult>) = CodeModeStep.Completed("done")
                        override fun close() = Unit
                    }
                }
                override fun close() = Unit
            }
            val config = CodeModeBridgeConfig(
                { runtime },
                CodeModeStateLocation(root.resolve("state"), root.resolve("legacy.json")),
            )
            val manager = MeasuredBridge(
                config,
                CodeModeStateWrite { path, text ->
                    CodeModeStateJournal.write(path, text) { _, channel ->
                        channel.force(true)
                        forces++
                    }
                },
            )
            manager.interceptor(crashTurn()).intercept(CRASH_BODY, QuietSink()) { RoundResult.Outcome(crashOutcome()) }
        }
    }

    private companion object {
        fun crashTurn() = CodexCodeModeBridge.Turn("crash-session", "crash-conversation", "synthetic", setOf("Read"))
        const val CRASH_BODY = """{"input":[{"role":"developer","content":"synthetic crash point"}]}"""
        fun crashOuter() = GatewayCustomCall(
            "crash-outer",
            CODE_MODE_TOOL_NAME,
            "text(await tools.Read({}));",
            buildJsonObject {
                put("type", "custom_tool_call")
                put("call_id", "crash-outer")
                put("name", CODE_MODE_TOOL_NAME)
                put("input", "text(await tools.Read({}));")
            },
        )
        fun crashOutcome() = TurnOutcome.Success(
            false,
            false,
            Usage(),
            handoffs = RoundHandoffs(customCalls = listOf(crashOuter())),
        )
    }

    private class QuietSink : WireSink {
        override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(0)
        override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
        override suspend fun signatureDelta(index: WireBlockIndex, signature: String) = Unit
        override suspend fun openText(): WireBlockIndex = WireBlockIndex(0)
        override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
        override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(1)
        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
        override suspend fun closeBlock(index: WireBlockIndex) = Unit
        override suspend fun closeAll() = Unit
        override suspend fun addTextBlock(text: String) = Unit
        override suspend fun addRedactedThinking(data: String) = Unit
    }
}
