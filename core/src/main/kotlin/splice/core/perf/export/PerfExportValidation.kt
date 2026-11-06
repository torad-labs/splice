package splice.core.perf.export

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URISyntaxException

/** Validation never includes supplied values in diagnostics. */
public object PerfExportValidation {
    public val signals: Set<String> = setOf("metrics", "traces", "logs")
    private val headerName = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}")
    private val forbiddenHeaders = setOf("host", "content-length", "content-type", "connection", "transfer-encoding")

    public fun protocol(value: String): Boolean = value in setOf("http/protobuf", "grpc")

    public fun endpoint(value: String): Boolean {
        if (value.isEmpty()) return true
        if (value.length > MAX_ENDPOINT || value.any { it <= ' ' }) return false
        return try {
            validUri(URI(value))
        } catch (_: URISyntaxException) {
            false
        }
    }

    private fun validUri(uri: URI): Boolean {
        val origin = uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty()
        val clean = listOf(uri.rawUserInfo, uri.rawQuery, uri.rawFragment).all { it == null }
        val port = uri.port == -1 || uri.port in 1..MAX_PORT
        return if (origin) clean && port else false
    }

    public fun headers(values: Map<String, String>): Boolean = values.size <= MAX_HEADERS &&
        values.keys.map(String::lowercase).distinct().size == values.size && values.all { (key, value) ->
            headerName.matches(key) && key.lowercase() !in forbiddenHeaders &&
                value.length <= MAX_HEADER_VALUE && value.all { it in ' '..'~' }
        }

    public fun parseHeaders(text: String): Map<String, String>? {
        if (text.length > MAX_HEADER_JSON) return null
        return try {
            (Json.parseToJsonElement(text) as? JsonObject)?.let(::stringHeaders)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun stringHeaders(value: JsonObject): Map<String, String>? {
        val strings = value.mapNotNull { (key, entry) ->
            (entry as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it }
        }.toMap()
        return strings.takeIf { it.size == value.size && headers(it) }
    }

    public fun parseSignals(text: String): Set<String>? {
        if (text.length > MAX_SIGNAL_TEXT) return null
        if (text.isBlank()) return emptySet()
        val values = text.split(',').map(String::trim)
        return values.toSet().takeIf { values.all { it in signals } && values.size == it.size }
    }
}

private const val MAX_ENDPOINT = 2_048
private const val MAX_PORT = 65_535
private const val MAX_HEADERS = 16
private const val MAX_HEADER_VALUE = 4_096
private const val MAX_HEADER_JSON = 16_384
private const val MAX_SIGNAL_TEXT = 128
