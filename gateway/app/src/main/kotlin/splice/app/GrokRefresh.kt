// NEW: the grok token-refresh HTTP call (POST grant_type=refresh_token to auth.x.ai) that
// GrokAuthProvider injects. Lives in :app so :provider-grok stays HTTP-client-agnostic and
// unit-testable with a fake refreshCall (mirrors CodexRefresh).
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.core.util.long
import splice.core.util.runCatchingCancellable
import splice.core.util.str
import splice.provider.grok.GrokOAuthEndpoints
import splice.provider.grok.GrokRefreshedTokens

// refresh failure -> null (re-prompt), with the CAUSE on stderr — a silent null left the
// operator staring at persistent 401s with zero evidence (audit 2026-07-18).
public suspend fun grokRefresh(tokenUrl: String, refreshToken: String): GrokRefreshedTokens? = runCatchingCancellable {
    val resp = grokRefreshClient.submitForm(
        url = tokenUrl,
        formParameters = Parameters.build {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            // Same env override the login flow honors — hard-coding the default looped
            // custom-client setups through invalid_grant forever.
            append("client_id", GrokOAuthEndpoints.clientId(System::getenv))
        },
    )
    if (!resp.status.isSuccess()) {
        System.err.println(
            "[grok] token refresh failed: HTTP ${resp.status.value} ${resp.bodyAsText().take(ERR_BODY_SNIPPET)}",
        )
        null
    } else {
        val obj = grokRefreshJson.parseToJsonElement(resp.bodyAsText()).jsonObject
        val access = obj.str("access_token")
        if (access == null) {
            null
        } else {
            GrokRefreshedTokens(
                accessToken = access,
                refreshToken = obj.str("refresh_token"),
                expiresIn = obj.long("expires_in"),
            )
        }
    }
}.getOrNull()

private val grokRefreshClient by lazy { HttpClient(CIO) }
private val grokRefreshJson = Json { ignoreUnknownKeys = true }

private const val ERR_BODY_SNIPPET = 200
