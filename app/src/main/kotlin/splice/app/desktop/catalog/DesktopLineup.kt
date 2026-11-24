// NEW: desktop-door (docs/specs/desktop-door.md, decision 3); gateway-no-heads. Which providers are in the door, read from
// splice.toml's providers at each use: a provider is in it when splice holds the credential it is called with. One that
// forwards the caller's own Claude login is in it on the Claude accounts added to it (Nov 17, 2026): a turn through the
// door carries no Claude login, and runs on one of those accounts, never on the person's own login; with none added it
// is left out. A provider whose login is missing or has expired is left out until it is signed in again. Its models are
// each a selector, `claude-<provider>--<model id>`, in splice.toml's order of providers. No head is read: removing every
// `[heads.*]` block leaves the door serving the same models.
package splice.app.desktop.catalog

import splice.app.provider.ProviderLogin
import splice.app.provider.ServedProvider
import splice.app.provider.ServingProviders
import splice.client.desktop.GatewayDiscovery
import splice.core.model.ModelTierSuffix
import splice.core.usage.QuotaFull

/** One model in the door: its selector, its own label, its provider and the ID the provider runs it as. */
internal data class DoorModel(
    val model: String,
    val label: String,
    val provider: String,
    val upstream: String,
    /** The plan window its provider reads fully used now, if one is (V4-452): a reading, never a refusal, so the model
     *  stays in the door and a picker says it beside the name. */
    val full: QuotaFull? = null,
    /** Whether the provider found it on its endpoint's own list rather than splice.toml declaring it. */
    val discovered: Boolean = false,
)

/** A provider that is not in the door, and why, in words that say what to do about it. */
internal data class LeftOut(
    val provider: String,
    val reason: String,
    /** Whether it forwards the caller's own Claude login: it is `claude`, whose models are Claude's. */
    val forwardsClientAuth: Boolean = false,
)

/** The door now: the providers in it and their models, the providers left out, the table that reads an old selector as
 *  a provider's ([aliases]), and the model catalog published last ([catalog]), whose facts every list the door gives
 *  reads (DoorCatalog). */
internal data class DesktopLineup(
    val providers: List<ServedProvider>,
    val models: List<DoorModel>,
    val leftOut: List<LeftOut>,
    val aliases: SelectorAliases,
    val catalog: ProviderCatalog = ProviderCatalog(),
    /** desktop-antigravity: the providers only Claude Desktop runs, through its own launcher, and their models. They
     *  are in Desktop's lists alone (DoorCatalog's Desktop lists), never in a picker the door serves or on a route. */
    val desktopOnly: List<ServedProvider> = emptyList(),
    val desktopModels: List<DoorModel> = emptyList(),
) {
    private val discovery = GatewayDiscovery()

    /** The client timeout a session on the door must outlive, the longest of its providers' walls: a turn can run on
     *  any of them (#791). Null while none has one. */
    fun clientWallMs(): Long? = providers.mapNotNull { it.clientWallMs }.maxOrNull()

    /** The rows of a terminal session's picker (docs/specs/door-default.md, decision 4): every model of a provider
     *  called with a login splice holds, and of a provider on an API key or a local server the models splice.toml
     *  declares for it, or every model it lists when it declares none. Until Nov 18, 2026 the picker left every key
     *  provider out, to keep OpenRouter's ~310 listed models out of it, and a custom API added with its key and model
     *  could not be picked at all; a key provider's own list still stays out where splice.toml names the models to
     *  show. */
    val picker: List<DoorModel>
        get() = models.filter(::inPicker)

    private fun inPicker(model: DoorModel): Boolean {
        val provider = providers.firstOrNull { it.key == model.provider } ?: return false
        return provider.usesLogin || !model.discovered || models.none { it.provider == provider.key && !it.discovered }
    }

    /** The selector a session on [provider] starts on: its pinned model, or its first model when its pin is not in the
     *  door. */
    fun pinnedOf(provider: ServedProvider): String =
        provider.catalog.wrap(pinnedId(provider)).takeIf { find(it) != null }
            ?: models.firstOrNull { it.provider == provider.key }?.model.orEmpty()

    /** The model a session on [provider] that names none runs: the one splice.toml pins, else its first. */
    fun pinnedId(provider: ServedProvider): String =
        provider.pinnedModel ?: provider.catalog.availableModelIds().firstOrNull().orEmpty()

    /** The door as it stands for a caller that may use only the providers [allowed] says: those providers, their models,
     *  and the catalog rows of those models, so every list and every route read from it serves no other. */
    fun serving(allowed: ProviderSelection): DesktopLineup {
        val serving = providers.filter { allowed(it) }
        val desktop = desktopOnly.filter { allowed(it) }
        val kept = (serving + desktop).mapTo(HashSet()) { it.key }
        return copy(
            providers = serving,
            models = models.filter { it.provider in kept },
            leftOut = leftOut.filter { it.provider in kept },
            catalog = ProviderCatalog(catalog.models.filter { it.provider in kept }),
            desktopOnly = desktop,
            desktopModels = desktopModels.filter { it.provider in kept },
        )
    }

    /** The provider and model of [selector], or null when no provider in the door lists it. An old selector (a head's
     *  prefix the gateway owns, or one the person aliased) and a readable name (ReadableNames, `claude-xai--4.7`), the
     *  id Claude Desktop lists another lab's model under, find the row of the selector they stand for. */
    fun find(selector: String): Pair<ServedProvider, DoorModel>? {
        val model = models.firstOrNull { it.model == selector }
            ?: models.firstOrNull { ModelTierSuffix.strip(it.model) == selector }
            // head-catalog: the gateway lists a selector Claude Code would drop with `claude-` before it (GatewayDiscovery.idOf)
            ?: discovery.selectorOf(selector)?.let { listed -> models.firstOrNull { it.model == listed } }
            ?: return standingFor(selector)?.takeIf { it != selector }?.let(::find)
        return providers.firstOrNull { it.key == model.provider }?.let { it to model }
    }

    private fun standingFor(selector: String): String? = aliases.canonical(selector).takeIf { it != selector }
        ?: providers.firstNotNullOfOrNull { it.catalog.names.selectorOf(selector) }

    /** The desktop-only provider and model for [selector] (e.g. `claude-antigravity--gem-4-neon`), or null when none
     *  matches. Kept separate from [find] so terminal, network and team doors never route to desktop-only providers. */
    fun findDesktopOnly(selector: String): Pair<ServedProvider, DoorModel>? {
        val model = desktopModels.firstOrNull { it.model == selector }
            ?: desktopModels.firstOrNull { ModelTierSuffix.strip(it.model) == selector }
            ?: discovery.selectorOf(selector)?.let { listed -> desktopModels.firstOrNull { it.model == listed } }
            ?: desktopOnly.firstNotNullOfOrNull { it.catalog.names.selectorOf(selector) }
                ?.takeIf { it != selector }
                ?.let { canonical -> return findDesktopOnly(canonical) }
            ?: return null
        return desktopOnly.firstOrNull { it.key == model.provider }?.let { it to model }
    }
}

/** Which of the door's providers a caller may use. */
internal fun interface ProviderSelection {
    operator fun invoke(provider: ServedProvider): Boolean
}

/** The door's providers, read from the [serving] providers at each use, and the model catalog [published] for them:
 *  built from the providers signed in at the daemon's start and again when a provider's credential changes
 *  ([publish]), never at a request. [aliases] reads an old selector as a provider's. [desktopOnly] are the providers
 *  only Claude Desktop runs (desktop-antigravity). */
internal class DesktopProviders(
    private val serving: ServingProviders,
    private val desktopOnly: ServingProviders,
    private val aliases: SelectorAliases,
    private val published: PublishedCatalog,
) {
    /** The door now, with the catalog published last. */
    suspend fun lineup(): DesktopLineup = read().copy(catalog = catalog())

    /** The catalog published last; empty before the first [publish]. */
    fun catalog(): ProviderCatalog = published.current

    /** The catalog of the providers signed in now, built, written and served from here on. */
    suspend fun publish() {
        published.publish(read())
    }

    private suspend fun read(): DesktopLineup {
        val providers = mutableListOf<ServedProvider>()
        val models = mutableListOf<DoorModel>()
        val leftOut = mutableListOf<LeftOut>()
        serving().forEach { provider ->
            when (val login = provider.login()) {
                is ProviderLogin.Missing -> leftOut += LeftOut(provider.key, login.reason, provider.forwardsClientAuth)
                ProviderLogin.Ready -> {
                    providers += provider
                    models += modelsOf(provider)
                }
            }
        }
        val desktop = desktopOnly()
        return DesktopLineup(
            providers,
            models,
            leftOut,
            aliases,
            desktopOnly = desktop,
            desktopModels = desktop.flatMap(::modelsOf),
        )
    }

    private fun modelsOf(provider: ServedProvider): List<DoorModel> {
        val full = provider.quotaFull()
        val declared = provider.catalog.tierModelIds().toSet()
        return provider.catalog.discoveryRows().map { row ->
            val upstream = provider.catalog.unwrap(row.id)
            DoorModel(row.id, row.displayName, provider.key, upstream, full, discovered = upstream !in declared)
        }
    }
}
