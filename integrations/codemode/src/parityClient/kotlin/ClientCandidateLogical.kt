import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import splice.codemode.NativeCodeModeRuntime
import splice.codemode.WorkerSpawn
import splice.codemode.nativehost.NativeCell
import splice.codemode.nativehost.NativeConnection
import splice.codemode.nativehost.NativeControlCell
import splice.codemode.nativehost.grpc.NativeGrpcWaits
import splice.codemode.nativehost.sessions.NativeLogicalSession
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeOptions
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicReference

// why: the foreground observation budget in ms; a started or deferred cell's first observation waits this long.
private const val OBSERVE_MS = 10_000L

// why: yield_time_ms of an execute or wait step when it names none; NativeCodeModeOptions' default, in ms.
private const val DEFAULT_YIELD_MS = 10_000L

// why: max_output_tokens of an execute step when it names none; NativeCodeModeOptions' default, in tokens.
private const val DEFAULT_OUTPUT_TOKENS = 10_000L

// why: ms the two queued execute requests get to reach the logical owner before the session is closed.
private const val QUEUE_SETTLE_MS = 80L

// why: ms the whole logical-owner scenario may run when the input names no case_timeout_ms.
private const val CASE_TIMEOUT_MS = 30_000L

private const val YIELD_TIME_KEY = "yield_time_ms"

/** Artifact wrapper. Session admission, generations, ownership, cancellation and shutdown are production objects. */
public object ClientCandidateLogical {
    private fun str(value: String) = JsonPrimitive(value)
    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*fields))
    private fun ok(value: JsonElement = JsonPrimitive(true)) = obj("ok" to value)
    private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.integer(name: String) = string(name)?.toBigIntegerOrNull()
    private fun JsonObject.flag(name: String) = string(name) == "true"
    private fun response(step: CodeModeStep, cell: String): JsonObject {
        val (kind, value) = when (step) {
            is CodeModeStep.Yielded -> "Yielded" to obj(
                "cell_id" to str(step.cellId),
                "content_items" to JsonArray(step.content),
            )
            is CodeModeStep.Completed -> completed(step, cell)
            is CodeModeStep.Calls, is CodeModeStep.WaitingForResults, is CodeModeStep.Notified ->
                error("This logical-owner scenario unexpectedly requested a callback")
        }
        val timing = when (step) {
            is CodeModeStep.Yielded -> step.timing
            is CodeModeStep.Completed -> step.timing
            is CodeModeStep.Calls, is CodeModeStep.WaitingForResults, is CodeModeStep.Notified -> null
        }
        return obj(
            kind to JsonObject(
                value + mapOf(
                    "code_mode_host_duration" to duration(timing?.hostDuration),
                    "result_buffered_for" to duration(timing?.resultBufferedFor),
                ),
            ),
        )
    }
    private fun completed(step: CodeModeStep.Completed, cell: String): Pair<String, JsonObject> {
        val fields = linkedMapOf<String, JsonElement>(
            "cell_id" to str(cell),
            "content_items" to JsonArray(step.content),
        )
        if (!step.terminated) fields["error_text"] = step.error?.let(::str) ?: JsonNull
        return (if (step.terminated) "Terminated" else "Result") to JsonObject(fields)
    }
    private fun duration(value: java.time.Duration?): JsonElement =
        value?.let { obj("secs" to JsonPrimitive(it.seconds), "nanos" to JsonPrimitive(it.nano)) } ?: JsonNull
    private class Harness(program: String, input: JsonObject) : AutoCloseable {
        private val process = AtomicReference<Process>()
        private val runtime =
            NativeCodeModeRuntime(
                maxYieldTimeMs = (input["limits"] as? JsonObject)?.integer("max_yield_time_ms")?.longValueExact(),
                spawn = WorkerSpawn { builder ->
                    System.getenv(
                        "SPLICE_GRPC_TRACE_PATH",
                    )?.let { builder.environment()["SPLICE_GRPC_TRACE_PATH"] = it }
                    builder.command(program, "--listen", "grpc+stdio://").start().also(process::set)
                },
            )
        private val owners = LinkedHashMap<String, NativeLogicalSession>()
        private val cells = LinkedHashMap<String, NativeCodeModeCell>()
        private val deferred = LinkedHashMap<String, NativeCodeModeCell>()
        private val events = mutableListOf<JsonElement>()
        private suspend fun open(session: String): JsonElement {
            runtime.openNativeSession(session)
            owners[session] = runtime.logicalSession(session)
            return JsonPrimitive(true)
        }
        private fun cell(input: JsonObject) = cells[input.string("cell") ?: "last"]
        private suspend fun execute(input: JsonObject, session: String): JsonElement {
            owners.getValue(session).current()
            val yield = input.integer(YIELD_TIME_KEY) ?: BigInteger.valueOf(DEFAULT_YIELD_MS)
            val tokens = input.integer("max_output_tokens") ?: BigInteger.valueOf(DEFAULT_OUTPUT_TOKENS)
            val options = NativeCodeModeOptions(
                yieldTimeMs = yield.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong(),
                maxOutputTokens = tokens.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong(),
                unsignedYieldTimeMs = yield,
                unsignedMaxOutputTokens = tokens,
                toolCallId = input.string("tool_call_id") ?: "witness-call",
            )
            val started = runtime.startNativeSession(
                session,
                input.string("source").orEmpty(),
                emptySet(),
                emptyMap(),
                options,
            )
            val alias = input.string("as") ?: "last"
            cells[alias] = started; cells["last"] = started
            events += obj(
                "type" to str("started"),
                "session" to str(session),
                "alias" to str(alias),
                "cell_id" to str(started.cellId),
            )
            return when {
                input.flag("defer_initial") -> { deferred[alias] = started; obj("started" to str(started.cellId)) }
                input.flag("drop_initial") -> { started.close(); obj("dropped_initial" to str(started.cellId)) }
                else -> response(started.observe(OBSERVE_MS), started.cellId)
            }
        }
        private suspend fun shutdownQueued(
            input: JsonObject,
            session: String,
        ): JsonElement = kotlinx.coroutines.coroutineScope {
            val queued = (0..1).map {
                async {
                    typed(
                        JsonObject(
                            input + mapOf(
                                "op" to str("execute"),
                                "source" to str("scripted queued request"),
                                YIELD_TIME_KEY to JsonPrimitive(1),
                            ),
                        ),
                    )
                }
            }
            delay(QUEUE_SETTLE_MS)
            runtime.closeSession(session)
            obj("shutdown" to ok(JsonNull), "queued" to JsonArray(queued.map { it.await() }))
        }
        private suspend fun step(input: JsonObject): JsonElement {
            val session = input.string("session") ?: "main"
            return when (input.string("op")) {
                "open" -> open(session)
                "execute" -> executeStep(input, session)
                "initial" -> initialStep(input)
                "wait" -> waitStep(input, session)
                "terminate" -> terminateStep(input, session)
                else -> hostStep(input, session)
            }
        }
        private suspend fun executeStep(input: JsonObject, session: String): JsonElement {
            val timeout = input.integer("cancel_after_ms")?.longValueExact()
            return if (timeout == null) {
                execute(input, session)
            } else {
                withTimeoutOrNull(timeout) { execute(input, session) }
                    ?: obj("witness_cancelled" to JsonPrimitive(true))
            }
        }
        private suspend fun initialStep(input: JsonObject): JsonElement {
            val initial = deferred.remove(input.string("cell") ?: "last") ?: error("no deferred initial")
            return response(initial.observe(OBSERVE_MS), initial.cellId)
        }
        private suspend fun waitStep(input: JsonObject, session: String): JsonElement {
            owners.getValue(session).current()
            val id = cell(input)?.cellId ?: input.string("cell").orEmpty()
            val control = runtime.controlNativeSession(session, id)
            return obj(
                "LiveCell" to response(
                    if (input.flag("retained_observe")) {
                        cell(input)!!.observe(input.integer(YIELD_TIME_KEY) ?: BigInteger.valueOf(DEFAULT_YIELD_MS))
                    } else {
                        control.observe(input.integer(YIELD_TIME_KEY) ?: BigInteger.valueOf(DEFAULT_YIELD_MS))
                    },
                    id,
                ),
            )
        }
        private suspend fun terminateStep(input: JsonObject, session: String): JsonElement {
            owners.getValue(session).current()
            val id = cell(input)?.cellId ?: input.string("cell").orEmpty()
            val control = runtime.controlNativeSession(session, id)
            return obj(
                "LiveCell" to response(
                    if (input.flag("owned")) cell(input)!!.terminate() else control.terminate(),
                    id,
                ),
            )
        }
        private suspend fun hostStep(input: JsonObject, session: String): JsonElement = when (input.string("op")) {
            "shutdown" -> {
                runtime.closeSession(session)
                JsonNull
            }
            "shutdown_queued" -> shutdownQueued(input, session)
            "crash_host" -> {
                process.get().destroyForcibly().waitFor()
                JsonPrimitive(true)
            }
            "pause_host" -> {
                val result = ProcessBuilder("kill", "-STOP", process.get().pid().toString()).start().waitFor()
                check(result == 0)
                JsonPrimitive(true)
            }
            "sleep" -> {
                delay(input.integer("ms")?.longValueExact() ?: 0)
                JsonPrimitive(true)
            }
            else -> error("unsupported logical-owner scenario operation")
        }
        private suspend fun typed(
            input: JsonObject,
        ): JsonObject = try {
            val value = step(
                input,
            ); if (value is JsonObject && value["witness_cancelled"] == JsonPrimitive(
                    true,
                )
            ) {
                obj("cancelled" to JsonPrimitive(true))
            } else {
                ok(value)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: java.io.IOException) {
            obj("error" to str(error.message.orEmpty()))
        } catch (error: IllegalStateException) { obj("error" to str(error.message.orEmpty())) }
        suspend fun run(input: JsonObject): JsonObject {
            val rows = mutableListOf<JsonElement>()
            if (!input.flag("manual_open"))rows += obj("op" to str("open"), "result" to typed(obj("op" to str("open"))))
            for ((index, step) in (input.getValue("steps") as JsonArray).withIndex()) {
                val value = step.jsonObject; val result = typed(
                    value,
                ); events += obj(
                    "type" to str("operation_returned"),
                    "step_index" to JsonPrimitive(index),
                    "op" to value.getValue("op"),
                ); rows += obj("op" to value.getValue("op"), "result" to result)
            }
            return obj("steps" to JsonArray(rows), "events" to JsonArray(events))
        }
        override fun close() { runtime.close() }
    }

    @JvmStatic public fun main(args: Array<String>) = runBlocking {
        val input = Json.parseToJsonElement(System.`in`.bufferedReader().readText()).jsonObject
        val value = Harness(
            args.single(),
            input,
        ).use { harness ->
            withTimeout(input.integer("case_timeout_ms")?.longValueExact() ?: CASE_TIMEOUT_MS) {
                harness.run(input)
            }
        }
        val origins = listOf(
            NativeCodeModeRuntime::class.java,
            NativeLogicalSession::class.java,
            NativeConnection::class.java,
            NativeCell::class.java,
            NativeControlCell::class.java,
            NativeGrpcWaits::class.java,
        ).associate { it.name to str(it.protectionDomain.codeSource.location.toURI().path) }
        println(
            obj(
                "id" to input.getValue("id"),
                "nonce" to input.getValue("nonce"),
                "result" to value,
                "class_origins" to JsonObject(origins),
                "classpath" to str(System.getProperty("java.class.path")),
            ),
        )
    }
}
