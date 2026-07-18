// NEW: the actual token-refresh HTTP call (POST grant_type=refresh_token) the CodexAuthProvider
// injects. Lives in :app (the wiring layer) so :provider-codex stays HTTP-client-agnostic and
// unit-testable with a fake refreshCall.
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.util.runCatchingCancellable
import splice.provider.codex.CodexOAuthEndpoints
import splice.provider.codex.RefreshedTokens

private val refreshClient by lazy { HttpClient(CIO) }
private val json = Json { ignoreUnknownKeys = true }

// refresh failure -> null (caller re-prompts), with the CAUSE on stderr — a silent null left
// the operator staring at persistent 401s with zero evidence (audit 2026-07-18).
public suspend fun codexRefresh(tokenUrl: String, refreshToken: String): RefreshedTokens? = runCatchingCancellable {
    val resp = refreshClient.submitForm(
        url = tokenUrl,
        formParameters = Parameters.build {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("client_id", CodexOAuthEndpoints.clientId(System::getenv))
        },
    )
    if (!resp.status.isSuccess()) {
        System.err.println(
            "[codex] token refresh failed: HTTP ${resp.status.value} ${resp.bodyAsText().take(ERR_BODY_SNIPPET)}",
        )
        null
    } else {
        val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val access = obj["access_token"]?.jsonPrimitive?.content
        if (access == null) {
            null
        } else {
            RefreshedTokens(
                accessToken = access,
                refreshToken = obj["refresh_token"]?.jsonPrimitive?.content,
                idToken = obj["id_token"]?.jsonPrimitive?.content,
            )
        }
    }
}.getOrNull()

private const val ERR_BODY_SNIPPET = 200
