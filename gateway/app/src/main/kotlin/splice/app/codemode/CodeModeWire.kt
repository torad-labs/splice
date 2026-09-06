// NEW: bounded framed protocol between splice and its bundled JavaScript worker.
package splice.app.codemode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.spi.CodeModeCall
import splice.spi.CodeModeLimits
import splice.spi.CodeModeProtocol
import splice.spi.CodeModeResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

private const val DESCRIPTION_RESULT_OUTPUT: String = "result output"
private const val FIELD_ARGUMENTS: String = "arguments"
private const val FIELD_CALLS: String = "calls"
private const val FIELD_ERROR: String = "error"
private const val FIELD_ID: String = "id"
private const val FIELD_IS_ERROR: String = "isError"
private const val FIELD_NAME: String = "name"
private const val FIELD_OUTPUT: String = "output"
private const val FIELD_RESULTS: String = "results"
private const val FIELD_SOURCE: String = "source"
private const val FIELD_TOOLS: String = "tools"
private const val FIELD_TYPE: String = "type"
private const val TYPE_CALLS: String = "calls"
private const val TYPE_COMPLETED: String = "completed"
private const val TYPE_RESULTS: String = "results"
private const val TYPE_START: String = "start"
private const val TOO_MANY_TOOLS: String = "Too many code-mode tools"

/** Strict, bounded frames shared by the parent runtime and isolated worker. */
internal object CodeModeWire {
    const val maxFrameBytes: Int = CodeModeLimits.MAX_FRAME_BYTES
    const val maxTextBytes: Int = CodeModeLimits.MAX_TEXT_BYTES
    const val maxCallsPerBatch: Int = 8
    const val maxCallsPerCell: Int = 32
    const val maxToolCatalog: Int = 2_048

    fun write(output: DataOutputStream, frame: JsonObject) {
        val bytes = CodeModeProtocol.encodeFrame(frame)
        require(bytes.size <= maxFrameBytes) { "Code-mode frame exceeds $maxFrameBytes bytes" }
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun read(input: DataInputStream): JsonObject {
        val size = readFrameSize(input)
        val bytes = ByteArray(size)
        readFrameBytes(input, bytes)
        return parseFrame(bytes)
    }

    private fun readFrameSize(input: DataInputStream): Int {
        val size = try {
            input.readInt()
        } catch (error: IOException) {
            throw IOException("Code-mode worker closed its protocol stream", error)
        }
        if (size !in 1..maxFrameBytes) throw IOException("Code-mode worker sent an invalid frame length")
        return size
    }

    private fun readFrameBytes(input: DataInputStream, bytes: ByteArray) {
        try {
            input.readFully(bytes)
        } catch (error: IOException) {
            throw IOException("Code-mode worker closed an incomplete protocol frame", error)
        }
    }

    private fun parseFrame(bytes: ByteArray): JsonObject {
        val element = try {
            CodeModeJson.codec.parseToJsonElement(bytes.decodeToString())
        } catch (error: IllegalArgumentException) {
            throw IOException("Code-mode worker sent invalid JSON", error)
        }
        return element as? JsonObject ?: throw IOException("Code-mode worker sent a non-object frame")
    }

    fun startFrame(source: String, tools: Set<String>): JsonObject {
        CodeModeFrames.requireText(source, FIELD_SOURCE)
        require(tools.size <= maxToolCatalog) { TOO_MANY_TOOLS }
        tools.forEach(CodeModeFrames::requireToolName)
        return buildJsonObject {
            put(FIELD_TYPE, TYPE_START)
            put(FIELD_SOURCE, source)
            put(FIELD_TOOLS, buildJsonArray { tools.sorted().forEach(::add) })
        }
    }

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
    fun parseStart(frame: JsonObject): WorkerStart {
        requireKeys(frame, setOf(FIELD_TYPE, FIELD_SOURCE, FIELD_TOOLS))
        require(requiredString(frame, FIELD_TYPE) == TYPE_START) { "Expected a code-mode start frame" }
        val source = requiredString(frame, FIELD_SOURCE).also { requireText(it, FIELD_SOURCE) }
        val rawTools = requiredArray(frame, FIELD_TOOLS)
        require(rawTools.size <= CodeModeWire.maxToolCatalog) { TOO_MANY_TOOLS }
        val tools = rawTools.map { element ->
            (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?: throw IllegalArgumentException("Code-mode tool must be a string")
        }.toSet()
        require(tools.size <= CodeModeWire.maxToolCatalog) { TOO_MANY_TOOLS }
        tools.forEach(::requireToolName)
        return WorkerStart(source, tools)
    }

    fun parseResults(frame: JsonObject): List<CodeModeResult> {
        requireKeys(frame, setOf(FIELD_TYPE, FIELD_RESULTS))
        require(requiredString(frame, FIELD_TYPE) == TYPE_RESULTS) { "Expected a code-mode results frame" }
        val rawResults = requiredArray(frame, FIELD_RESULTS)
        require(rawResults.size <= CodeModeWire.maxCallsPerBatch) { "Too many code-mode results" }
        return rawResults.map { element ->
            val result = element as? JsonObject ?: throw IllegalArgumentException("Code-mode result must be an object")
            requireKeys(result, setOf(FIELD_ID, FIELD_OUTPUT, FIELD_IS_ERROR))
            CodeModeResult(
                id = requiredString(result, FIELD_ID).also { requireText(it, "result id") },
                output = requiredString(result, FIELD_OUTPUT).also { requireText(it, DESCRIPTION_RESULT_OUTPUT) },
                isError = requiredBoolean(result, FIELD_IS_ERROR),
            )
        }
    }

    fun parseReply(frame: JsonObject, tools: Set<String>, nextId: Int): WorkerReply =
        when (requiredString(frame, FIELD_TYPE)) {
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
        requireKeys(frame, setOf(FIELD_TYPE, FIELD_CALLS))
        val rawCalls = requiredArray(frame, FIELD_CALLS)
        ensureWorker(
            rawCalls.isNotEmpty() && rawCalls.size <= CodeModeWire.maxCallsPerBatch,
            "Code-mode worker sent an invalid call batch",
        )
        val calls = rawCalls.mapIndexed { index, element ->
            val call = requiredObject(element, "Code-mode worker sent an invalid call")
            requireKeys(call, setOf(FIELD_ID, FIELD_NAME, FIELD_ARGUMENTS))
            val id = requiredString(call, FIELD_ID)
            ensureWorker(id == (nextId + index).toString(), "Code-mode worker sent an invalid call id")
            val name = requiredString(call, FIELD_NAME)
            ensureWorker(name in tools, "Code-mode worker requested an unauthorized tool")
            val arguments = requiredObject(
                call[FIELD_ARGUMENTS],
                "Code-mode worker sent non-object tool arguments",
            )
            CodeModeCall(id, name, arguments)
        }
        return WorkerReply(calls = calls, output = null, error = null)
    }

    private fun parseCompleted(frame: JsonObject): WorkerReply {
        requireKeys(frame, setOf(FIELD_TYPE, FIELD_OUTPUT, FIELD_ERROR))
        val output = requiredString(frame, FIELD_OUTPUT).also { requireText(it, FIELD_OUTPUT) }
        val error = when (val element = frame[FIELD_ERROR]) {
            JsonNull -> null
            is JsonPrimitive -> element.takeIf(JsonPrimitive::isString)?.content
                ?: throw IOException("Code-mode worker sent an invalid error")
            else -> throw IOException("Code-mode worker sent an invalid error")
        }
        error?.let { requireText(it, "error") }
        return WorkerReply(calls = null, output = output, error = error)
    }

    private fun requireKeys(frame: JsonObject, expected: Set<String>) {
        if (frame.keys != expected) throw IOException("Code-mode protocol fields are invalid")
    }

    private fun ensureWorker(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
    }

    private fun requiredObject(element: JsonElement?, message: String): JsonObject =
        element as? JsonObject ?: throw IOException(message)

    private fun requiredString(frame: JsonObject, name: String): String =
        (frame[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: throw IOException("Code-mode protocol field $name must be a string")

    private fun requiredBoolean(frame: JsonObject, name: String): Boolean =
        (frame[name] as? JsonPrimitive)?.booleanOrNull
            ?: throw IOException("Code-mode protocol field $name must be a boolean")

    private fun requiredArray(frame: JsonObject, name: String): JsonArray =
        frame[name] as? JsonArray ?: throw IOException("Code-mode protocol field $name must be an array")
}

internal object CodeModeJson {
    val codec: Json = Json { explicitNulls = true }
}

internal data class WorkerStart(val source: String, val tools: Set<String>)

internal data class WorkerReply(
    val calls: List<CodeModeCall>?,
    val output: String?,
    val error: String?,
)
