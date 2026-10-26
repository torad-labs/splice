package splice.upstream.codemode.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeOutput
import splice.upstream.codemode.NativeCodeModeTiming
import java.math.BigInteger
import java.time.Duration

internal object CodeModeProbeFormats {
    fun evaluate(input: JsonObject): JsonObject {
        val content = (input["content"] as? JsonArray).orEmpty().map { it as JsonObject }
        val timing =
            NativeCodeModeTiming(duration(input, "host_duration_ns"), duration(input, "result_buffered_for_ns"))
        val step = when (JsonScalars.str(input, "status")) {
            "yielded" -> CodeModeStep.Yielded(
                JsonScalars.str(input, "cell_id").orEmpty(),
                "",
                content,
                timing,
            )
            else -> CodeModeStep.Completed(
                "",
                JsonScalars.str(input, "error"),
                content,
                JsonScalars.str(input, "status") == "terminated",
                timing,
            )
        }
        val items = NativeCodeModeOutput.content(
            step,
            JsonScalars.long(input, "wall_millis") ?: 0,
            JsonScalars.int(
                input,
                "max_output_tokens",
            ) ?: splice.upstream.codemode.NativeCodeModeOptions().maxOutputTokens.toInt(),
            CodeModeProbeTools.flag(input, "supports_original", true),
            NativeCodeModeOutput.FormatOptions(
                CodeModeProbeTools.flag(input, "show_cell_overhead", false),
                JsonScalars.long(input, "handler_duration_millis"),
            ),
        )
        return buildJsonObject {
            put("content", JsonArray(items))
            put("success", step !is CodeModeStep.Completed || step.terminated || step.error == null)
        }
    }

    private fun duration(input: JsonObject, key: String): Duration? {
        val nanoseconds = JsonScalars.str(input, key)?.toBigIntegerOrNull() ?: return null
        require(nanoseconds.signum() >= 0) { "negative native duration" }
        val parts = nanoseconds.divideAndRemainder(BigInteger.valueOf(NANOS_PER_SECOND))
        return Duration.ofSeconds(parts[0].longValueExact(), parts[1].longValueExact())
    }
}

private val NANOS_PER_SECOND = java.util.concurrent.TimeUnit.SECONDS.toNanos(1)
