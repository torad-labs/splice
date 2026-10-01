// NEW: bounded framed protocol between splice and its bundled JavaScript worker.
package splice.codemode

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.FIELD_ERROR
import splice.core.wire.FIELD_TYPE
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.codemode.CodeModeProtocol
import splice.upstream.codemode.CodeModeResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

private const val DESCRIPTION_RESULT_OUTPUT: String = "result output"
private const val FIELD_ARGUMENTS: String = "arguments"
private const val FIELD_CALLS: String = "calls"
private const val FIELD_DESCRIPTIONS: String = "descriptions"
private const val FIELD_ID: String = "id"
private const val FIELD_IS_ERROR: String = "isError"
private const val FIELD_NAME: String = "name"
private const val FIELD_OUTPUT: String = "output"
private const val FIELD_RESULTS: String = "results"
private const val FIELD_SOURCE: String = "source"
private const val FIELD_TOOLS: String = "tools"
private const val TYPE_CALLS: String = "calls"
private const val TYPE_COMPLETED: String = "completed"
private const val TYPE_READY: String = "ready"
private const val TYPE_RESULTS: String = "results"
private const val TYPE_START: String = "start"
private const val TOO_MANY_TOOLS: String = "Too many code-mode tools"
private const val DESCRIPTION_OVER_BUDGET: String =
    "(description omitted: the client tool catalog exceeded the code-mode description budget)"

/** Strict, bounded frames shared by the parent runtime and isolated worker. */
internal object CodeModeWire {
    const val maxFrameBytes: Int = CodeModeLimits.MAX_FRAME_BYTES
    const val maxTextBytes: Int = CodeModeLimits.MAX_TEXT_BYTES
    const val maxCallsPerBatch: Int = 8
    const val maxCallsPerCell: Int = 32
    const val maxToolCatalog: Int = 2_048

    fun write(output: DataOutputStream, frame: JsonObject) = CodeModeTransport.write(output, frame)

    fun read(input: DataInputStream): JsonObject = CodeModeTransport.read(input)

    /** V4-226: a worker's first frame, sent once its JVM and JavaScript engine are up and before it
     *  reads the start frame, so the parent can time its start apart from the script's advance. */
    fun readyFrame(): JsonObject = buildJsonObject { put(FIELD_TYPE, TYPE_READY) }

    /** V4-388: [descriptions] fill what the frame has left after the source and the tool names, in name
     *  order and charged at their JSON-escaped size, so the encoded frame never passes [maxFrameBytes];
     *  a description that no longer fits is sent as [DESCRIPTION_OVER_BUDGET], so the cell's
     *  `ALL_TOOLS` says why the entry is bare. */
    fun startFrame(source: String, tools: Set<String>, descriptions: Map<String, String> = emptyMap()): JsonObject {
        CodeModeFrames.requireText(source, FIELD_SOURCE)
        require(tools.size <= maxToolCatalog) { TOO_MANY_TOOLS }
        tools.forEach(CodeModeFrames::requireToolName)
        val names = tools.sorted()
        var budget = maxFrameBytes - CodeModeProtocol.encodeFrame(start(source, names, names.associateWith { "" })).size
        val fitted = names.associateWith { name ->
            val description = CodeModeLimits.boundedText(descriptions[name].orEmpty())
            val entry = listOf(description, DESCRIPTION_OVER_BUDGET, "").firstOrNull { escaped(it) <= budget }.orEmpty()
            budget -= escaped(entry)
            entry
        }
        return start(source, names, fitted)
    }

    private fun start(source: String, names: List<String>, descriptions: Map<String, String>): JsonObject =
        buildJsonObject {
            put(FIELD_TYPE, TYPE_START)
            put(FIELD_SOURCE, source)
            put(FIELD_TOOLS, buildJsonArray { names.forEach(::add) })
            put(FIELD_DESCRIPTIONS, buildJsonObject { descriptions.forEach { (name, text) -> put(name, text) } })
        }

    /** What [text] adds to a frame over an empty string: its JSON-escaped UTF-8 size, quotes excluded. */
    private fun escaped(text: String): Int = JsonPrimitive(text).toString().encodeToByteArray().size - 2

    fun resultFrame(results: List<CodeModeResult>): JsonObject {
        results.forEach { result ->
            CodeModeFrames.requireText(result.id, "result id")
            CodeModeFrames.requireText(result.output, DESCRIPTION_RESULT_OUTPUT)
        }
        return CodeModeProtocol.resultsFrame(results)
    }

    fun callsFrame(calls: List<CodeModeCall>): JsonObject = buildJsonObject {
        put(FIELD_TYPE, TYPE_CALLS)
        put(
            FIELD_CALLS,
            buildJsonArray {
                calls.forEach { call ->
                    add(
                        buildJsonObject {
                            put(FIELD_ID, call.id)
                            put(FIELD_NAME, call.name)
                            put(FIELD_ARGUMENTS, call.arguments)
                        },
                    )
                }
            },
        )
    }

    fun completedFrame(output: String, error: String?): JsonObject = buildJsonObject {
        put(FIELD_TYPE, TYPE_COMPLETED)
        put(FIELD_OUTPUT, output)
        put(FIELD_ERROR, error?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal object CodeModeFrames {
    /** A worker's first frame: ready, or the fatal diagnostics of a start that failed. Anything else
     *  is a worker that answered before it was ready. */
    fun parseReady(frame: JsonObject) {
        when (CodeModeFields.requiredString(frame, FIELD_TYPE)) {
            TYPE_READY -> CodeModeFields.requireKeys(frame, setOf(FIELD_TYPE))
            CODE_MODE_FATAL_FRAME_TYPE -> CodeModeFatalFrame.parse(frame)
            else -> throw IOException("Code-mode worker answered before it was ready")
        }
    }

    fun parseStart(frame: JsonObject): WorkerStart {
        CodeModeFields.requireKeys(frame, setOf(FIELD_TYPE, FIELD_SOURCE, FIELD_TOOLS, FIELD_DESCRIPTIONS))
        require(CodeModeFields.requiredString(frame, FIELD_TYPE) == TYPE_START) { "Expected a code-mode start frame" }
        val source = CodeModeFields.requiredString(frame, FIELD_SOURCE).also { requireText(it, FIELD_SOURCE) }
        val rawTools = CodeModeFields.requiredArray(frame, FIELD_TOOLS)
        require(rawTools.size <= CodeModeWire.maxToolCatalog) { TOO_MANY_TOOLS }
        val tools = rawTools.map { element ->
            (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?: throw IllegalArgumentException("Code-mode tool must be a string")
        }.toSet()
        require(tools.size <= CodeModeWire.maxToolCatalog) { TOO_MANY_TOOLS }
        tools.forEach(::requireToolName)
        val rawDescriptions = CodeModeFields.requiredObject(
            frame[FIELD_DESCRIPTIONS],
            "Code-mode protocol field $FIELD_DESCRIPTIONS must be an object",
        )
        val descriptions = rawDescriptions.keys.associateWith { CodeModeFields.requiredString(rawDescriptions, it) }
        return WorkerStart(source, tools, descriptions)
    }

    fun parseResults(frame: JsonObject): List<CodeModeResult> {
        CodeModeFields.requireKeys(frame, setOf(FIELD_TYPE, FIELD_RESULTS))
        require(CodeModeFields.requiredString(frame, FIELD_TYPE) == TYPE_RESULTS) {
            "Expected a code-mode results frame"
        }
        val rawResults = CodeModeFields.requiredArray(frame, FIELD_RESULTS)
        require(rawResults.size <= CodeModeWire.maxCallsPerBatch) { "Too many code-mode results" }
        return rawResults.map { element ->
            val result = element as? JsonObject ?: throw IllegalArgumentException("Code-mode result must be an object")
            CodeModeFields.requireKeys(result, setOf(FIELD_ID, FIELD_OUTPUT, FIELD_IS_ERROR))
            CodeModeResult(
                id = CodeModeFields.requiredString(result, FIELD_ID).also { requireText(it, "result id") },
                output = CodeModeFields.requiredString(result, FIELD_OUTPUT)
                    .also { requireText(it, DESCRIPTION_RESULT_OUTPUT) },
                isError = CodeModeFields.requiredBoolean(result, FIELD_IS_ERROR),
            )
        }
    }

    fun parseReply(frame: JsonObject, tools: Set<String>, nextId: Int): WorkerReply =
        when (CodeModeFields.requiredString(frame, FIELD_TYPE)) {
            TYPE_CALLS -> parseCalls(frame, tools, nextId)
            TYPE_COMPLETED -> parseCompleted(frame)
            CODE_MODE_FATAL_FRAME_TYPE -> CodeModeFatalFrame.parse(frame)
            else -> throw IOException("Code-mode worker sent an unknown reply type")
        }

    fun validateResultSet(expected: List<CodeModeCall>, results: List<CodeModeResult>) {
        if (expected.isEmpty() && results.isEmpty()) return
        require(expected.size == results.size) { "Code-mode results do not match pending calls" }
        val expectedIds = expected.map(CodeModeCall::id).toSet()
        val resultIds = results.map(CodeModeResult::id)
        require(resultIds.size == resultIds.toSet().size && resultIds.toSet() == expectedIds) {
            "Code-mode results do not match pending calls"
        }
        results.forEach { result -> requireText(result.output, DESCRIPTION_RESULT_OUTPUT) }
    }

    fun requireText(value: String, description: String) {
        require(CodeModeLimits.fitsText(value)) {
            "Code-mode $description exceeds ${CodeModeWire.maxTextBytes} bytes"
        }
    }

    fun requireToolName(value: String) {
        require(value.isNotBlank() && CodeModeLimits.fitsText(value)) {
            "Invalid code-mode tool name"
        }
    }

    private fun parseCalls(frame: JsonObject, tools: Set<String>, nextId: Int): WorkerReply {
        CodeModeFields.requireKeys(frame, setOf(FIELD_TYPE, FIELD_CALLS))
        val rawCalls = CodeModeFields.requiredArray(frame, FIELD_CALLS)
        CodeModeFields.ensureWorker(
            rawCalls.isNotEmpty() && rawCalls.size <= CodeModeWire.maxCallsPerBatch,
            "Code-mode worker sent an invalid call batch",
        )
        val calls = rawCalls.mapIndexed { index, element ->
            val call = CodeModeFields.requiredObject(element, "Code-mode worker sent an invalid call")
            CodeModeFields.requireKeys(call, setOf(FIELD_ID, FIELD_NAME, FIELD_ARGUMENTS))
            val id = CodeModeFields.requiredString(call, FIELD_ID)
            CodeModeFields.ensureWorker(id == (nextId + index).toString(), "Code-mode worker sent an invalid call id")
            val name = CodeModeFields.requiredString(call, FIELD_NAME)
            CodeModeFields.ensureWorker(name in tools, "Code-mode worker requested an unauthorized tool")
            val arguments = CodeModeFields.requiredObject(
                call[FIELD_ARGUMENTS],
                "Code-mode worker sent non-object tool arguments",
            )
            CodeModeCall(id, name, arguments)
        }
        return WorkerReply(calls = calls, output = null, error = null)
    }

    private fun parseCompleted(frame: JsonObject): WorkerReply {
        CodeModeFields.requireKeys(frame, setOf(FIELD_TYPE, FIELD_OUTPUT, FIELD_ERROR))
        val output = CodeModeFields.requiredString(frame, FIELD_OUTPUT).also { requireText(it, FIELD_OUTPUT) }
        val error = when (val element = frame[FIELD_ERROR]) {
            JsonNull -> null
            is JsonPrimitive -> element.takeIf(JsonPrimitive::isString)?.content
                ?: throw IOException("Code-mode worker sent an invalid error")
            else -> throw IOException("Code-mode worker sent an invalid error")
        }
        error?.let { requireText(it, "error") }
        return WorkerReply(calls = null, output = output, error = error)
    }
}
