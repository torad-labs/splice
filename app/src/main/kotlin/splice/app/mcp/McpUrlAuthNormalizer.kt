// Promotes query-string API keys to HTTP request headers for known remote MCP endpoints that
// support header authentication (such as Exa's `https://mcp.exa.ai/mcp`, which accepts `x-api-key`
// and `Authorization: Bearer`), avoiding secret leakage in synced `serverUrl`/`url` fields.
package splice.app.mcp

import splice.core.wire.HTTPS_SCHEME
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException

private const val EXA_MCP_HOST = "mcp.exa.ai"
private const val EXA_API_KEY_PARAM = "exaApiKey"
private const val EXA_API_KEY_HEADER = "x-api-key"
private const val AUTH_HEADER = "Authorization"

// why: RFC 3986 percent-encoding consumes `%` plus 2 hexadecimal digits (3 characters total).
private const val PERCENT_ESCAPE_WIDTH = 3

// why: high hex nibble shifts left by 4 bits to combine with the low hex nibble into one byte.
private const val HEX_NIBBLE_SHIFT = 4

// why: hexadecimal digits use base 16.
private const val URL_HEX_RADIX = 16

/** Normalized remote HTTP MCP endpoint with query-string credentials promoted to headers where supported. */
internal data class NormalizedHttpEndpoint(
    val url: String,
    val headers: Map<String, String>,
)

/** Rewrites supported query-parameter API keys (e.g., Exa's `exaApiKey`) into HTTP headers. */
internal class McpUrlAuthNormalizer {
    fun normalize(url: String, headers: Map<String, String>): NormalizedHttpEndpoint {
        val uri = parseHttpsUri(url)?.takeIf { it.host.equals(EXA_MCP_HOST, ignoreCase = true) }
        val extracted = uri?.rawQuery?.let { extractParam(it, EXA_API_KEY_PARAM) }
        return if (uri != null && extracted != null) {
            NormalizedHttpEndpoint(
                rebuildUrl(uri, extracted.remainingQuery),
                mergeApiKeyHeader(headers, extracted.decodedValue),
            )
        } else {
            NormalizedHttpEndpoint(url, headers)
        }
    }

    private fun parseHttpsUri(raw: String): URI? = try {
        URI(raw).takeIf { it.scheme.equals(HTTPS_SCHEME, ignoreCase = true) && !it.host.isNullOrBlank() }
    } catch (_: URISyntaxException) {
        null
    }

    private fun extractParam(rawQuery: String, targetKey: String): ExtractedQueryParam? {
        var foundValue: String? = null
        val kept = mutableListOf<String>()
        rawQuery.split('&').filter { it.isNotEmpty() }.forEach { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            if (key == targetKey && value.isNotEmpty()) {
                foundValue = percentDecodeRfc3986(value)
            } else {
                kept += part
            }
        }
        val decoded = foundValue?.takeIf { it.isNotBlank() } ?: return null
        return ExtractedQueryParam(decoded, kept.joinToString("&").ifEmpty { null })
    }

    /** RFC 3986 percent-decoding: decodes `%XX` UTF-8 escapes while preserving literal `+` characters. */
    private fun percentDecodeRfc3986(raw: String): String? {
        val bytes = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '%') {
                val decodedByte = decodeHexPair(raw, i) ?: return null
                bytes.write(decodedByte)
                i += PERCENT_ESCAPE_WIDTH
            } else {
                bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
                i += 1
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun decodeHexPair(raw: String, percentIndex: Int): Int? {
        if (percentIndex + 2 >= raw.length) return null
        val hi = raw[percentIndex + 1].digitToIntOrNull(URL_HEX_RADIX)
        val lo = raw[percentIndex + 2].digitToIntOrNull(URL_HEX_RADIX)
        return if (hi != null && lo != null) (hi shl HEX_NIBBLE_SHIFT) or lo else null
    }

    private fun rebuildUrl(uri: URI, remainingRawQuery: String?): String = buildString {
        append(uri.scheme)
        append("://")
        append(uri.rawAuthority)
        append(uri.rawPath.orEmpty())
        if (!remainingRawQuery.isNullOrEmpty()) {
            append('?')
            append(remainingRawQuery)
        }
        if (!uri.rawFragment.isNullOrEmpty()) {
            append('#')
            append(uri.rawFragment)
        }
    }

    private fun mergeApiKeyHeader(headers: Map<String, String>, apiKey: String): Map<String, String> {
        val hasExplicitAuth = headers.keys.any { key ->
            key.equals(EXA_API_KEY_HEADER, ignoreCase = true) || key.equals(AUTH_HEADER, ignoreCase = true)
        }
        if (hasExplicitAuth) return headers
        val merged = LinkedHashMap<String, String>(headers)
        merged[EXA_API_KEY_HEADER] = apiKey
        return merged
    }

    private data class ExtractedQueryParam(val decodedValue: String, val remainingQuery: String?)
}
