// Promotes query-string API keys to HTTP request headers for known remote MCP endpoints that
// support header authentication (such as Exa's `https://mcp.exa.ai/mcp`, which accepts `x-api-key`
// and `Authorization: Bearer`), avoiding secret leakage in synced `serverUrl`/`url` fields.
package splice.app.mcp

import splice.core.wire.HTTPS_SCHEME
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

private const val EXA_MCP_HOST = "mcp.exa.ai"
private const val EXA_API_KEY_PARAM = "exaApiKey"
private const val EXA_API_KEY_HEADER = "x-api-key"
private const val AUTH_HEADER = "Authorization"

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
                foundValue = decodeQueryValue(value)
            } else {
                kept += part
            }
        }
        val decoded = foundValue?.takeIf { it.isNotBlank() } ?: return null
        return ExtractedQueryParam(decoded, kept.joinToString("&").ifEmpty { null })
    }

    private fun decodeQueryValue(raw: String): String? = try {
        URLDecoder.decode(raw, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
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
