package splice.provider.codex.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.upstream.codemode.NativeCodeModeOptions

/** Native observation controls are metadata, never a second executable program. */
internal data class CodeModeNativeInvocation(
    val options: NativeCodeModeOptions,
    val cellId: String? = null,
    val terminate: Boolean = false,
    val source: String? = null,
)

internal object CodeModeNativeInvocations {
    private val json = Json
    private val execFields = setOf("yield_time_ms", "max_output_tokens")
    private val waitFields = setOf("cell_id", "yield_time_ms", "max_tokens", "terminate")

    fun exec(source: String): CodeModeNativeInvocation {
        require(source.isNotBlank()) { "exec requires non-empty JavaScript source" }
        val lines = source.split('\n', limit = 2)
        val first = lines.first().trimStart()
        if (!first.startsWith("// @exec:")) return CodeModeNativeInvocation(NativeCodeModeOptions(), source = source)
        val code = lines.getOrNull(1)
        require(!code.isNullOrBlank()) { "exec pragma requires JavaScript on subsequent lines" }
        val header = first.removePrefix("// @exec:").trim()
        val options = requireNotNull(JsonScalars.objectOrNull(json, header)) { "Invalid @exec options" }
        require(options.keys.all { it in execFields }) { "Unsupported @exec option" }
        return CodeModeNativeInvocation(options(options, "max_output_tokens"), source = code)
    }

    fun wait(arguments: String): CodeModeNativeInvocation {
        val value = requireNotNull(JsonScalars.objectOrNull(json, arguments)) { "Invalid wait arguments" }
        require(value.keys.all { it in waitFields }) { "Unsupported wait argument" }
        val id = JsonScalars.strIfString(value["cell_id"]).takeIf(String::isNotBlank)
        requireNotNull(id) { "wait requires cell_id" }
        return CodeModeNativeInvocation(options(value, "max_tokens"), id, terminate(value))
    }

    private fun terminate(value: JsonObject): Boolean {
        val supplied = value["terminate"] ?: return false
        val boolean = (supplied as? JsonPrimitive)?.takeUnless { it.isString }
            ?.let(JsonScalars::str)?.toBooleanStrictOrNull()
        return requireNotNull(boolean) { "wait terminate must be boolean" }
    }

    private fun options(value: JsonObject, tokenField: String): NativeCodeModeOptions = NativeCodeModeOptions(
        integer(value, "yield_time_ms", NATIVE_DEFAULT_YIELD_MS),
        integer(value, tokenField, NATIVE_DEFAULT_OUTPUT_TOKENS),
    )

    private fun integer(value: JsonObject, field: String, default: Long): Long {
        val supplied = value[field]?.takeUnless { it is JsonNull } ?: return default
        val number = (supplied as? JsonPrimitive)?.takeUnless { it.isString }?.let { JsonScalars.long(value, field) }
        val valid = number != null && number in 0..MAX_NATIVE_SAFE_INTEGER
        require(valid) { "Native option $field must be a non-negative safe integer" }
        return checkNotNull(number)
    }
}

// Pinned native exec and wait observation default is ten seconds.
private const val NATIVE_DEFAULT_YIELD_MS = 10_000L

// Pinned native formatter defaults to ten thousand output tokens.
private const val NATIVE_DEFAULT_OUTPUT_TOKENS = 10_000L

// JavaScript integer values must remain exactly representable across JSON and native parsing.
private const val MAX_NATIVE_SAFE_INTEGER = 9_007_199_254_740_991L
