package splice.upstream.codemode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import java.util.Locale
import kotlin.math.roundToInt

/** Model-visible Codex output: bounded native content with its status and wall-clock header outside the budget. */
public object NativeCodeModeOutput {
    public fun content(
        step: CodeModeStep,
        wallMillis: Long,
        maxOutputTokens: Int = 10_000,
        supportsOriginal: Boolean = true,
    ): List<JsonObject> {
        val items = items(step).map { item -> imageDetail(item, supportsOriginal) }.toMutableList()
        if (step is CodeModeStep.Completed && step.error != null) {
            items.add(textItem("Script error:\n${step.error}"))
        }
        val seconds = (wallMillis.toFloat() / NATIVE_MILLIS_PER_SECOND * NATIVE_TENTHS).roundToInt() /
            NATIVE_TENTHS
        val header = "${status(step)}\nWall time ${String.format(Locale.ROOT, "%.1f", seconds)} seconds\nOutput:\n"
        return listOf(textItem(header)) + NativeCodeModeTruncation.content(items, maxOutputTokens)
    }

    public fun textItem(value: String): JsonObject = buildJsonObject {
        put("type", "input_text")
        put("text", value)
    }

    private fun items(step: CodeModeStep): List<JsonObject> = when (step) {
        is CodeModeStep.Completed -> step.content.ifEmpty {
            step.output.takeIf(String::isNotEmpty)?.let {
                listOf(textItem(it))
            }.orEmpty()
        }
        is CodeModeStep.Yielded -> step.content.ifEmpty {
            step.output.takeIf(String::isNotEmpty)?.let {
                listOf(textItem(it))
            }.orEmpty()
        }
        is CodeModeStep.Calls -> step.content
        is CodeModeStep.Notified -> listOf(textItem(step.text))
        is CodeModeStep.WaitingForResults -> step.output?.let { listOf(textItem(it)) }.orEmpty()
    }

    private fun status(step: CodeModeStep): String = when (step) {
        is CodeModeStep.Yielded -> "Script running with cell ID ${step.cellId}"
        is CodeModeStep.Completed -> when {
            step.terminated -> "Script terminated"
            step.error != null -> "Script failed"
            else -> "Script completed"
        }
        is CodeModeStep.Calls, is CodeModeStep.WaitingForResults, is CodeModeStep.Notified ->
            error("Only a native execution observation has a script status")
    }

    private fun imageDetail(item: JsonObject, supportsOriginal: Boolean): JsonObject {
        if (JsonScalars.str(item, "type") != "input_image") return item
        val detail = JsonScalars.str(item, "detail")
        val unsupported = detail == "original" && !supportsOriginal
        return if (detail == null || unsupported) {
            JsonObject(item + ("detail" to JsonPrimitive("high")))
        } else {
            item
        }
    }
}

// why: Convert the caller's elapsed milliseconds to the seconds printed by native Codex output headers.
private const val NATIVE_MILLIS_PER_SECOND: Float = 1_000f

// why: Codex rounds the default wall-time header to one decimal place before rendering it.
private const val NATIVE_TENTHS: Float = 10f
