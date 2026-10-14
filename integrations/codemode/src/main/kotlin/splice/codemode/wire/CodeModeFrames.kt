package splice.codemode.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.codemode.CODE_MODE_FATAL_FRAME_TYPE
import splice.codemode.CodeModeFatalFrame
import splice.codemode.CodeModeFields
import splice.codemode.CodeModeWire
import splice.codemode.DESCRIPTION_RESULT_OUTPUT
import splice.codemode.FIELD_ARGUMENTS
import splice.codemode.FIELD_CALLS
import splice.codemode.FIELD_DESCRIPTIONS
import splice.codemode.FIELD_ID
import splice.codemode.FIELD_IS_ERROR
import splice.codemode.FIELD_NAME
import splice.codemode.FIELD_OUTPUT
import splice.codemode.FIELD_RESULTS
import splice.codemode.FIELD_SOURCE
import splice.codemode.FIELD_TOOLS
import splice.codemode.StreamingCodeModeWire
import splice.codemode.TOO_MANY_TOOLS
import splice.codemode.TYPE_CALLS
import splice.codemode.TYPE_COMPLETED
import splice.codemode.TYPE_READY
import splice.codemode.TYPE_RESULTS
import splice.codemode.TYPE_RESULTS_PART
import splice.codemode.TYPE_START
import splice.codemode.TYPE_WAITING_RESULTS
import splice.codemode.WorkerReply
import splice.codemode.WorkerStart
import splice.core.wire.FIELD_ERROR
import splice.core.wire.FIELD_TYPE
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.codemode.CodeModeResult
import java.io.IOException

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
        val sealField = StreamingCodeModeWire.SOURCE_SCOPE_SEAL
        val fields = setOf(FIELD_TYPE, FIELD_SOURCE, FIELD_TOOLS, FIELD_DESCRIPTIONS)
        CodeModeFields.requireKeys(frame, if (sealField in frame) fields + sealField else fields)
        val type = CodeModeFields.requiredString(frame, FIELD_TYPE)
        require(type == TYPE_START || type == StreamingCodeModeWire.START) { "Expected a code-mode start frame" }
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
        return WorkerStart(
            source,
            tools,
            descriptions,
            streaming = type == StreamingCodeModeWire.START,
            sealedGlobals = if (sealField in frame) StreamingCodeModeWire.parseSeal(frame) else emptySet(),
        )
    }

    fun parseResults(frame: JsonObject): List<CodeModeResult> {
        return parseResults(frame, TYPE_RESULTS)
    }

    fun parseResultsPart(frame: JsonObject): List<CodeModeResult> = parseResults(frame, TYPE_RESULTS_PART)

    private fun parseResults(frame: JsonObject, expectedType: String): List<CodeModeResult> {
        CodeModeFields.requireKeys(frame, setOf(FIELD_TYPE, FIELD_RESULTS))
        require(CodeModeFields.requiredString(frame, FIELD_TYPE) == expectedType) {
            "Expected a code-mode results frame"
        }
        val rawResults = CodeModeFields.requiredArray(frame, FIELD_RESULTS)
        require(rawResults.size <= CodeModeWire.maxResultsPerFrame) { "Too many code-mode results" }
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
            TYPE_WAITING_RESULTS -> parseWaitingResults(frame)
            StreamingCodeModeWire.SOURCE_WAIT_FRAME -> StreamingCodeModeWire.parseWaiting(frame)
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

    /** A callback can resume the script as soon as its result arrives; siblings remain pending. */
    fun validatePartialResultSet(expected: List<CodeModeCall>, results: List<CodeModeResult>) {
        if (expected.isEmpty() && results.isEmpty()) return
        val expectedIds = expected.map(CodeModeCall::id).toSet()
        val resultIds = results.map(CodeModeResult::id)
        require(
            expected.isNotEmpty() && resultIds.isNotEmpty() &&
                resultIds.size == resultIds.toSet().size && resultIds.all { it in expectedIds },
        ) { "Code-mode results do not match pending calls" }
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
        val required = setOf(FIELD_TYPE, FIELD_CALLS)
        val optional = setOf(FIELD_OUTPUT, FIELD_ERROR)
        CodeModeFields.ensureWorker(
            frame.keys.containsAll(required) && frame.keys.all { it in required || it in optional },
            "Code-mode protocol fields are invalid",
        )
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
        val output = optionalString(frame, FIELD_OUTPUT, "Code-mode worker sent invalid call output")
        val error = optionalString(frame, FIELD_ERROR, "Code-mode worker sent invalid call error")
        output?.let { requireText(it, FIELD_OUTPUT) }
        error?.let { requireText(it, "error") }
        return WorkerReply(calls = calls, output = output, error = error)
    }

    private fun parseCompleted(frame: JsonObject): WorkerReply {
        CodeModeFields.requireKeys(frame, setOf(FIELD_TYPE, FIELD_OUTPUT, FIELD_ERROR))
        val output = CodeModeFields.requiredString(frame, FIELD_OUTPUT).also { requireText(it, FIELD_OUTPUT) }
        val error = optionalString(frame, FIELD_ERROR, "Code-mode worker sent an invalid error")
        error?.let { requireText(it, "error") }
        return WorkerReply(calls = null, output = output, error = error)
    }

    private fun parseWaitingResults(frame: JsonObject): WorkerReply {
        CodeModeFields.requireKeys(frame, setOf(FIELD_TYPE, FIELD_OUTPUT, FIELD_ERROR))
        val output = optionalString(frame, FIELD_OUTPUT, "Code-mode worker sent invalid waiting output")
        output?.let { requireText(it, FIELD_OUTPUT) }
        val error = optionalString(frame, FIELD_ERROR, "Code-mode worker sent an invalid error")
        error?.let { requireText(it, "error") }
        return WorkerReply(calls = null, output = output, error = error, waitingForResults = true)
    }

    private fun optionalString(frame: JsonObject, field: String, failure: String): String? {
        if (field !in frame) return null
        return when (val element = frame[field]) {
            JsonNull -> null
            is JsonPrimitive -> element.takeIf(JsonPrimitive::isString)?.content ?: throw IOException(failure)
            null, is JsonObject, is JsonArray -> throw IOException(failure)
        }
    }
}
