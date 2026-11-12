import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import splice.codemode.NativeCodeModeRuntime
import splice.codemode.WorkerSpawn
import splice.codemode.nativehost.NativeConnection
import splice.codemode.nativehost.grpc.NativeGrpcRouter
import splice.codemode.nativehost.sessions.NativeLogicalSession
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeOptions
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

// why: the foreground observation budget in ms for the healthy cell's first observation.
private const val OBSERVE_MS = 10_000L

// why: yield_time_ms and max_output_tokens of the healthy execute step when it names none; same 10_000 as the options.
private const val DEFAULT_BUDGET = 10_000L

// why: seconds the held return waits for releaseReturn() before check() fails the scenario.
private const val RETURN_RELEASE_WAIT_SECONDS = 10L

// why: ms the whole opening-cancel scenario may run before withTimeout cancels it.
private const val CASE_TIMEOUT_MS = 30_000L

// why: the cancel_after_ms in ms that the scripted target open step must carry; scenarioSteps pins it.
private const val EXPECTED_CANCEL_AFTER_MS = 40

private const val BOOTSTRAP_SESSION = "bootstrap"

private const val RESULT_KEY = "result"

/** Cancels the actual public opening owner while its return to the caller is queued. */
public object ClientCandidateOpening {
    private fun str(value: String) = JsonPrimitive(value)
    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*fields))
    private fun ok(value: JsonElement = JsonPrimitive(true)) = obj("ok" to value)
    private class CancelDispatchReturn : CoroutineDispatcher() {
        private val returns = java.util.concurrent.atomic.AtomicInteger()
        private val release = java.util.concurrent.CountDownLatch(1)
        private val suspended = java.util.concurrent.CountDownLatch(1)
        private val heldReturn = java.util.concurrent.CountDownLatch(1)
        fun hasSuspended() = suspended.count == 0L
        fun hasHeldReturn() = heldReturn.count == 0L
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val dispatch = returns.incrementAndGet()
            if (dispatch == 1) {
                Dispatchers.IO.dispatch(context) { block.run(); suspended.countDown() }
            } else if (dispatch == 2) {
                Thread.ofVirtual().start {
                    heldReturn.countDown()
                    check(release.await(RETURN_RELEASE_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
                    block.run()
                }
            } else {
                Dispatchers.IO.dispatch(context, block)
            }
        }
        fun releaseReturn() { release.countDown() }
    }
    private class HoldIoDispatch : CoroutineDispatcher() {
        private val armed = java.util.concurrent.atomic.AtomicBoolean()
        private val held = java.util.concurrent.atomic.AtomicReference<Pair<CoroutineContext, Runnable>?>()
        fun arm() { check(armed.compareAndSet(false, true)) }
        fun hasHeld() = held.get() != null
        fun release() {
            val(context, block) = checkNotNull(
                held.getAndSet(null),
            ); Dispatchers.IO.dispatch(context, block)
        }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (armed.compareAndSet(true, false)) {
                check(held.compareAndSet(null, context to block))
            } else {
                Dispatchers.IO.dispatch(context, block)
            }
        }
    }

    @JvmStatic public fun main(args: Array<String>) = runBlocking {
        val input = Json.parseToJsonElement(System.`in`.bufferedReader().readText()) as JsonObject
        val requests = scenarioSteps(input)
        val traceFile = Path.of(checkNotNull(System.getenv("SPLICE_GRPC_TRACE_PATH")))
        val heldIo = HoldIoDispatch()
        val runtime = NativeCodeModeRuntime(
            ioDispatcher = heldIo,
            spawn = WorkerSpawn { builder ->
                builder.environment()["SPLICE_GRPC_TRACE_PATH"] = traceFile.toString()
                builder.command(args.single(), "--listen", "grpc+stdio://").start()
            },
        )
        val result = runtime.use {
            withTimeout(CASE_TIMEOUT_MS) {
                scenario(runtime, heldIo, requests, traceFile)
            }
        }
        val origins = listOf(
            NativeCodeModeRuntime::class.java,
            NativeLogicalSession::class.java,
            NativeConnection::class.java,
            NativeGrpcRouter::class.java,
        ).associate {
            it.name to str(it.protectionDomain.codeSource.location.toURI().path)
        }
        println(
            obj(
                "id" to input.getValue("id"),
                "nonce" to input.getValue("nonce"),
                RESULT_KEY to result,
                "class_origins" to JsonObject(origins),
                "classpath" to str(System.getProperty("java.class.path")),
            ),
        )
    }

    /** The scripted steps, once the scenario shape this probe was written for has been checked. */
    private fun scenarioSteps(input: JsonObject): List<JsonObject> {
        check(input["fault_mode"] == str("open-dispatch-return-cancel") && input["manual_open"] == JsonPrimitive(true))
        val requests = (input.getValue("steps") as JsonArray).map { it as JsonObject }
        check(requests.map { it["op"] } == listOf(str("open"), str("open"), str("execute"), str("shutdown")))
        check(
            requests.map { it["session"] } == listOf(
                str(BOOTSTRAP_SESSION),
                str("target"),
                str(BOOTSTRAP_SESSION),
                str(BOOTSTRAP_SESSION),
            ),
        )
        check(
            requests[1].keys == setOf(
                "op",
                "session",
                "cancel_after_ms",
            ) && requests[1]["cancel_after_ms"] == JsonPrimitive(EXPECTED_CANCEL_AFTER_MS),
        )
        check(requests[2]["as"] == str("healthy"))
        return requests
    }

    /** Cancels the target open while its return is held, then shows the bootstrap session still runs a cell. */
    private suspend fun CoroutineScope.scenario(
        runtime: NativeCodeModeRuntime,
        heldIo: HoldIoDispatch,
        requests: List<JsonObject>,
        traceFile: Path,
    ): JsonObject {
        cancelOpening(runtime, heldIo, traceFile)
        val cell = startHealthy(runtime, requests[2])
        val step = cell.observe(OBSERVE_MS)
        check(step is CodeModeStep.Completed && !step.terminated)
        val response = rendered(cell.cellId, step)
        runtime.closeSession(BOOTSTRAP_SESSION)
        return obj(
            "steps" to JsonArray(
                listOf(
                    obj("op" to str("open"), RESULT_KEY to ok()),
                    obj("op" to str("open"), RESULT_KEY to obj("cancelled" to JsonPrimitive(true))),
                    obj("op" to str("execute"), RESULT_KEY to ok(response)),
                    obj("op" to str("shutdown"), RESULT_KEY to ok(JsonNull)),
                ),
            ),
            "events" to JsonArray(
                listOf(
                    obj(
                        "type" to str("started"),
                        "session" to str(BOOTSTRAP_SESSION),
                        "alias" to str("healthy"),
                        "cell_id" to str(cell.cellId),
                    ),
                ),
            ),
        )
    }

    /** Holds the target open's IO and return, cancels it once the peer saw both opens, and waits for the cancel row. */
    private suspend fun CoroutineScope.cancelOpening(
        runtime: NativeCodeModeRuntime,
        heldIo: HoldIoDispatch,
        traceFile: Path,
    ) {
        runtime.openNativeSession(BOOTSTRAP_SESSION)
        val bootstrap = runtime.logicalSession(BOOTSTRAP_SESSION)
        val dispatcher = CancelDispatchReturn()
        heldIo.arm()
        val opening = async { withContext(dispatcher) { runtime.openNativeSession("target") } }
        while (!heldIo.hasHeld() || !dispatcher.hasSuspended()) delay(1)
        heldIo.release()
        while (!dispatcher.hasHeldReturn()) delay(1)
        while (trace(traceFile).count { it.sentOpened() } < 2) delay(1)
        opening.cancel()
        dispatcher.releaseReturn()
        opening.join()
        check(opening.isCancelled)
        while (trace(traceFile).none { it.cancelledSession2() }) delay(1)
        bootstrap.current()
    }

    /** Starts the scripted healthy execute on the bootstrap session with the step's budgets. */
    private suspend fun startHealthy(runtime: NativeCodeModeRuntime, request: JsonObject): NativeCodeModeCell {
        fun budget(name: String) = (request[name] as? JsonPrimitive)?.content?.toBigIntegerOrNull()
            ?: BigInteger.valueOf(DEFAULT_BUDGET)
        val yield = budget("yield_time_ms")
        val tokens = budget("max_output_tokens")
        val maximum = BigInteger.valueOf(Long.MAX_VALUE)
        val options = NativeCodeModeOptions(
            yieldTimeMs = yield.min(maximum).toLong(),
            maxOutputTokens = tokens.min(maximum).toLong(),
            unsignedYieldTimeMs = yield,
            unsignedMaxOutputTokens = tokens,
            toolCallId = (request["tool_call_id"] as? JsonPrimitive)?.content ?: "witness-call",
        )
        return runtime.startNativeSession(
            BOOTSTRAP_SESSION,
            (request.getValue("source") as JsonPrimitive).content,
            emptySet(),
            emptyMap(),
            options,
        )
    }

    private fun rendered(cellId: String, step: CodeModeStep.Completed): JsonObject {
        fun duration(
            value: java.time.Duration?,
        ) = value?.let {
            obj(
                "secs" to JsonPrimitive(it.seconds),
                "nanos" to JsonPrimitive(it.nano),
            )
        } ?: JsonNull
        return obj(
            "Result" to obj(
                "cell_id" to str(cellId),
                "content_items" to JsonArray(step.content),
                "error_text" to (step.error?.let(::str) ?: JsonNull),
                "code_mode_host_duration" to duration(step.timing?.hostDuration),
                "result_buffered_for" to duration(step.timing?.resultBufferedFor),
            ),
        )
    }

    private fun trace(traceFile: Path): List<JsonObject> =
        Files.readAllLines(traceFile).map { Json.parseToJsonElement(it) as JsonObject }

    private fun JsonObject.isOpenSession(direction: String): Boolean =
        (this["direction"] as? JsonPrimitive)?.contentOrNull == direction &&
            (this["method"] as? JsonPrimitive)?.contentOrNull == "OpenSession"

    private fun JsonObject.sentOpened(): Boolean =
        isOpenSession("sent") && (this["value"] as? JsonObject)?.containsKey("opened") == true

    private fun JsonObject.cancelledSession2(): Boolean =
        isOpenSession("cancelled") && (this["value"] as? JsonObject)?.get("session_id") == str("session-2")
}
