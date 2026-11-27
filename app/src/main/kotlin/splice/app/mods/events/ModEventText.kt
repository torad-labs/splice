package splice.app.mods.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.head.observe.ObservedCredentials
import splice.upstream.transport.HeaderRedaction

/** Match request text in memory; sample live provider credentials only at output boundaries. */
internal class ModEventText(
    private val source: ModSecretSources? = null,
    private val secrets: Set<String> = emptySet(),
    private val request: ObservedCredentials? = null,
) {
    fun withRequest(credentials: ObservedCredentials?): ModEventText =
        credentials?.let { ModEventText(source, secrets, it) } ?: this

    /** Reads provider credentials. Never call from a turn, an event offer or a session lock. */
    fun snapshot(): ModEventText = source?.let { ModEventText(secrets = it.read(), request = request) } ?: this

    fun text(value: String): String = if (safe(value)) value else HeaderRedaction.REDACTED

    fun labels(values: Map<String, String>): JsonObject = buildJsonObject {
        for ((key, value) in values) {
            if (safe(key)) put(key, text(value))
        }
    }

    fun labels(values: JsonObject): JsonObject = labels(
        values.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.let { key to it.content }
        }.toMap(),
    )

    fun safe(value: String): Boolean =
        !ModCredentialShapes.pattern.containsMatchIn(value) &&
            secrets.none { it in value } && request?.containsIn(value) != true
}

private object ModCredentialShapes {
    val pattern = Regex(
        """(?i)(?:[0-9a-f]{64}|(?<![\w-])(?:sk-|gh[pousr]_|github_pat_|xox[baprs]-|ya29\.)[\w.-]+|""" +
            """AIza[\w-]{35}|xai-[\w-]{16,}|\bbearer\s+\S+|eyJ[\w-]+\.eyJ[\w-]+\.[\w-]+)""",
    )
}
