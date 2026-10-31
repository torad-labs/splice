// The account, caller and captured routing headers of an SSE round (failure-handling slice 8).
package splice.head.transport.headers

import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.head.transport.CallerCredential
import splice.head.transport.ListValuedHeaders
import splice.head.transport.WsRoundInputs
import splice.head.usage.TurnProviderAnswers
import splice.upstream.Provider
import splice.upstream.RetryNotice

internal class SseRoundHeaders(private val provider: Provider, private val onRetry: RetryNotice) {
    /** codex-rs client.rs:2336-2340 validates HTTP echoes; WS retains the untouched raw snapshot. */
    private fun httpRoutingHeaders(inputs: WsRoundInputs): Map<String, String> {
        val holder = inputs.drive.meta.upstreamHeaders
        return holder.snapshot().filterValues { value ->
            // OkHttp's request builder accepts only TAB or printable ASCII, stricter than Ktor's check.
            val valid = value.all { it == '\t' || it in ' '..'~' }
            if (!valid && holder.claimHttpOmissionNotice()) onRetry("omitting invalid upstream turn-state HTTP header")
            valid
        }
    }

    /** The account's headers ride ON TOP of the provider's, never instead of them. */
    suspend fun send(
        inputs: WsRoundInputs,
        answers: TurnProviderAnswers,
        creds: Credentials,
    ): Map<String, String> {
        val drive = inputs.drive
        val account = drive.account?.account?.extraHeaders?.invoke(creds).orEmpty()
        val caller = CallerCredential.over(drive.turnHeaders, creds)
        val headers = provider.extraHeaders(creds) + ListValuedHeaders.over(account, caller) +
            httpRoutingHeaders(inputs)
        val key = CredentialKey.fromHeaders(
            CredentialKey.headers(creds, headers),
            (creds as? Credentials.ApiKey)?.header,
        )
        answers.sent(key)
        drive.accountLabel.observe(key, drive.meta.scope.sessionId)
        return headers
    }
}
