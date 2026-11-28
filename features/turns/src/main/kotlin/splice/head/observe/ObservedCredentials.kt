package splice.head.observe

import io.ktor.http.Headers
import splice.core.client.SESSION_HEADER
import splice.upstream.transport.HeaderRedaction

/** Match turn credentials against outward metadata without exposing them through the observation port. */
public class ObservedCredentials internal constructor(headers: Headers) {
    private val words = headers.entries().flatMap { (name, values) ->
        if (!name.equals(SESSION_HEADER, ignoreCase = true) && HeaderRedaction.isSecret(name)) {
            values.flatMap { fragments(name, it) }
        } else {
            emptyList()
        }
    }.filter(String::isNotBlank).toSet()

    public fun containsIn(text: String): Boolean = words.any { it in text }

    private fun fragments(name: String, value: String): List<String> = when {
        name.contains("cookie", ignoreCase = true) ->
            listOf(value) + value.split(';').map { it.substringAfter('=', "").trim().trim('"') }
        name.contains("authorization", ignoreCase = true) ->
            listOf(value, value.substringAfter(' ', "").trim())
        else -> listOf(value)
    }
}
