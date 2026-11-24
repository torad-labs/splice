// NEW: gateway-no-heads. A provider the gateway serves, as the gateway reads it: what its lineup, routing and pickers
// ask of a provider, and the two calls that run a turn on it. Read from the provider alone (its config, its credential,
// its roster), never from a head block, so a splice.toml with no [heads.*] lists and serves every provider. The gateway
// depends on this and nothing behind it; the provider's runtime, books and credential owner are the implementation's.
package splice.app.provider

import io.ktor.server.application.ApplicationCall
import splice.core.model.ModelCatalog
import splice.core.topology.ChatEffortVocabularyConfig
import splice.core.usage.QuotaFull
import splice.head.GatewayTurn
import splice.usage.quota.HeadUsageSource

internal interface ServedProvider {
    /** The provider's key in splice.toml: the name its selectors carry, `claude-<key>--<model>`. */
    val key: String

    /** Its models under that prefix: the selectors, labels and windows, with the roster as the provider lists it now. */
    val catalog: ModelCatalog

    /** Which model an Anthropic tier (opus, sonnet, haiku, fable) runs on here; empty where none is declared, and a tier
     *  then runs the session's current model. */
    val tiers: Map<String, String>

    /** The model a session that names none runs on: the one declared, else the first declared or listed. */
    val pinnedModel: String?

    /** The wire dialect as splice.toml spells it, which Desktop's web search, fetch and files read. */
    val dialect: String?

    /** The effort vocabulary the provider declares for the chat dialect: the levels its models are sent and the one
     *  they run at when none is asked. A model catalog row whose endpoint names no reasoning of its own publishes
     *  these as the row's reasoning levels, so a client's picker offers the effort the wire actually takes. */
    val effortVocabulary: ChatEffortVocabularyConfig? get() = null

    /** Where the provider's plan windows are read, which the usage meter reads. */
    val usage: HeadUsageSource

    /** Whether the provider is called with a login splice holds (a ChatGPT, Grok, Kimi or Muse sign-in, or accounts added
     *  to a Claude one), as against an API key or a local server: a login provider lists all its models in a picker, a
     *  key provider only the ones declared. */
    val usesLogin: Boolean

    /** Whether the provider forwards a caller's own login rather than holding one (`claude`): a gateway turn on it runs
     *  on the accounts added to splice. */
    val forwardsClientAuth: Boolean

    /** The largest request body this provider's turns take, in bytes. */
    val maxRequestBytes: Int

    /** The client timeout a session must outlive to see a turn here end, in milliseconds
     *  (UpstreamFaultPlan.clientWallMs): a launch on the door plants the longest of its providers' (#791). Null for a
     *  provider whose turns run elsewhere and hold no wall of splice's. */
    val clientWallMs: Long? get() = null

    /** How long reading one request body may take, in milliseconds: the gateway reads a turn's body itself, to route it,
     *  and holds it to the bounds the provider's turns are held to. */
    val requestReadTimeoutMs: Long

    /** The plan window the provider reads fully used now, if one is: a reading a picker says beside the model. */
    fun quotaFull(): QuotaFull?

    /** Whether a turn can run on it now, and why not when it cannot ([ProviderLogins]). */
    suspend fun login(): ProviderLogin

    suspend fun serve(call: ApplicationCall, turn: GatewayTurn)

    suspend fun countTokens(call: ApplicationCall, turn: GatewayTurn)
}

/** The providers the gateway serves now, read when asked and never copied. */
internal fun interface ServingProviders {
    operator fun invoke(): List<ServedProvider>
}
