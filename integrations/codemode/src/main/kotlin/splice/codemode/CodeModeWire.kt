// NEW: bounded framed protocol between splice and its bundled JavaScript worker.
package splice.codemode

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.codemode.wire.CodeModeFrames
import splice.core.wire.FIELD_ERROR
import splice.core.wire.FIELD_TYPE
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.codemode.CodeModeProtocol
import splice.upstream.codemode.CodeModeResult
import java.io.DataInputStream
import java.io.DataOutputStream

internal const val DESCRIPTION_RESULT_OUTPUT: String = "result output"
internal const val FIELD_ARGUMENTS: String = "arguments"
internal const val FIELD_CALLS: String = "calls"
internal const val FIELD_DESCRIPTIONS: String = "descriptions"
internal const val FIELD_ID: String = "id"
internal const val FIELD_IS_ERROR: String = "isError"
internal const val FIELD_NAME: String = "name"
internal const val FIELD_OUTPUT: String = "output"
internal const val FIELD_RESULTS: String = "results"
internal const val FIELD_SOURCE: String = "source"
internal const val FIELD_TOOLS: String = "tools"
internal const val TYPE_CALLS: String = "calls"
internal const val TYPE_COMPLETED: String = "completed"
internal const val TYPE_READY: String = "ready"
internal const val TYPE_RESULTS: String = "results"
internal const val TYPE_RESULTS_PART: String = "results_part"
internal const val TYPE_WAITING_RESULTS: String = "waiting_results"
internal const val TYPE_START: String = "start"
internal const val TOO_MANY_TOOLS: String = "Too many code-mode tools"
private const val DESCRIPTION_OVER_BUDGET: String =
    "(description omitted: the client tool catalog exceeded the code-mode description budget)"

/** Strict, bounded frames shared by the parent runtime and isolated worker. */
internal object CodeModeWire {
    const val maxFrameBytes: Int = CodeModeLimits.MAX_FRAME_BYTES
    const val maxTextBytes: Int = CodeModeLimits.MAX_TEXT_BYTES
    const val maxCallsPerBatch: Int = 32
    const val maxResultsPerFrame: Int = CodeModeLimits.MAX_RESULTS_PER_FRAME
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
    fun startFrame(
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String> = emptyMap(),
        reserveBytes: Int = 0,
    ): JsonObject {
        CodeModeFrames.requireText(source, FIELD_SOURCE)
        require(tools.size <= maxToolCatalog) { TOO_MANY_TOOLS }
        tools.forEach(CodeModeFrames::requireToolName)
        val names = tools.sorted()
        var budget = maxFrameBytes - reserveBytes -
            CodeModeProtocol.encodeFrame(start(source, names, names.associateWith { "" })).size
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

    /** JSON-escaped UTF-8 size of [text], excluding the surrounding quotes. */
    private fun escaped(text: String): Int = JsonPrimitive(text).toString().encodeToByteArray().size - 2

    fun resultFrame(results: List<CodeModeResult>): JsonObject {
        results.forEach { result ->
            CodeModeFrames.requireText(result.id, "result id")
            CodeModeFrames.requireText(result.output, DESCRIPTION_RESULT_OUTPUT)
        }
        return CodeModeProtocol.resultsFrame(results)
    }

    fun callsFrame(
        calls: List<CodeModeCall>,
        output: String? = null,
        error: String? = null,
    ): JsonObject = buildJsonObject {
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
        if (output != null) put(FIELD_OUTPUT, output)
        if (error != null) put(FIELD_ERROR, error)
    }

    fun fittingCallPrefix(calls: List<CodeModeCall>, output: String? = null, error: String? = null): Int {
        val maximum = calls.take(maxCallsPerCell)
        for (count in maximum.size downTo 1) {
            if (CodeModeProtocol.encodeFrame(
                    callsFrame(maximum.take(count), output, error),
                ).size <= maxFrameBytes
            ) {
                return count
            }
        }
        return 0
    }

    fun resultsFrames(results: List<CodeModeResult>): List<JsonObject> {
        if (results.isEmpty()) return listOf(resultFrame(emptyList()))
        val chunks = CodeModeProtocol.resultChunks(results, maxResultsPerFrame)
        return chunks.mapIndexed { index, chunk ->
            if (index == chunks.lastIndex) {
                CodeModeWire.resultFrame(chunk)
            } else {
                CodeModeProtocol.resultsPartFrame(chunk)
            }
        }
    }

    fun completedFrame(output: String, error: String?): JsonObject = buildJsonObject {
        put(FIELD_TYPE, TYPE_COMPLETED)
        put(FIELD_OUTPUT, output)
        put(FIELD_ERROR, error?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun waitingResultsFrame(output: String?, error: String?): JsonObject = buildJsonObject {
        put(FIELD_TYPE, TYPE_WAITING_RESULTS)
        put(FIELD_OUTPUT, output?.let(::JsonPrimitive) ?: JsonNull)
        put(FIELD_ERROR, error?.let(::JsonPrimitive) ?: JsonNull)
    }
}
