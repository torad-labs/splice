package splice.app.mods.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.head.observe.ObservedCredentials
import splice.upstream.transport.HeaderRedaction

/** Nothing that reads credentials is visible to the mod. Text that contains one is omitted as a whole. */
internal class ModEventText(env: Map<String, String>, known: Collection<String> = emptyList()) {
    @Volatile private var secrets = (known + env.filterKeys { HeaderRedaction.isSecret(it) || "LOGIN" in it }.values)
        .filter(String::isNotBlank).toSet()

    fun remember(values: Collection<String>) {
        secrets = secrets + values.filter(String::isNotBlank)
    }
    private val shape = Regex(
        """(?i)(?:[0-9a-f]{64}|(?<![\w-])(?:sk-|gh[pousr]_|github_pat_|xox[baprs]-|ya29\.)[\w.-]+|""" +
            """\bbearer\s+\S+|eyJ[\w-]+\.eyJ[\w-]+\.[\w-]+)""",
    )

    fun text(value: String, request: ObservedCredentials? = null): String =
        if (safe(value, request)) value else HeaderRedaction.REDACTED

    fun labels(values: Map<String, String>, request: ObservedCredentials? = null): JsonObject = buildJsonObject {
        for ((key, value) in values) {
            if (safe(key, request) && safe(value, request)) put(key, value)
        }
    }

    fun safe(value: String, request: ObservedCredentials? = null): Boolean =
        !shape.containsMatchIn(value) && secrets.none { it in value } && request?.containsIn(value) != true
}
