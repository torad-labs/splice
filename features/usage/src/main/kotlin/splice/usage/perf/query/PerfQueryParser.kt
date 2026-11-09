package splice.usage.perf.query

import io.ktor.http.Parameters
import splice.usage.perf.TurnsFilterRead
import splice.usage.perf.TurnsFilterReader
import splice.usage.perf.query.PerfQueryJson.queryError

/** Shared API/CLI validation. Error text never reflects raw user input. */
public object PerfQueryParser {
    // A 200-row default keeps cursor pages bounded while allowing callers to request the validated maximum.
    public const val DEFAULT_PAGE_SIZE: Int = 200

    private val windows = mapOf(
        "1m" to MINUTE,
        "5m" to FIVE_MINUTES,
        "15m" to FIFTEEN_MINUTES,
        "1h" to HOUR,
        "5h" to FIVE_HOURS,
        "24h" to DAY,
        "7d" to WEEK,
    )
    internal val filters: Set<String> = setOf(
        "head", "model", "transport", "outcome", "session", "project", "account", "local", "compact",
        "streaming", "silence_ms", "queued_ms", "resumed", "started_over", "unattributed", "test",
    )
    private val selectors = setOf("from", "until", "window", "source", "contract", "cursor", "objective", PAGE_SIZE)
    private val sources = setOf("auto", "live", "history", "evidence", "legacy")
    private val head = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")

    public fun parse(parameters: Map<String, String>, nowMs: Long): PerfQueryRead {
        val issue = problem(parameters)
        return if (issue == null) interval(parameters, nowMs) else invalid(issue)
    }

    private fun interval(parameters: Map<String, String>, nowMs: Long): PerfQueryRead {
        val until = parameters["until"]?.toLongOrNull() ?: nowMs
        val width = windows[parameters["window"] ?: "24h"] ?: return invalid("unsupported_window")
        val from = parameters["from"]?.toLongOrNull() ?: (until - width).coerceAtLeast(0)
        return if (from < 0 || until <= from) {
            invalid("invalid_interval")
        } else {
            PerfQueryRead(
                PerfQuery(
                    from,
                    until,
                    parameters.filterKeys { it in filters },
                    source = parameters["source"] ?: "auto",
                    objectiveId = parameters["objective"],
                ),
                null,
            )
        }
    }

    private fun problem(values: Map<String, String>): String? = listOfNotNull(
        "unknown_parameter".takeIf { values.keys.any { it !in filters && it !in selectors } },
        "invalid_parameter_value".takeIf { values.any { invalidValue(it.key, it.value) } },
        "invalid_head".takeIf { values["head"]?.let { !head.matches(it) } == true },
        selectionProblem(values),
        legacyProblem(values),
    ).firstOrNull()

    private fun selectionProblem(values: Map<String, String>): String? = listOfNotNull(
        "unsupported_source".takeIf { values["source"]?.let { it !in sources } == true },
        "unsupported_contract".takeIf { values["contract"]?.let { it != "1" } == true },
        "invalid_streaming".takeIf { values["streaming"]?.let { it !in switches } == true },
        "invalid_page_size".takeIf { values[PAGE_SIZE]?.let { !validPageSize(it) } == true },
    ).firstOrNull()

    private fun legacyProblem(values: Map<String, String>): String? {
        val parsed = TurnsFilterReader()(Parameters.build { values.forEach { (k, v) -> append(k, v) } })
        return if (parsed is TurnsFilterRead.Refused) "invalid_filter_or_interval" else null
    }

    private fun invalidValue(key: String, value: String): Boolean {
        val limit = if (key == "cursor") MAX_CURSOR_CHARS else MAX_VALUE
        return listOf(value.isEmpty(), value.length > limit, value.any(Char::isISOControl)).any { it }
    }

    private fun validPageSize(value: String): Boolean = value.toIntOrNull()?.let { it in 1..MAX_PAGE_SIZE } == true

    private fun invalid(code: String): PerfQueryRead = PerfQueryRead(null, queryError("invalid_query", code))
}

// Cap accepted page requests at one thousand records before cursor decoding and response allocation.
internal const val MAX_PAGE_SIZE = 1_000

// Limit opaque cursor text to 32 KiB before continuation parsing.
internal const val MAX_CURSOR_CHARS = 32_768

// Reject ordinary selector values above 512 characters before query construction.
private const val MAX_VALUE = 512
private const val PAGE_SIZE = "page_size"
private val switches = setOf("0", "1", "true", "false")

// The public 1m selector denotes sixty seconds expressed in millisecond query bounds.
private const val MINUTE = 60_000L

// The public 5m selector denotes five complete minutes expressed in millisecond query bounds.
private const val FIVE_MINUTES = 300_000L

// The public 15m selector denotes fifteen complete minutes expressed in millisecond query bounds.
private const val FIFTEEN_MINUTES = 900_000L

// The public 1h selector denotes sixty complete minutes expressed in millisecond query bounds.
private const val HOUR = 3_600_000L

// The public 5h selector denotes five complete hours expressed in millisecond query bounds.
private const val FIVE_HOURS = 18_000_000L

// The public 24h selector denotes twenty-four elapsed hours rather than a calendar-day boundary.
private const val DAY = 86_400_000L

// The public 7d selector denotes seven elapsed days rather than a calendar-week boundary.
private const val WEEK = 604_800_000L
