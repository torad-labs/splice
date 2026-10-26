package splice.upstream.codemode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import kotlin.math.floor

/** Model-visible Codex output: bounded native content with its status and wall-clock header outside the budget. */
public object NativeCodeModeOutput {
    public data class FormatOptions(
        val showCellOverhead: Boolean = false,
        val handlerDurationMillis: Long? = null,
    )

    public fun content(
        step: CodeModeStep,
        wallMillis: Long,
        maxOutputTokens: Int = 10_000,
        supportsOriginal: Boolean = true,
        options: FormatOptions = FormatOptions(),
    ): List<JsonObject> {
        val items = items(step).map { item -> imageDetail(item, supportsOriginal) }.toMutableList()
        val failure = (step as? CodeModeStep.Completed)?.takeUnless { it.terminated }?.error
        if (failure != null) items.add(textItem("Script error:\n$failure"))
        val header = header(step, wallMillis, options)
        return listOf(textItem(header)) + NativeCodeModeTruncation.content(items, maxOutputTokens)
    }

    private fun header(step: CodeModeStep, wallMillis: Long, options: FormatOptions): String {
        val timing = when (step) {
            is CodeModeStep.Completed -> step.timing
            is CodeModeStep.Yielded -> step.timing
            is CodeModeStep.Calls, is CodeModeStep.Notified, is CodeModeStep.WaitingForResults -> null
        }
        val host = timing?.hostDuration
        val totalMillis = options.handlerDurationMillis
        val showOverhead = options.showCellOverhead && totalMillis != null
        val wall = if (showOverhead && host != null) {
            val total = checkNotNull(totalMillis).toDouble() / NATIVE_MILLIS_PER_SECOND
            val hostSeconds = seconds(host)
            "${decimal(total, OVERHEAD_PLACES)} seconds (code-mode ${decimal(hostSeconds, OVERHEAD_PLACES)} seconds; " +
                "overhead ${decimal(total - hostSeconds, OVERHEAD_PLACES)} seconds)"
        } else {
            val hostSeconds = host?.let {
                it.seconds.toFloat() + it.nano.toFloat() / NATIVE_NANOS_PER_SECOND.toFloat()
            }
                ?: (wallMillis.toFloat() / NATIVE_MILLIS_PER_SECOND)
            val rounded = floor((hostSeconds * NATIVE_TENTHS).toDouble() + NATIVE_ROUNDING_HALF).toFloat() /
                NATIVE_TENTHS
            "${decimal(rounded.toDouble(), 1)} seconds"
        }
        return "${status(step)}\nWall time $wall\nOutput:\n"
    }

    private fun seconds(duration: Duration): Double =
        duration.seconds.toDouble() + duration.nano.toDouble() / NATIVE_NANOS_PER_SECOND

    private fun decimal(value: Double, places: Int): String {
        val rendered = BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).toPlainString()
        return if (value.toRawBits() < 0 && !rendered.startsWith('-')) "-$rendered" else rendered
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
private val NATIVE_NANOS_PER_SECOND: Long = java.util.concurrent.TimeUnit.SECONDS.toNanos(1)

// why: for a non-negative x, floor(x + 0.5) rounds half away from zero like Rust's f32::round, which Codex
// rust-v0.164.0 core/src/tools/code_mode/output.rs:27 applies to the wall time in tenths.
private const val NATIVE_ROUNDING_HALF: Double = 0.5

// why: Codex rust-v0.164.0 core/src/tools/code_mode/output.rs:64 prints the overhead header's seconds with {:.3}.
private const val OVERHEAD_PLACES = 3
