// NEW: input-wait is not script completion; the addressed cell stays open for subsequent source.
package splice.codemode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.upstream.codemode.CodeModeSourcePart

private const val SOURCE_COMPLETE = "complete"
private const val SOURCE_ERROR = "error"

internal object StreamingCodeModeWire {
    const val START = "stream_start"
    const val SOURCE_INPUT_FRAME = "input"
    const val SOURCE_WAIT_FRAME = "input_wait"

    const val SOURCE_SCOPE_SEAL = "sealedGlobals"

    fun startFrame(
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String>,
        sealed: Set<String> = emptySet(),
    ): JsonObject = JsonObject(
        completeFrame(source, tools, descriptions, sealed) + ("type" to JsonPrimitive(START)),
    )

    fun completeFrame(
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String>,
        sealed: Set<String>,
    ): JsonObject {
        // Reserve the actual seal bytes and the longer streaming type before fitting the catalog.
        val reserve = withSeal(JsonObject(emptyMap()), sealed).toString().encodeToByteArray().size + START.length
        return withSeal(CodeModeWire.startFrame(source, tools, descriptions, reserve), sealed)
    }

    fun withSeal(frame: JsonObject, sealed: Set<String>): JsonObject =
        JsonObject(frame + (SOURCE_SCOPE_SEAL to JsonArray(sealed.sorted().map(::JsonPrimitive))))

    fun parseSeal(frame: JsonObject): Set<String> {
        val names = CodeModeFields.requiredArray(frame, SOURCE_SCOPE_SEAL).map {
            requireNotNull(splice.core.util.JsonScalars.str(it)) { "Expected a sealed intrinsic name" }
        }.toSet()
        require(names.all { it in CodeModeScopeSeal.globals }) { "Unknown sealed intrinsic" }
        return names
    }

    fun inputFrame(part: CodeModeSourcePart): JsonObject = buildJsonObject {
        put("type", SOURCE_INPUT_FRAME)
        val text = when (part) {
            is CodeModeSourcePart.Delta -> part.text
            is CodeModeSourcePart.Complete -> part.text
            is CodeModeSourcePart.Failed -> ""
        }
        CodeModeFrames.requireText(text, "source delta")
        put("delta", text)
        put(SOURCE_COMPLETE, part is CodeModeSourcePart.Complete)
        put(SOURCE_ERROR, (part as? CodeModeSourcePart.Failed)?.error?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun append(frame: JsonObject, session: StreamingWorkerSession) {
        CodeModeFields.requireKeys(frame, setOf("type", "delta", SOURCE_COMPLETE, SOURCE_ERROR))
        require(CodeModeFields.requiredString(frame, "type") == SOURCE_INPUT_FRAME) { "Expected a source-input frame" }
        val text = CodeModeFields.requiredString(frame, "delta").also { CodeModeFrames.requireText(it, "source delta") }
        val error = if (frame[SOURCE_ERROR] == JsonNull) null else CodeModeFields.requiredString(frame, SOURCE_ERROR)
        error?.let { CodeModeFrames.requireText(it, "source error") }
        session.append(text, CodeModeFields.requiredBoolean(frame, SOURCE_COMPLETE), error)
    }

    fun waitingFrame(): JsonObject = buildJsonObject { put("type", SOURCE_WAIT_FRAME) }

    fun parseWaiting(frame: JsonObject): WorkerReply {
        CodeModeFields.requireKeys(frame, setOf("type"))
        return WorkerReply(null, null, null, waitingForInput = true)
    }
}
