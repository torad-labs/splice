package splice.app.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions
import splice.upstream.codemode.ProcessDispatchers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class CodeModeParityProtocol(
    private val runtime: NativeCodeModeLifecycle,
    private val frames: CodeModeParityFrames,
    private val scope: CoroutineScope,
) {
    private data class Held(val session: String, val cell: NativeCodeModeCell)
    private data class Pending(val held: Held, val callback: String)
    private val cells = ConcurrentHashMap<Pair<String, String>, Held>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val ids = AtomicLong()

    suspend fun run() {
        try {
            while (true) {
                val frame = withContext(ProcessDispatchers().io()) { frames.read() } ?: break
                if (JsonScalars.str(frame, "type") == "delegate/response") {
                    reply(frame)
                } else {
                    scope.launch { dispatch(frame) }
                }
            }
        } finally {
            runtime.close()
        }
    }

    private suspend fun dispatch(frame: JsonObject) {
        val type = JsonScalars.str(frame, "type")
        if (type == "connection/hello") {
            frames.send(
                buildJsonObject {
                    put(
                        "type",
                        "connection/ready",
                    )
                    put("selectedVersion", 1)
                    put("capabilities", JsonArray(emptyList()))
                },
            )
        } else if (type == "operation/request") {
            val id = frame.getValue("id")
            val request = frame.getValue(FIELD_REQUEST).jsonObject
            try {
                operation(id, request)
            } catch (error: IllegalArgumentException) {
                response(id, CodeModeParityPayload.failure(error.message.orEmpty()))
            } catch (error: IllegalStateException) {
                response(id, CodeModeParityPayload.failure(error.message.orEmpty()))
            }
        }
    }

    private suspend fun operation(id: JsonElement, request: JsonObject) {
        val session = JsonScalars.str(request, FIELD_SESSION).orEmpty()
        when (JsonScalars.str(request, "method")) {
            "session/open" -> response(
                id,
                CodeModeParityPayload.ok(
                    buildJsonObject {
                        put("type", "session/ready")
                        put(FIELD_SESSION, session)
                    },
                ),
            )
            "session/execute" -> execute(id, session, request.getValue(FIELD_REQUEST).jsonObject)
            "session/wait" -> wait(id, session, request.getValue(FIELD_REQUEST).jsonObject)
            "session/terminate" -> terminate(id, session, JsonScalars.str(request, "cellId").orEmpty())
            "session/shutdown" -> {
                runtime.closeSession(session)
                cells.keys.removeIf { it.first == session }
                response(
                    id,
                    CodeModeParityPayload.ok(
                        buildJsonObject {
                            put("type", "session/closed")
                            put(FIELD_SESSION, session)
                        },
                    ),
                )
            }
            else -> error("Unsupported native diagnostic method")
        }
    }

    private suspend fun execute(id: JsonElement, session: String, request: JsonObject) {
        val definitions = (request["enabled_tools"] as? JsonArray).orEmpty().map { it.jsonObject }
        val tools = definitions.associate { item ->
            val name = item.getValue("tool_name").jsonObject
            JsonScalars.str(name, "name").orEmpty() to JsonScalars.str(item, "description").orEmpty()
        }
        val options = NativeCodeModeOptions(
            CodeModeParityPayload.number(request, "yield_time_ms", DEFAULT_OBSERVATION_BUDGET),
            CodeModeParityPayload.number(request, "max_output_tokens", DEFAULT_OBSERVATION_BUDGET),
        )
        val cell = runtime.startNativeSession(
            session,
            JsonScalars.str(request, "source").orEmpty(),
            tools.keys,
            tools,
            options,
        )
        val held = Held(session, cell)
        cells[session to cell.cellId] = held
        response(
            id,
            CodeModeParityPayload.ok(
                buildJsonObject {
                    put("type", "execution/started")
                    put("cellId", cell.cellId)
                },
            ),
        )
        val step = observe(held, options.yieldTimeMs)
        frames.send(
            buildJsonObject {
                put("type", "execute/initialResponse")
                put("id", id)
                put(FIELD_RESULT, CodeModeParityPayload.ok(CodeModeParityPayload.outcome(cell.cellId, step)))
            },
        )
    }

    private suspend fun wait(id: JsonElement, session: String, request: JsonObject) {
        val cellId = JsonScalars.str(request, "cell_id").orEmpty()
        val held = cells[session to cellId]
        val step = held?.let {
            observe(
                it,
                CodeModeParityPayload.number(request, "yield_time_ms", DEFAULT_OBSERVATION_BUDGET),
            )
        }
            ?: CodeModeStep.Completed("")
        response(
            id,
            CodeModeParityPayload.ok(
                buildJsonObject {
                    put("type", "wait/completed")
                    put(
                        "outcome",
                        buildJsonObject {
                            put(
                                if (held == null) "MissingCell" else "LiveCell",
                                CodeModeParityPayload.outcome(cellId, step),
                            )
                        },
                    )
                },
            ),
        )
    }

    private suspend fun terminate(id: JsonElement, session: String, cellId: String) {
        val held = cells.remove(session to cellId)
        val step = held?.cell?.terminate() ?: CodeModeStep.Completed("", terminated = true)
        response(id, CodeModeParityPayload.ok(CodeModeParityPayload.outcome(cellId, step)))
    }

    private suspend fun observe(held: Held, budget: Long): CodeModeStep {
        while (true) {
            when (val step = held.cell.observe(budget)) {
                is CodeModeStep.Calls -> step.calls.forEach { callback(held, it) }
                is CodeModeStep.Notified -> notification(held, step.text)
                else -> {
                    if (step is CodeModeStep.Completed) cells.remove(held.session to held.cell.cellId)
                    return step
                }
            }
        }
    }

    private fun callback(held: Held, call: CodeModeCall) {
        val id = ids.incrementAndGet().toString()
        pending[id] = Pending(held, call.id)
        delegate(
            id,
            held,
            buildJsonObject {
                put("type", "tool/invoke")
                put(
                    "invocation",
                    buildJsonObject {
                        put("cell_id", held.cell.cellId)
                        put("runtime_tool_call_id", call.id)
                        put(
                            "tool_name",
                            buildJsonObject {
                                put("name", call.name)
                                put("namespace", JsonNull)
                            },
                        )
                        put("tool_kind", "function")
                        put("input", call.arguments)
                    },
                )
            },
        )
    }

    private fun notification(held: Held, text: String) {
        delegate(
            ids.incrementAndGet().toString(),
            held,
            buildJsonObject {
                put(
                    "type",
                    "notification/send",
                )
                put("callId", "diagnostic")
                put("cellId", held.cell.cellId)
                put("text", text)
            },
        )
    }

    private fun delegate(id: String, held: Held, request: JsonObject) = frames.send(
        buildJsonObject {
            put("type", "delegate/request")
            put("id", id)
            put(FIELD_SESSION, held.session)
            put(FIELD_REQUEST, request)
        },
    )

    private suspend fun reply(frame: JsonObject) {
        val id = JsonScalars.str(frame, "id") ?: return
        val awaiting = pending.remove(id) ?: return
        val result = frame.getValue(FIELD_RESULT).jsonObject
        val success = JsonScalars.str(result, "status") == "ok"
        val value = (result["value"] as? JsonObject)?.get(FIELD_RESULT) ?: result["message"] ?: JsonNull
        awaiting.held.cell.submitResults(listOf(CodeModeResult(awaiting.callback, value.toString(), !success)))
    }

    private fun response(id: JsonElement, result: JsonObject) = frames.send(
        buildJsonObject {
            put("type", "operation/response")
            put("id", id)
            put(FIELD_RESULT, result)
        },
    )
}

private const val FIELD_REQUEST = "request"
private const val FIELD_RESULT = "result"
private const val FIELD_SESSION = "sessionId"
private const val DEFAULT_OBSERVATION_BUDGET = 10_000L
