// NEW: gateway-no-heads. One provider as the gateway reads it (ServedProvider): its catalog, tiers, login and plan
// windows from the provider's own config and credential, and the two calls that run a turn, which go to its runtime.
package splice.app.head

import io.ktor.server.application.ApplicationCall
import splice.app.head.books.AssembledRoute
import splice.app.provider.ProviderBuild
import splice.app.provider.ProviderLogin
import splice.app.provider.ProviderLogins
import splice.app.provider.ServedProvider
import splice.app.sources.UsageStoreSource
import splice.core.auth.ClientAuthProvider
import splice.core.model.ModelCatalog
import splice.core.topology.ChatEffortVocabularyConfig
import splice.core.topology.DialectWires
import splice.core.usage.QuotaFull
import splice.head.GatewayTurn
import splice.usage.quota.HeadUsageSource

internal class ServedProviderRoute(
    override val key: String,
    private val ctx: ProviderBuild,
    private val route: AssembledRoute,
    private val logins: ProviderLogins,
) : ServedProvider {
    override val catalog: ModelCatalog get() = ctx.catalog

    override val tiers: Map<String, String> get() = ctx.providerCfg.tierModels

    override val pinnedModel: String?
        get() = ctx.providerCfg.pinnedModel ?: ctx.catalog.live().models.firstOrNull()?.id

    override val dialect: String? get() = DialectWires.name(ctx.providerCfg.dialect)

    override val effortVocabulary: ChatEffortVocabularyConfig? get() = ctx.providerCfg.quirks.chatEffortVocabulary

    override val usage: HeadUsageSource =
        UsageStoreSource(route.books.stores.usageStore, route.books.stores.quota, route.books.quota.pollers)

    override val forwardsClientAuth: Boolean get() = route.wired.auth is ClientAuthProvider

    override val usesLogin: Boolean
        get() = logins.usesLogin(forwardsClientAuth, ctx.providerCfg.auth.kind)

    override val maxRequestBytes: Int get() = ctx.cfg.current().maxRequestBytes

    override val requestReadTimeoutMs: Long get() = ctx.cfg.current().requestReadTimeoutMs

    override fun quotaFull(): QuotaFull? = route.runtime.quotaFull()

    override suspend fun login(): ProviderLogin = logins.of(
        route.wired.auth,
        forwardsClientAuth,
        route.wired.liveAccounts.map { it.label },
        ctx.faultPlan.loginCommand,
    )

    override suspend fun serve(call: ApplicationCall, turn: GatewayTurn) = route.runtime.serve(call, turn)

    override suspend fun countTokens(call: ApplicationCall, turn: GatewayTurn) = route.runtime.countTokens(call, turn)
}
