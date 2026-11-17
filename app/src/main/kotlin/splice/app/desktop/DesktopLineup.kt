// NEW: desktop-door (docs/specs/desktop-door.md, decision 3). Which heads are in the door, read from the running heads
// at each use: a head is in it when splice holds the credential its provider is called with. A head that forwards the
// caller's own Claude login is not, since the Code tab sends no claude.ai token on this route and splice never calls
// Anthropic with a subscription login; nor is a head whose login is missing or has expired, until it is signed in again.
// Its models are each a selector, the ID the head's own /v1/models lists, in splice.toml's order of heads.
package splice.app.desktop

import splice.app.auth.API_KEY
import splice.app.control.ManagedHead
import splice.core.auth.AuthDescription
import splice.core.model.ClientWindows
import splice.core.model.ModelCatalog
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
)

/** A head that is not in the door, and why, in words that say what to do about it. */
internal data class LeftOut(val head: String, val command: String, val reason: String)

/** A head in the door: where a turn is relayed ([relay]), its catalog, the model it names for each tier and its
 *  pinned model, and the windows its sessions hold. */
internal data class DoorHead(
    val key: String,
    val relay: SharedHead,
    val catalog: ModelCatalog,
    val tiers: Map<String, String>,
    val pinned: String,
    val windows: ClientWindows?,
)

internal data class DesktopLineup(val heads: List<DoorHead>, val models: List<DoorModel>, val leftOut: List<LeftOut>) {
    /** The head and model of [selector], or null when no head in the door lists it. */
    fun find(selector: String): Pair<DoorHead, DoorModel>? {
        val model = models.firstOrNull { it.model == selector } ?: return null
        return heads.firstOrNull { it.key == model.head }?.let { it to model }
    }
}

/** The heads a lineup is read from: each running head, and the relay a turn reaches it through. */
internal fun interface RunningHeads {
    operator fun invoke(): List<Pair<ManagedHead, SharedHead>>
}

internal class DesktopHeads(private val running: RunningHeads) {
    suspend fun lineup(): DesktopLineup {
        val heads = mutableListOf<DoorHead>()
        val models = mutableListOf<DoorModel>()
        val leftOut = mutableListOf<LeftOut>()
        running().forEach { (managed, relay) ->
            val spec = managed.launchSpec ?: return@forEach
            val catalog = managed.statusline.catalog ?: return@forEach
            val why = absence(managed, relay, spec.signIn.loginCommand)
            if (why != null) {
                leftOut += LeftOut(relay.key, relay.command, why)
                return@forEach
            }
            val pinned = spec.models.pinnedModel.ifEmpty { catalog.availableModelIds().firstOrNull().orEmpty() }
            val windows = managed.statusline.clientWindows
            heads += DoorHead(relay.key, relay, catalog, spec.models.tiers.byTier, pinned, windows)
            catalog.discoveryRows().forEach { row ->
                models += DoorModel(row.id, row.displayName, relay.key, relay.command, catalog.unwrap(row.id))
            }
        }
        return DesktopLineup(heads, models, leftOut)
    }

    /** Why [managed] is not in the door, or null when it is. */
    private suspend fun absence(managed: ManagedHead, relay: SharedHead, signIn: String): String? {
        if (relay.forwardsClientAuth) {
            return "it forwards your own Claude login, which the Code tab does not send through splice; " +
                "Claude's models reach the door through a head on an Anthropic API key"
        }
        val described = Cancellables.runCatchingCancellable { managed.auth.describe() }
        val auth = described.getOrElse { failure ->
            return "its login could not be read (${SafeFailureText.render(failure)}); sign in with `$signIn`"
        }
        return when {
            signedIn(auth) -> null
            auth.kind == API_KEY -> keyless(auth)
            else -> "it is signed out; sign in with `$signIn`"
        }
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

// The field a login's description carries once its refresh has been refused for good (an invalid grant).
private const val EXPIRED_FIELD = "refresh_latched"

// The fields an API-key login's description names its key's place by: the key file it reads, or the variable it is
// stored under.
private const val KEY_FILE_FIELD = "key_file"
private const val KEY_ENV_FIELD = "env_var"
