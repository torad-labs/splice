package splice.core.config

/** Display-only projection. Never feed these values back into persistence or runtime configuration. */
public object ConfigRedaction {
    public const val CONFIG_SECRET_REDACTED: String = "[redacted]"
    private val sensitive = setOf(Knob.TELEMETRY_OTLP_HEADERS.key, Knob.TELEMETRY_OTLP_ENDPOINT.key)

    public fun values(values: Map<String, Any?>): Map<String, Any?> = values.mapValues { (key, value) ->
        val nonempty = value != null && value !in setOf("", "{}")
        if (key in sensitive && nonempty) CONFIG_SECRET_REDACTED else value
    }
}
