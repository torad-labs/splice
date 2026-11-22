// NEW: gateway-no-heads. Every provider in splice.toml, assembled for the gateway from the provider's own config, with no
// head to hold it: its credential and books (the same books a head of the same identity has, BooksKeys), a runtime a
// gateway turn runs through, and the ServedProvider the gateway reads it by. A splice.toml with no [heads.*] serves
// every provider. A provider that cannot be built is left out with a line saying why; it never stops the daemon.
package splice.app.head

import splice.app.head.books.BooksKeys
import splice.app.provider.AntigravityProvider
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderLogins
import splice.app.provider.ServedProvider
import splice.app.provider.ServingProviders
import splice.core.config.ConfigService
import splice.core.config.PROVIDER_LAYER_PREFIX
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.topology.gateway.GatewayProviderRoute
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.head.ProviderRuntime
import splice.provider.antigravity.AgyModels
import splice.provider.antigravity.AgyRead
import splice.provider.antigravity.AntigravityCatalog
import java.util.concurrent.CopyOnWriteArrayList

internal class ServedProviderAssembly(
    private val topology: Topology,
    private val buildInputs: HeadBuildInputs,
    private val managedHeadFactory: ManagedHeadFactory,
    private val log: LogSink,
) : ServingProviders {
    private val keys = BooksKeys(topology)
    private val logins = ProviderLogins()
    private val served = CopyOnWriteArrayList<ServedProvider>()
    private val runtimes = CopyOnWriteArrayList<ProviderRuntime>()

    @Volatile
    private var antigravity: ServedProvider? = null

    /** The model rosters the gateway needs beside the heads': one per provider, under the key its books are kept under, so
     *  a head of the same identity and the gateway read one roster and the endpoint is asked once. */
    fun rosterProviders(): Map<String, ProviderConfig> = topology.providers.entries.associate { (provider, config) ->
        val key = keys.forProvider(provider)
        key to buildInputs.effectiveProvider(key, config, legacyKnobsGovern = false)
    }

    /** Assembles and starts one runtime per provider. After the heads are assembled, since a head of the same identity
     *  builds the books first and the gateway's route is a second route over them. */
    fun open() {
        topology.providers.forEach { (provider, config) ->
            Cancellables.runCatchingCancellable { serve(provider, config) }.onFailure { failure ->
                log("[$provider] not served by the gateway: ${SafeFailureText.render(failure)}\n")
            }
        }
    }

    override fun invoke(): List<ServedProvider> = served.toList() + listOfNotNull(antigravity)

    /** desktop-antigravity: Antigravity joins the providers once [agy] says what it offers the account signed in to
     *  it, read with [config]'s limits. True when it joined, so the caller publishes the catalog again. */
    suspend fun joinAntigravity(agy: AgyModels, config: ConfigService): Boolean {
        val catalog = when (val read = agy.read()) {
            is AgyRead.Offered -> AntigravityCatalog().of(read.roster)
            AgyRead.SignedOut -> null.also { log("[antigravity] not served: agy is signed in to no account\n") }
            is AgyRead.Failed -> null.also { log("[antigravity] not served: ${read.reason}\n") }
        } ?: return false
        antigravity = AntigravityProvider(catalog, config)
        log("[antigravity] served: ${catalog.models.size} models from agy\n")
        return true
    }

    /** The direct routes join the heads in the daemon's drain and handover phases. */
    fun running(): List<ProviderRuntime> = runtimes.toList()

    private fun serve(provider: String, config: ProviderConfig) {
        val key = keys.forProvider(provider)
        // the provider's own overrides, apart from every head key: the gateway reads no head block
        val layer = "$PROVIDER_LAYER_PREFIX$provider"
        val ctx = buildInputs.providerContext(
            key,
            GatewayProviderRoute(provider, config).assembly,
            config,
            legacyKnobsGovern = false,
            configKey = layer,
        )
        val route = managedHeadFactory.assembleRoute(ctx, layer, registers = key !in topology.heads)
        route.runtime.start()
        runtimes += route.runtime
        served += ServedProviderRoute(provider, ctx, route, logins)
    }
}
