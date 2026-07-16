// NEW: the grok token-refresh HTTP call (POST grant_type=refresh_token to auth.x.ai) that
// GrokAuthProvider injects. Lives in :app so :provider-grok stays HTTP-client-agnostic and
// unit-testable with a fake refreshCall (mirrors CodexRefresh).
package splice.app

import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.provider.grok.GrokOAuthEndpoints
import splice.provider.grok.GrokRefreshedTokens

@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException") // refresh failure -> null (re-prompt)
public suspend fun grokRefresh(tokenUrl: String, refreshToken: String): GrokRefreshedTokens? = try {
    val resp = grokRefreshClient.submitForm(
        url = tokenUrl,
        formParameters = Parameters.build {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("client_id", GrokOAuthEndpoints.DEFAULT_CLIENT_ID)
        },
    )
    if (!resp.status.isSuccess()) {
        null
    } else {
        val obj = grokRefreshJson.parseToJsonElement(resp.bodyAsText()).jsonObject
        val access = obj["access_token"]?.jsonPrimitive?.content
        if (access == null) {
            null
        } else {
            GrokRefreshedTokens(
                accessToken = access,
                refreshToken = obj["refresh_token"]?.jsonPrimitive?.content,
            )
        }
    }
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    null
}

private val grokRefreshClient by lazy { io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) }
private val grokRefreshJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
