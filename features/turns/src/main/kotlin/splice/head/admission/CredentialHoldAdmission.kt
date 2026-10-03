// NEW: a local refusal is owned by the effective credential, never by aggregate head pressure.
package splice.head.admission

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.perf.OutcomeTag
import splice.core.usage.PlanLimit
import splice.core.util.WallClock
import splice.head.HeadDeps
import splice.head.turn.OutcomeSentences
import splice.head.turn.Preparation
import splice.head.turn.TurnDriver
import splice.head.wire.ClientAnswer
import splice.head.wire.TurnTrace
import splice.upstream.Provider
import splice.upstream.credentials.AccountResetText
import splice.upstream.credentials.AccountSelection
import splice.upstream.retry.RateLimitCooldown

/** Admission owns the HTTP status until the turn drive starts. Native refusals retain their wire reply. */
internal class CredentialHoldAdmission(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val responses: AdmissionResponses,
    private val driver: TurnDriver,
    private val wallClock: WallClock,
) {
    suspend fun refuse(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: AdmittedTurn,
        trace: TurnTrace?,
        account: AccountSelection?,
    ): Boolean {
        // No hold means no early auth work. Credential failures still belong to the turn's honest ending boundary.
        if (account == null && deps.upstream.rateLimitedForMs <= 0L) return false
        val cooldown = account?.account?.cooldown ?: resolve(prepared)
        if (cooldown == null || cooldown.remainingMs() <= 0L) return false
        respond(call, prepared, admitted, trace, cooldown)
        return true
    }

    private suspend fun resolve(prepared: Preparation.Ready): RateLimitCooldown? {
        val credentials = provider.auth.credentials() ?: return null
        val headers = CredentialKey.headers(
            credentials,
            provider.extraHeaders(credentials) + prepared.built.extraHeaders,
        )
        return deps.upstream.credentialCooldown(headers, (credentials as? Credentials.ApiKey)?.header)
    }

    private suspend fun respond(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: AdmittedTurn,
        trace: TurnTrace?,
        cooldown: RateLimitCooldown,
    ) {
        val native = cooldown.rateLimitReply
        native?.let { reply -> trace?.collectedAnswer { ClientAnswer(reply.status, reply.body) } }
        val plan = cooldown.planHold.live()
        val armedMs = cooldown.remainingMs()
        val now = wallClock()
        val reset = cooldown.providerUnavailableForMs().takeIf { it > 0L }?.let { (now + it) / MILLIS_PER_SECOND }
        plan?.let { trace?.failureSentence(OutcomeSentences.planLimit(it)) }
        driver.recordLocalRefusal(
            prepared.built.meta,
            admitted.perf,
            admitted.t0,
            LocalRefusal(
                (if (plan == null) OutcomeTag.RATE_LIMITED else OutcomeTag.PLAN_LIMIT).wire,
                "provider_reset=${AccountResetText.format(reset)} gateway_hold=${armedMs}ms",
                trace,
            ),
        )
        admitted.close()
        if (native != null) {
            responses.respondProviderRateLimited(call, native)
        } else {
            val retryEpochSeconds = plan?.resetEpochSeconds ?: (now + armedMs) / MILLIS_PER_SECOND
            deps.turnQuota.forSession(prepared.built.meta.sessionId, null)?.clientHeadersRejected(retryEpochSeconds)
                ?.forEach { (name, value) -> call.response.header(name, value) }
            responses.respondRateLimited(call, message(armedMs, reset, plan), retryEpochSeconds)
        }
    }

    private fun message(armedMs: Long, reset: Long?, plan: PlanLimit?): String {
        if (plan != null) return plan.refusal()
        val waitS = (armedMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
        val base = "Rate limit exceeded. This gateway already retried upstream and is still being " +
            "limited, so it is holding new turns for ${waitS}s. Retry after that."
        if (reset == null) return base
        return "$base The upstream reports its quota window resets at " +
            "${AccountResetText.forPerson(reset)}; if this keeps happening, that is the real deadline."
    }
}
