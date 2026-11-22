// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 1). Antigravity as one more login provider in
// the door: its catalog is what `agy acp` offers the account signed in to it (AgyModels), so the catalog splice
// publishes lists its models with their reasoning levels in Claude Desktop's picker. A session on one of them runs
// `agy acp` itself, started by Desktop's launcher in place of Claude Code, so no turn of it comes through the door; one
// that does (a live Claude session switched to an Antigravity model) is answered with what to do instead.
package splice.app.provider

import io.ktor.server.application.ApplicationCall
import splice.core.config.ConfigService
import splice.core.model.ModelCatalog
import splice.core.usage.QuotaFull
import splice.core.wire.HttpStatus
import splice.head.GatewayTurn
import splice.share.RefusalVoice
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

/** Antigravity's models, read from agy as [catalog], under the limits [config] sets for every provider. */
internal class AntigravityProvider(override val catalog: ModelCatalog, private val config: ConfigService) :
    ServedProvider {
    override val key: String = "antigravity"
    override val tiers: Map<String, String> = emptyMap()
    override val pinnedModel: String? = catalog.pinnedModel.ifEmpty { null }
    override val dialect: String? = null
    override val usage: HeadUsageSource = HeadUsageSource { UsageView(0, 0, null) }
    override val usesLogin: Boolean = true
    override val forwardsClientAuth: Boolean = false
    override val maxRequestBytes: Int get() = config.getConfig().maxRequestBytes
    override val requestReadTimeoutMs: Long get() = config.getConfig().requestReadTimeoutMs
    private val voice = RefusalVoice("splice: ")

    override fun quotaFull(): QuotaFull? = null

    override suspend fun login(): ProviderLogin = ProviderLogin.Ready

    override suspend fun serve(call: ApplicationCall, turn: GatewayTurn) {
        voice.refuse(call, HttpStatus.BAD_REQUEST, ELSEWHERE)
    }

    override suspend fun countTokens(call: ApplicationCall, turn: GatewayTurn) {
        voice.refuse(call, HttpStatus.BAD_REQUEST, ELSEWHERE)
    }
}

private const val ELSEWHERE =
    "Antigravity models run in a session of their own. Start a new session on this model to use it."
