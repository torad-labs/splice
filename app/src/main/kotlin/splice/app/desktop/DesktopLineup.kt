// NEW: desktop-door (docs/specs/desktop-door.md, decision 3). Which heads are in the door, read from the running heads
// at each use: a head is in it when splice holds the credential its provider is called with. A head that forwards the
// caller's own Claude login is in it on the Claude accounts added to it (Nov 17, 2026): a turn through the door
// carries no Claude login, and the head runs it on one of those accounts, never on the person's own login
// (AddedClaudeLogins); with none added it is left out. A head whose login is missing or has expired is left out until it
// is signed in again. Its models are each a selector, the ID the head's own /v1/models lists, in splice.toml's order of
// heads.
package splice.app.desktop

import splice.app.auth.API_KEY
import splice.app.auth.claude.AddedClaudeLogins
import splice.app.control.ManagedHead
import splice.core.auth.AuthDescription
import splice.core.model.ClientWindows
import splice.core.model.ModelCatalog
import splice.core.topology.AuthKindRegistry
import splice.core.usage.QuotaFull
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.share.SharedHead

/** One model in the door: its selector, its own label, its head and that head's command, and the ID the head runs it
 *  as. */
internal data class DoorModel(
    val model: String,
    val label: String,
    val head: String,
    val command: String,
    val upstream: String,
    /** The plan window its head's provider reads fully used now, if one is (V4-452): a reading, never a refusal, so
     *  the model stays in the door and a picker says it beside the name. */
    val full: QuotaFull? = null,
    /** Whether the head found it on its endpoint's own list rather than splice.toml declaring it. */
    val discovered: Boolean = false,
)

/** A head that is not in the door, and why, in words that say what to do about it. */
internal data class LeftOut(val head: String, val command: String, val reason: String)

/** A head in the door: where a turn is relayed ([relay]), its catalog, the model it names for each tier and its
 *  pinned model, the windows its sessions hold, and whether its provider is called with a login splice holds ([login]:
 *  a ChatGPT, Grok, Kimi or Muse sign-in) rather than an API key or a local server. */
internal data class DoorHead(
    val key: String,
    val relay: SharedHead,
    val catalog: ModelCatalog,
    val tiers: Map<String, String>,
    val pinned: String,
    val windows: ClientWindows?,
    val login: Boolean = false,
)

/** The door now: the heads in it and their models, the heads left out, the provider splice.toml runs each head on
 *  ([providers], by head key), and the model catalog published last ([catalog]), whose facts every list the door gives
 *  reads (DoorCatalog). */
internal data class DesktopLineup(
    val heads: List<DoorHead>,
    val models: List<DoorModel>,
    val leftOut: List<LeftOut>,
    val providers: Map<String, String>,
    val catalog: ProviderCatalog = ProviderCatalog(),
) {
    /** The provider [head] runs on. */
    fun providerOf(head: DoorHead): String = providers.getValue(head.key)

    /** The rows of a terminal session's picker (docs/specs/door-default.md, decision 4): every model of a head called
     *  with a login splice holds, and of a head on an API key or a local server the models splice.toml declares for
     *  it, or every model it lists when it declares none. Until Nov 18, 2026 the picker left every key head out, to
     *  keep OpenRouter's ~310 listed models out of it, and a custom API added with its key and model could not be
     *  picked at all; a key head's own list still stays out where splice.toml names the models to show. */
    val picker: List<DoorModel>
        get() = models.filter(::inPicker)

    private fun inPicker(model: DoorModel): Boolean {
        val head = heads.firstOrNull { it.key == model.head } ?: return false
        return head.login || !model.discovered || models.none { it.head == head.key && !it.discovered }
    }

    /** The selector a session on [head] starts on: its pinned model, or its first model when its pin is not in the
     *  door. */
    fun pinnedOf(head: DoorHead): String =
        head.catalog.wrap(head.pinned).takeIf { find(it) != null }
            ?: models.firstOrNull { it.head == head.key }?.model.orEmpty()

    /** The head and model of [selector], or null when no head in the door lists it. A readable name (ReadableNames,
     *  `claude-xai--4.7`), the id Claude Desktop lists another lab's model under, finds the row of the selector it
     *  stands for. */
    fun find(selector: String): Pair<DoorHead, DoorModel>? {
        val model = models.firstOrNull { it.model == selector }
            ?: return heads.firstNotNullOfOrNull { it.catalog.names.selectorOf(selector) }
                ?.takeIf { it != selector }?.let(::find)
        return heads.firstOrNull { it.key == model.head }?.let { it to model }
    }
}

/** The heads a lineup is read from: each running head, and the relay a turn reaches it through. */
internal fun interface RunningHeads {
    operator fun invoke(): List<Pair<ManagedHead, SharedHead>>
}

/** The door's heads, read from the [running] heads at each use, and the model catalog [published] for them: built
 *  from the providers signed in at the daemon's start and again when a head's credential changes ([publish]), never
 *  at a request. [providers] is the provider each of splice.toml's heads runs on. */
internal class DesktopHeads(
    private val running: RunningHeads,
    private val providers: Map<String, String>,
    private val published: PublishedCatalog,
) {
    private val addedLogins = AddedClaudeLogins()

    /** The door now, with the catalog published last. */
    suspend fun lineup(): DesktopLineup = read().copy(catalog = catalog())

    /** The catalog published last; empty before the first [publish]. */
    fun catalog(): ProviderCatalog = published.current

    /** The catalog of the providers signed in now, built, written and served from here on. */
    suspend fun publish() {
        published.publish(read())
    }

    private suspend fun read(): DesktopLineup {
        val heads = mutableListOf<DoorHead>()
        val models = mutableListOf<DoorModel>()
        val leftOut = mutableListOf<LeftOut>()
        running().forEach { (managed, relay) ->
            val spec = managed.launchSpec ?: return@forEach
            val catalog = managed.statusline.catalog ?: return@forEach
            // Nov 17, 2026: a head that forwards the caller's Claude login is in the door on the accounts
            // added to it, which a turn through the door runs on, and never on the person's own login
            val read = if (relay.forwardsClientAuth) added(managed) else loginOf(managed, spec.signIn.loginCommand)
            if (read.why != null) {
                leftOut += LeftOut(relay.key, relay.command, read.why)
                return@forEach
            }
            val pinned = spec.models.pinnedModel.ifEmpty { catalog.availableModelIds().firstOrNull().orEmpty() }
            val windows = managed.statusline.clientWindows
            heads += DoorHead(relay.key, relay, catalog, spec.models.tiers.byTier, pinned, windows, read.login)
            val full = managed.head.quotaFull()
            val declared = catalog.tierModelIds().toSet()
            catalog.discoveryRows().forEach { row ->
                val upstream = catalog.unwrap(row.id)
                val discovered = upstream !in declared
                models += DoorModel(row.id, row.displayName, relay.key, relay.command, upstream, full, discovered)
            }
        }
        return DesktopLineup(heads, models, leftOut, providers.filterKeys { key -> heads.any { it.key == key } })
    }

    /** A head's login as the door reads it: a login splice holds (a sign-in it keeps, not a key), and why the head is
     *  left out, or null when it is in. */
    private suspend fun loginOf(managed: ManagedHead, signIn: String): LoginRead {
        val auth = Cancellables.runCatchingCancellable { managed.auth.describe() }.getOrElse { failure ->
            val unread = "its login could not be read (${SafeFailureText.render(failure)}); sign in with `$signIn`"
            return LoginRead(login = false, why = unread)
        }
        return LoginRead(AuthKindRegistry.isOAuth(auth.kind), absence(auth, signIn))
    }

    /** A head that forwards the caller's own Claude login, as the door reads it: in, on the accounts added to it,
     *  while it holds one; left out with none, since the person's own login never serves a turn through the door. */
    private fun added(managed: ManagedHead): LoginRead {
        val labels = managed.authSurface.accountPool?.view(null)?.accounts?.map { it.label }.orEmpty()
        return LoginRead(login = true, why = NONE_ADDED.takeIf { labels.none(addedLogins::held) })
    }

    /** Why a head is not in the door, by its login as [auth] describes it, or null when it is. */
    private fun absence(auth: AuthDescription, signIn: String): String? = when {
        signedIn(auth) -> null
        auth.kind == API_KEY -> keyless(auth)
        else -> "it is signed out; sign in with `$signIn`"
    }

    /** Why a head on an API key with none is not in the door, and how to give it one: its key file, or the key store. */
    private fun keyless(auth: AuthDescription): String {
        auth.fields[KEY_FILE_FIELD]?.let { return "its key file $it holds no key" }
        val name = auth.fields[KEY_ENV_FIELD] ?: return "it has no API key"
        return "it has no API key; set one with `printf '%s' \"\$KEY\" | splice key set $name --stdin`"
    }

    /** A login that is there and has not expired: an access token that no refresh can renew any more is gone. */
    private fun signedIn(auth: AuthDescription): Boolean = auth.present && EXPIRED_FIELD !in auth.fields
}

/** A head's login as the door reads it: whether splice holds it, and why the head is left out (null: it is in). */
private data class LoginRead(val login: Boolean, val why: String?)

// Why a head that forwards the caller's own Claude login is not in the door while no account is added to it.
private const val NONE_ADDED = "no Claude account is added to it, and the door never sends your own Claude login; " +
    "add one on splice's accounts page (`splice console`)"

// The field a login's description carries once its refresh has been refused for good (an invalid grant).
private const val EXPIRED_FIELD = "refresh_latched"

// The fields an API-key login's description names its key's place by: the key file it reads, or the variable it is
// stored under.
private const val KEY_FILE_FIELD = "key_file"
private const val KEY_ENV_FIELD = "env_var"
