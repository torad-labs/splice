import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
import kotlinx.serialization.json.jsonObject
import splice.codemode.NativeCodeModeRuntime
import splice.codemode.WorkerSpawn
import splice.codemode.nativehost.NativeControlCell
import splice.codemode.nativehost.grpc.NativeGrpcWaits
import splice.codemode.nativehost.grpc.state.NativeGrpcWaitSlots
import splice.codemode.nativehost.sessions.NativeLogicalControl
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeOptions
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

// why: the foreground observation budget in ms; every cell.observe call in this wrapper waits at most this long.
private const val OBSERVE_MS = 10_000L

// why: yield_time_ms and max_output_tokens of the execute step when it names none; NativeCodeModeOptions' default.
private const val DEFAULT_BUDGET = 10_000L

// why: seconds the second dispatch waits for cancelReturn() before check() fails the scenario.
private const val RETURN_CANCEL_WAIT_SECONDS = 10L

// why: ms the whole held-wait scenario may run before withTimeout cancels it.
private const val CASE_TIMEOUT_MS = 30_000L

// why: ms the replacement wait gets to register before the queued overlapping wait is issued.
private const val REPLACEMENT_SETTLE_MS = 30L

private const val RESULT_KEY = "result"

/** Wrapper only; concurrent and cancelled observations enter the actual public logical session owner. */
public object ClientCandidateWait {
    private fun str(value: String) = JsonPrimitive(value)
    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*fields))
    private fun ok(value: JsonElement = JsonPrimitive(true)) = obj("ok" to value)
    private fun failure(error: Throwable) = obj("error" to str(error.message.orEmpty()))
    private fun response(step: CodeModeStep, cell: String): JsonObject {
        val (kind, fields) = when (step) {
            is CodeModeStep.Yielded -> "Yielded" to obj(
                "cell_id" to str(step.cellId),
                "content_items" to JsonArray(step.content),
            )
            is CodeModeStep.Completed -> completed(step, cell)
            is CodeModeStep.Calls, is CodeModeStep.WaitingForResults, is CodeModeStep.Notified ->
                error("unexpected callback in wait slot probe")
        }
        val timing = when (step) {
            is CodeModeStep.Yielded -> step.timing
            is CodeModeStep.Completed -> step.timing
            is CodeModeStep.Calls, is CodeModeStep.WaitingForResults, is CodeModeStep.Notified -> null
        }
        return obj(
            kind to JsonObject(
                fields + mapOf(
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
    private suspend fun observe(cell: NativeCodeModeCell): JsonObject = try {
        ok(obj("LiveCell" to response(cell.observe(OBSERVE_MS), cell.cellId)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: java.io.IOException) { failure(error) }

    private class CancelDispatchReturn : CoroutineDispatcher() {
        private val returns = java.util.concurrent.atomic.AtomicInteger()
        private val cancel = java.util.concurrent.CountDownLatch(1)
        private val suspended = java.util.concurrent.CountDownLatch(1)
        fun hasSuspended() = suspended.count == 0L
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val dispatch = returns.incrementAndGet()
            if (dispatch == 1) {
                Dispatchers.IO.dispatch(context) { block.run(); suspended.countDown() }
            } else if (dispatch == 2) {
                Thread.ofVirtual().start {
                    check(cancel.await(RETURN_CANCEL_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
                    context[Job]?.cancel(CancellationException("cancel dispatched wait before returning to caller"))
                    block.run()
                }
            } else {
                Dispatchers.IO.dispatch(context, block)
            }
        }
        fun cancelReturn() { cancel.countDown() }
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
        val input = Json.parseToJsonElement(System.`in`.bufferedReader().readText()).jsonObject
        val requests = (input.getValue("steps") as JsonArray).map { it.jsonObject }
        check(requests.map { it["op"] } == listOf(str("execute"), str("parallel_wait")))
        val execution = requests[0]
        check(execution["as"] == str("held") && requests[1]["cell"] == str("held"))
        check((execution["enabled_tools"] as? JsonArray).isNullOrEmpty())
        val traceFile = System.getenv("SPLICE_GRPC_TRACE_PATH")?.let(Path::of)
            ?: Files.createTempFile("splice-wait-peer-", ".jsonl")
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
                scenario(runtime, heldIo, input, execution, traceFile)
            }
        }
        val origins = listOf(
            NativeCodeModeRuntime::class.java,
            NativeLogicalControl::class.java,
            NativeControlCell::class.java,
            NativeGrpcWaits::class.java,
            NativeGrpcWaitSlots::class.java,
        ).associate { it.name to str(it.protectionDomain.codeSource.location.toURI().path) }
        println(
            obj(
                "id" to input.getValue("id"),
                "nonce" to input.getValue("nonce"),
                RESULT_KEY to result,
                "class_origins" to JsonObject(origins),
                "classpath" to str(System.getProperty("java.class.path")),
                "peer_trace" to JsonArray(Files.readAllLines(traceFile).map(::str)),
            ),
        )
    }

    /** Opens the session, starts the held cell and records the rows around the overlapping-wait choreography. */
    private suspend fun CoroutineScope.scenario(
        runtime: NativeCodeModeRuntime,
        heldIo: HoldIoDispatch,
        input: JsonObject,
        execution: JsonObject,
        traceFile: Path,
    ): JsonObject {
        val rows = mutableListOf<JsonElement>()
        val events = mutableListOf<JsonElement>()
        runtime.openNativeSession("main")
        rows += obj("op" to str("open"), RESULT_KEY to ok())
        fun budget(name: String) = (execution[name] as? JsonPrimitive)?.content?.toBigIntegerOrNull()
            ?: BigInteger.valueOf(DEFAULT_BUDGET)
        val yield = budget("yield_time_ms")
        val tokens = budget("max_output_tokens")
        val maximum = BigInteger.valueOf(Long.MAX_VALUE)
        val options = NativeCodeModeOptions(
            yieldTimeMs = yield.min(maximum).toLong(),
            maxOutputTokens = tokens.min(maximum).toLong(),
            unsignedYieldTimeMs = yield,
            unsignedMaxOutputTokens = tokens,
            toolCallId = (execution["tool_call_id"] as? JsonPrimitive)?.content ?: "witness-call",
        )
        val cell = runtime.startNativeSession(
            "main",
            (execution.getValue("source") as JsonPrimitive).content,
            emptySet(),
            emptyMap(),
            options,
        )
        events += obj(
            "type" to str("started"),
            "session" to str("main"),
            "alias" to str("held"),
            "cell_id" to str(cell.cellId),
        )
        rows += obj("op" to str("execute"), RESULT_KEY to ok(response(cell.observe(OBSERVE_MS), cell.cellId)))
        val contended = contend(runtime, heldIo, input, traceFile, cell.cellId)
        rows += obj("op" to str("parallel_wait"), RESULT_KEY to ok(contended))
        return obj("steps" to JsonArray(rows), "events" to JsonArray(events))
    }

    /** The first wait is held, an overlapping wait supersedes it, then a replacement and a queued wait follow. */
    private suspend fun CoroutineScope.contend(
        runtime: NativeCodeModeRuntime,
        heldIo: HoldIoDispatch,
        input: JsonObject,
        traceFile: Path,
        cellId: String,
    ): JsonObject {
        val firstControl = runtime.controlNativeSession("main", cellId)
        val dispatcher = if (input["dispatch_cancel"] == JsonPrimitive(true)) CancelDispatchReturn() else null
        if (dispatcher != null) heldIo.arm()
        val first = async {
            if (dispatcher == null) {
                observe(firstControl)
            } else {
                withContext(dispatcher) { observe(firstControl) }
            }
        }
        if (dispatcher != null) {
            while (!heldIo.hasHeld() || !dispatcher.hasSuspended()) delay(1)
            heldIo.release()
        }
        while (Files.readAllLines(traceFile).none { it.contains("\"method\":\"Wait\"") }) delay(1)
        val overlapping = observe(runtime.controlNativeSession("main", cellId))
        if (dispatcher == null) {
            first.cancelAndJoin()
        } else {
            dispatcher.cancelReturn()
            first.join()
            check(first.isCancelled)
        }
        val replacement = async { observe(runtime.controlNativeSession("main", cellId)) }
        delay(REPLACEMENT_SETTLE_MS)
        val queued = observe(runtime.controlNativeSession("main", cellId))
        val replaced = replacement.await()
        return obj("overlapping" to overlapping, "queued_overlap" to queued, "replacement" to replaced)
    }
}
