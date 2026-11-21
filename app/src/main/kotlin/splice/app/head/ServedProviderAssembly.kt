// NEW: gateway-no-heads. Every provider in splice.toml, assembled for the gateway from the provider's own config, with no
// head to hold it: its credential and books (the same books a head of the same identity has, BooksKeys), a runtime a
// gateway turn runs through, and the ServedProvider the gateway reads it by. A splice.toml with no [heads.*] serves
// every provider. A provider that cannot be built is left out with a line saying why; it never stops the daemon.
package splice.app.head

import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderLogins
import splice.app.provider.ServedProvider
import splice.app.provider.ServingProviders
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.head.ProviderRuntime
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

    override fun invoke(): List<ServedProvider> = served.toList()

    /** Refuses new turns on every provider and lets the running ones finish, as a head's stop does. */
    suspend fun drain() {
        runtimes.forEach { it.drain() }
    }

    private fun serve(provider: String, config: ProviderConfig) {
        val key = keys.forProvider(provider)
        // A Code Mode bridge is registered with the console under the key, one per key, so a provider a head of the
        // same key serves with it on keeps its bridge there. Its sign-in is shared either way (LoginOwners).
        if (topology.heads[key]?.provider == provider && config.codeModeEnabled) {
            log("[$provider] left to its head: its Code Mode bridge is registered under the head's key\n")
            return
        }
        val ctx = buildInputs.providerContext(
            key,
            headlessHead(provider, config),
            config,
            legacyKnobsGovern = false,
            configKey = null,
        )
        val route = managedHeadFactory.assembleRoute(ctx, registers = key !in topology.heads)
        route.runtime.start()
        runtimes += route.runtime
        served += ServedProviderRoute(provider, ctx, route, logins)
    }

    /** The head a provider's build needs, from the provider's own config alone: it names the selector prefix the gateway
     *  serves the provider under and carries the pin (its declared one, else its first declared row, else none, which
     *  the gateway allows), the tier map, the window and the standing prompt the provider declares. It is never bound. */
    private fun headlessHead(provider: String, config: ProviderConfig): HeadConfig = HeadConfig(
        provider = provider,
        port = 0,
        discoveryPrefix = "claude-$provider--",
        pinnedModel = config.pinnedModel ?: config.models.firstOrNull()?.id.orEmpty(),
        modelSlots = config.modelSlots,
        contextWindow = config.contextWindow,
        systemPrompt = config.systemPrompt,
        systemPromptFile = config.systemPromptFile,
        systemPromptMode = config.systemPromptMode,
        servedByGateway = true,
    )
}
