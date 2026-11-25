package splice.core.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.topology.ServerState
import splice.core.wire.HttpStatus
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** The cached external state of all providers on a server, independent of pool health. */
public fun interface UpstreamAvailability {
    public fun reading(): UpstreamStateReading
}

public sealed class UpstreamState {
    public data object Up : UpstreamState()
    public data object Starting : UpstreamState()
    public data class Off(val until: Instant? = null) : UpstreamState()
}

public data class UpstreamSignal(val state: UpstreamState, val message: String? = null)

/** A failed check advances [lastChecked] and reports [lastError] without replacing [signal]. */
public data class UpstreamStateReading(
    val signal: UpstreamSignal = UpstreamSignal(UpstreamState.Up),
    val lastError: String? = null,
    val lastChecked: Instant? = null,
)

public object UpstreamSignalParser {
    private val offUntil = Regex("""off\s+until\s+(\S+)""", RegexOption.IGNORE_CASE)
    private val rfc3339 = Regex("""\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(\.\d+)?([Zz]|[+-]\d{2}:\d{2})""")

    public fun parse(output: String): UpstreamSignal? {
        val text = output.removePrefix("\uFEFF").trim()
        return if (text.startsWith("{")) json(text) else plain(text)
    }

    private fun plain(text: String): UpstreamSignal? =
        state(text)?.let { UpstreamSignal(it) }
            ?: offUntil.matchEntire(text)?.groupValues?.get(1)?.let(::instant)
                ?.let { UpstreamSignal(UpstreamState.Off(it)) }

    private fun json(text: String): UpstreamSignal? = try {
        (Json.parseToJsonElement(text) as? JsonObject)?.let { fields ->
            val until = string(fields, "until")?.let { requireNotNull(instant(it)) }
            val message = string(fields, "message")?.takeUnless(String::isBlank)
            state(string(fields, "state"), until)?.let { UpstreamSignal(it, message) }
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun string(fields: JsonObject, key: String): String? = when (val field = fields[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> field.content.also { require(field.isString) }
        is JsonArray, is JsonObject -> throw IllegalArgumentException("expected a string")
    }

    private fun state(text: String?, until: Instant? = null): UpstreamState? = when (text?.lowercase()) {
        "up" -> UpstreamState.Up
        "starting" -> UpstreamState.Starting
        "off" -> UpstreamState.Off(until)
        else -> null
    }

    private fun instant(text: String): Instant? = if (rfc3339.matches(text)) {
        try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeException) {
            null
        }
    } else {
        null
    }
}

public data class UpstreamRefusal(val message: String, val retryAfterSeconds: Long) {
    public val status: Int get() = HttpStatus.SERVICE_UNAVAILABLE
}

public class UpstreamStatePolicy(private val config: ServerState) {
    public fun refusal(signal: UpstreamSignal, now: Instant): UpstreamRefusal? = when (val state = signal.state) {
        UpstreamState.Up -> null
        UpstreamState.Starting -> UpstreamRefusal(
            signal.message ?: config.messageStarting.ifBlank { "Upstream is starting; retry shortly." },
            config.retryAfterStarting.toLong(),
        )
        is UpstreamState.Off -> UpstreamRefusal(
            signal.message ?: offMessage(state.until),
            retryAfter(state.until, now),
        )
    }

    private fun offMessage(until: Instant?): String = config.messageOff
        .ifBlank { if (until == null) "Upstream is off." else "Upstream is off until {until}." }
        .replace("{until}", until?.toString() ?: "an unknown time")

    private fun retryAfter(until: Instant?, now: Instant): Long {
        if (until == null || !until.isAfter(now)) return config.retryAfterStarting.toLong()
        val remaining = Duration.between(now, until)
        return (remaining.seconds + if (remaining.nano == 0) 0 else 1).coerceAtLeast(1)
    }
}
