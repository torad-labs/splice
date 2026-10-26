import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import splice.codemode.nativehost.NativeConnection
import splice.codemode.nativehost.NativeConnectionClosed
import splice.codemode.nativehost.NativeFrames
import splice.codemode.nativehost.NativeObservations
import splice.codemode.nativehost.NativeOperation
import splice.codemode.nativehost.NativeRequests
import splice.codemode.nativehost.grpc.NativeGrpcChannel
import splice.codemode.nativehost.grpc.NativeGrpcDelegates
import splice.codemode.nativehost.grpc.NativeGrpcExecutions
import splice.codemode.nativehost.grpc.NativeGrpcRouter
import splice.codemode.nativehost.grpc.NativeGrpcSessions
import splice.codemode.nativehost.grpc.NativeGrpcWaits
import splice.codemode.nativehost.grpc.NativeGrpcWire
import splice.core.util.Cancellables
import splice.upstream.codemode.CodeModeResult
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

// why: ms a started cell's first observation waits for its response (the yield budget handed to NativeObservations).
private const val INITIAL_OBSERVE_MS = 10_000L

// why: ms of yield budget a wait step gets when it names no yield_time_ms.
private const val DEFAULT_WAIT_YIELD_MS = 10_000L

// why: ms each session may take to answer session/shutdown in the closing sweep; a slower answer times the case out.
private const val SHUTDOWN_TIMEOUT_MS = 2_000L

// why: ms the whole case may run when the input names no case_timeout_ms.
private const val CASE_TIMEOUT_MS = 30_000L

private const val SESSION_FIELD = "session"

private const val CELL_ID_FIELD = "cell_id"

private const val INPUT_FIELD = "input"

/** Test wrapper only: every RPC, admission, callback, closure and retirement runs production classes. */
public object ClientCandidate {
    private fun obj(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*fields))
    private fun str(value: String): JsonPrimitive = JsonPrimitive(value)
    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(name: String): BigInteger? = string(name)?.toBigIntegerOrNull()
    private fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull == true
    private fun ok(value: JsonElement = JsonPrimitive(true)): JsonObject = obj("ok" to value)

    private suspend fun result(operation: NativeOperation): JsonElement {
        val value = NativeFrames.value(operation.response.await())
        return if (value.string("type") == "wait/completed") value.getValue("outcome") else value
    }

    private suspend fun initial(cell: Cell): JsonElement {
        val frame = cell.observations.response(BigInteger.valueOf(INITIAL_OBSERVE_MS)).await()
        cell.observations.consumed()
        return NativeFrames.value(frame)
    }

    private fun executeRequest(input: JsonObject): JsonObject {
        val request = linkedMapOf<String, JsonElement>(
            "tool_call_id" to (input["tool_call_id"] ?: str("witness-call")),
            "enabled_tools" to (input["enabled_tools"] ?: JsonArray(emptyList())),
            "source" to input.getValue("source"),
        )
        listOf("yield_time_ms", "max_output_tokens").forEach { field ->
            input.number(field)?.let { request[field] = JsonPrimitive(it) }
        }
        return JsonObject(request)
    }

    private fun harnessError(error: Throwable): JsonObject =
        obj("harness_error" to str(error.message ?: error.javaClass.name))

    private suspend fun witnessed(args: Array<String>, input: JsonObject): JsonObject =
        Harness(args.single()).use { harness ->
            withTimeout(input.number("case_timeout_ms")?.longValueExact() ?: CASE_TIMEOUT_MS) {
                harness.run(input)
            }
        }

    /**
     * A failed harness is reported as data, as [Harness.typed] does for a failed step. Cancellation, the case timeout
     * included, and JVM errors propagate, as they did under the catch of Exception this replaces.
     */
    private suspend fun guarded(args: Array<String>, input: JsonObject): JsonObject =
        Cancellables.runCatchingBestEffort { witnessed(args, input) }.getOrElse(::harnessError)

    private data class Cell(
        val session: String,
        val wire: String,
        val alias: String,
        val observations: NativeObservations,
        val config: JsonObject,
    )

    private class Harness(private val program: String) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val process = ProcessBuilder(program, "--listen", "grpc+stdio://").start()
        private val connection = NativeConnection(process, scope, Dispatchers.IO, NativeConnectionClosed {})
        private val sessions = LinkedHashMap<String, JsonObject>()
        private val cells = LinkedHashMap<String, Cell>()
        private val deferred = LinkedHashMap<String, Cell>()
        private val callbacks = ConcurrentHashMap<String, Job>()
        private val events = java.util.Collections.synchronizedList(mutableListOf<JsonElement>())

        private fun event(value: JsonObject) { events.add(value) }
        private fun cell(step: JsonObject): Pair<String, String> {
            val alias = step.string("cell") ?: "last"
            val known = cells[alias]
            return (step.string(SESSION_FIELD) ?: "main") to (known?.wire ?: alias)
        }

        private suspend fun open(name: String, input: JsonObject): JsonObject {
            val limits = input["limits"] as? JsonObject
            val fields = linkedMapOf<String, JsonElement>("method" to str("session/open"), "sessionId" to str(name))
            if (limits != null) {
                fields["cellExecutionLimits"] = JsonObject(
                    listOfNotNull(
                        limits["max_yield_time_ms"]?.let { "maxYieldTimeMs" to it },
                        limits["max_heap_size_bytes"]?.let { "maxHeapSizeBytes" to it },
                    ).toMap(),
                )
            }
            result(connection.operation(JsonObject(fields)))
            sessions[name] = input
            return ok()
        }

        private fun pump(cell: Cell) {
            scope.launch {
                try {
                    for (frame in connection.events.cell(cell.session, cell.wire)) {
                        val id = frame.getValue("id")
                        val key = id.toString()
                        if (frame.string("type") == "delegate/cancel") {
                            callbacks.remove(key)?.cancel()
                        } else {
                            val request = frame.getValue("request").jsonObject
                            callbacks[key] = scope.launch { delegate(cell, id, request) }
                        }
                    }
                } catch (_: IOException) {
                    // The typed operation below records session/process failure; the callback stream has no value
                    // to fabricate.
                }
            }
        }

        private suspend fun delegate(cell: Cell, id: JsonElement, request: JsonObject) {
            val notify = request.string("type") == "notification/send"
            val invocation = request["invocation"] as? JsonObject
            try {
                if (notify) notification(cell, id, request) else toolCall(cell, id, invocation)
                connection.events.answered(id.toString())
            } catch (cancelled: CancellationException) {
                event(
                    obj(
                        "type" to str(if (notify) "notification_cancelled" else "tool_cancelled"),
                        CELL_ID_FIELD to str(cell.wire),
                    ),
                )
                throw cancelled
            } finally {
                callbacks.remove(id.toString())
            }
        }

        private suspend fun notification(cell: Cell, id: JsonElement, request: JsonObject) {
            event(
                obj(
                    "type" to str("notification"),
                    "call_id" to str(cell.config.string("tool_call_id") ?: "witness-call"),
                    SESSION_FIELD to str(cell.session),
                    "cell_alias" to str(cell.alias),
                    CELL_ID_FIELD to str(cell.wire),
                    "text" to request.getValue("text"),
                ),
            )
            delay(cell.config.number("notification_delay_ms")?.toLong() ?: 0L)
            event(
                obj(
                    "type" to str("notification_delivery_finished"),
                    SESSION_FIELD to str(cell.session),
                    "cell_alias" to str(cell.alias),
                    CELL_ID_FIELD to str(cell.wire),
                ),
            )
            connection.send(NativeRequests.notified(id))
        }

        private suspend fun toolCall(cell: Cell, id: JsonElement, invocation: JsonObject?) {
            val call = requireNotNull(invocation)
            val present = call.containsKey(INPUT_FIELD)
            event(
                obj(
                    "type" to str("tool"),
                    SESSION_FIELD to str(cell.session),
                    "cell_alias" to str(cell.alias),
                    "input_present" to JsonPrimitive(present),
                    "value" to JsonObject(call + (INPUT_FIELD to (call[INPUT_FIELD] ?: JsonNull))),
                ),
            )
            val name = call.getValue("tool_name").jsonObject.string("name").orEmpty()
            val config = (cell.config["callbacks"] as? JsonObject)?.get(name) as? JsonObject ?: cell.config
            delay(config.number("callback_delay_ms")?.toLong() ?: 0L)
            val error = config.string("callback_error")
            val payload = config["callback_result"] ?: call[INPUT_FIELD] ?: JsonObject(emptyMap())
            connection.send(
                NativeRequests.delegate(
                    id,
                    CodeModeResult(
                        id = "witness",
                        output = error.orEmpty(),
                        isError = error != null,
                        payload = if (error == null) payload else null,
                    ),
                ),
            )
        }

        private suspend fun execute(session: String, input: JsonObject): JsonElement {
            val accepted = CompletableDeferred<NativeObservations>()
            input.number("preempt_after_ms")?.let { delayMs ->
                scope.launch {
                    delay(delayMs.toLong())
                    accepted.await().yieldObservation()
                }
            }
            val request = executeRequest(input)
            val operation = connection.operation(
                obj(
                    "method" to str("session/execute"),
                    "sessionId" to str(session),
                    "request" to request,
                ),
                execute = true,
            )
            val started = NativeFrames.value(operation.response.await())
            val wire = requireNotNull(started.string("cellId"))
            val alias = input.string("as") ?: "last"
            val cell =
                Cell(session, wire, alias, NativeObservations(connection, scope, session, wire, operation), input)
            cells[alias] = cell; cells["last"] = cell
            accepted.complete(cell.observations)
            event(
                obj(
                    "type" to str("started"),
                    SESSION_FIELD to str(session),
                    "alias" to str(alias),
                    CELL_ID_FIELD to str(wire),
                ),
            )
            pump(cell)
            return when {
                input.flag("defer_initial") -> { deferred[alias] = cell; obj("started" to str(wire)) }
                input.flag("drop_initial") -> obj("dropped_initial" to str(wire))
                else -> initial(cell)
            }
        }

        private suspend fun wait(input: JsonObject): JsonElement {
            val (session, wire) = cell(input)
            val existing = cells[input.string("cell") ?: "last"]
            val budget = input.number("yield_time_ms") ?: BigInteger.valueOf(DEFAULT_WAIT_YIELD_MS)
            if (existing == null) return result(connection.operation(NativeRequests.wait(session, wire, budget)))
            input.number("preempt_after_ms")?.let { delayMs ->
                scope.launch {
                    delay(delayMs.toLong()); existing.observations.yieldObservation()
                }
            }
            val future = existing.observations.response(budget)
            val frame = input.number("cancel_after_ms")?.let { timeout ->
                withTimeoutOrNull(timeout.toLong()) { future.await() }
            } ?: if (input.containsKey("cancel_after_ms")) null else future.await()
            if (frame == null) {
                existing.observations.cancelled()
                return obj("witness_cancelled" to JsonPrimitive(true))
            }
            existing.observations.consumed()
            val value = NativeFrames.value(frame)
            return value.getValue("outcome")
        }

        private suspend fun step(input: JsonObject): JsonElement {
            val session = input.string(SESSION_FIELD) ?: "main"
            return when (input.string("op")) {
                "execute" -> execute(session, input)
                "initial" -> initial(requireNotNull(deferred.remove(input.string("cell") ?: "last")))
                "wait" -> wait(input)
                "terminate" -> {
                    val (owner, wire) = cell(input)
                    result(connection.operation(NativeRequests.terminate(owner, wire)))
                }
                else -> sessionStep(input, session)
            }
        }

        private suspend fun sessionStep(input: JsonObject, session: String): JsonElement = when (input.string("op")) {
            "open" -> open(session, input).getValue("ok")
            "shutdown" -> {
                result(connection.operation(NativeRequests.session("session/shutdown", session)))
                JsonPrimitive(null as String?)
            }
            "sleep" -> {
                delay(input.number("ms")?.toLong() ?: 0L)
                JsonPrimitive(true)
            }
            "crash_host" -> {
                process.destroyForcibly().waitFor()
                JsonPrimitive(true)
            }
            else -> error("unknown candidate witness operation")
        }

        private suspend fun typed(input: JsonObject): JsonObject = try {
            val value = step(input)
            if (value is JsonObject && value["witness_cancelled"] == JsonPrimitive(true)) {
                obj("cancelled" to JsonPrimitive(true))
            } else {
                ok(value)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            obj("error" to str(error.message.orEmpty()))
        } catch (error: IllegalArgumentException) {
            obj("error" to str(error.message.orEmpty()))
        } catch (error: IllegalStateException) { obj("error" to str(error.message.orEmpty())) }

        suspend fun run(input: JsonObject): JsonObject {
            connection.handshake()
            val steps = mutableListOf<JsonElement>()
            if (!input.flag("manual_open")) {
                val open = JsonObject(input + ("op" to str("open")))
                steps.add(obj("op" to str("open"), "result" to typed(open)))
            }
            for ((index, step) in (input.getValue("steps") as JsonArray).withIndex()) {
                val request = step.jsonObject
                val result = typed(request)
                event(
                    obj(
                        "type" to str("operation_returned"),
                        "step_index" to JsonPrimitive(index),
                        "op" to request.getValue("op"),
                    ),
                )
                steps.add(obj("op" to request.getValue("op"), "result" to result))
            }
            for (session in sessions.keys) {
                try {
                    withTimeout(SHUTDOWN_TIMEOUT_MS) {
                        result(connection.operation(NativeRequests.session("session/shutdown", session)))
                    }
                } catch (_: IOException) { }
            }
            return obj("steps" to JsonArray(steps), "events" to JsonArray(synchronized(events) { events.toList() }))
        }

        override fun close() { connection.close(); scope.cancel(); process.destroyForcibly() }
    }

    @JvmStatic
    public fun main(args: Array<String>): Unit = runBlocking {
        val input = Json.parseToJsonElement(System.`in`.bufferedReader().readText()).jsonObject
        val value = guarded(args, input)
        val origins = listOf(
            NativeConnection::class.java, NativeObservations::class.java, NativeFrames::class.java,
            NativeRequests::class.java, NativeGrpcChannel::class.java, NativeGrpcRouter::class.java,
            NativeGrpcSessions::class.java, NativeGrpcExecutions::class.java, NativeGrpcDelegates::class.java,
            NativeGrpcWaits::class.java, NativeGrpcWire::class.java,
        ).associate { type ->
            type.name to str(type.protectionDomain.codeSource.location.toURI().path)
        }
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
