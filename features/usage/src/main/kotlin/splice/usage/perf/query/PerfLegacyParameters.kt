package splice.usage.perf.query

import io.ktor.http.Parameters
import io.ktor.http.parseQueryString

internal object PerfLegacyParameters {
    fun problem(parameters: Parameters, operation: String): String? {
        val accepted = if (operation == "summary") setOf("window") else turns
        return when {
            parameters.names().any { it !in accepted } -> "selector_requires_contract_1"
            parameters.entries().any { it.key != "ask" && it.value.size != 1 } -> "duplicate_parameter"
            parameters.getAll(
                "ask",
            ).orEmpty().any { parseQueryString(it).names().any { key -> key !in turns - "ask" } } ->
                "nested_selector_requires_contract_1"
            else -> null
        }
    }

    private val turns = setOf(
        "head", "since", "n", "from", "until", "model", "outcome", "session", "project", "account", "local", "compact",
        "silence_ms", "queued_ms", "resumed", "started_over", "test", "unattributed", "time_zone", "order", "usage",
        "peak", "ask",
    )
}
