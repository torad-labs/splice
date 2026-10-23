// NEW: LAN share, phase 1 (docs/specs/lan-share.md; Marcos, 2026-10-22). A head another of the person's
// machines offers, regenerated here by `splice remote`: the same command, models and tiers, written through
// AddCommand like every other add (the same refusals, checks, atomic save, link and restart). Its provider
// is an existing schema, anthropic-passthrough on a key, pointed at the sharing machine's relay for that
// head; the key is this machine's own share token, and `forward_client_identity` carries the session id and
// wire knobs the sharing head needs. Turns run on the sharing machine's login; nothing is copied here.
package splice.configuration.add

import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.terminal.TerminalOutput
import splice.core.topology.Dialect
import splice.core.topology.DialectWires
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText

/** One head as the sharing machine described it, with the local [key] and [command] it is written under. */
public data class RemoteHead(
    /** The sharing machine's name, for the row's comment and the verb's title. */
    public val server: String,
    /** The head's key on the sharing machine. */
    public val remoteKey: String,
    public val key: String,
    public val command: String,
    /** The relay for this head: `http://<server>:<port>/share/v1/heads/<remoteKey>`. */
    public val baseUrl: String,
    /** This machine's share token on [server]. */
    public val token: String,
    /** Pinned model first, as the sharing machine pins it. */
    public val models: List<RemoteModel>,
)

/** One of a shared head's picker rows: its wire id, label, window in force and the Claude tiers naming it. */
public data class RemoteModel(
    public val id: String,
    public val label: String,
    public val contextWindow: Long,
    public val slots: List<String>,
    /** The context the sharing head holds back below [contextWindow] when it places the client's compaction, null
     *  when it holds none. This machine's head scales the relayed raw counts, so it needs the same reserve. */
    public val compactionReserve: Long? = null,
)

internal class RemoteHeadAdd(
    private val output: TerminalOutput,
    private val command: AddCommand,
) {
    private val profiles = AddProfiles()

    /** The token is stored once the add's refusals have passed and before its checks, so the credential check
     *  finds it; AddCommand's answer is returned. */
    suspend fun add(head: RemoteHead, env: EnvReader): Boolean =
        command.addDescribed(profile(head), env, TokenClaim(head.token, profiles.apiKeyEnv(head.key), env))

    /** A head imported before only needs its new token: a machine that joins again gets a fresh one, and the
     *  old one stops working on the sharing machine. */
    fun renew(key: String, token: String, env: EnvReader): Boolean = storeToken(key, token, env)

    private fun storeToken(key: String, token: String, env: EnvReader): Boolean {
        val store = KeyStore(KeyStorePath.defaultPath(env))
        val envVar = profiles.apiKeyEnv(key)
        return Cancellables.runCatchingCancellable { store.write(envVar, token) }.fold(
            onSuccess = { true },
            onFailure = {
                output.line("  could not store $envVar in ${store.path}: ${SafeFailureText.render(it)}")
                false
            },
        )
    }

    private fun profile(head: RemoteHead): AddProfile = AddProfile(
        name = head.key,
        baseUrl = head.baseUrl,
        provider = AddProviderSpec(
            dialect = DialectWires.name(Dialect.ANTHROPIC_PASSTHROUGH),
            authKind = API_KEY,
            // A real key, stored before the add runs: the checks present it, never the local-runtime placeholder.
            requiresKey = true,
            extra = listOf(
                "# Relayed to '${head.remoteKey}' on ${head.server}, which runs every turn on its own login.",
                "# The key is this machine's share token there; `splice remote` renews it.",
                "quirks = { forward_client_identity = true }",
            ),
        ),
        head = AddHeadSpec(key = head.key, command = head.command),
        labels = AddLabels(summary = "${head.remoteKey} on ${head.server}", origin = "splice remote"),
        // A splice head leaves its pinned model out of /v1/models, so its list cannot decide the rows. Tiers go in a
        // model_slots table: the sharing head may put one model behind several tiers, which a models list refuses.
        policy = AddModelPolicy(listAuthoritative = false, tierTable = true),
        models = head.models.map {
            AddModel(it.id, it.label, it.contextWindow, slots = it.slots, compactionReserve = it.compactionReserve)
        },
    )

    /** A new head's token, written only under a name nothing else here uses: no other provider reads [envVar] and
     *  the key store holds nothing under it, so an import never replaces a key it did not write. Taken back when
     *  the add writes nothing, unless a `splice key set` replaced it in between. */
    private inner class TokenClaim(
        private val token: String,
        private val envVar: String,
        env: EnvReader,
    ) : AddSideWrite {
        private val store = KeyStore(KeyStorePath.defaultPath(env))
        private var claimed = false

        override fun claim(candidate: AddCandidate): Boolean {
            val reader = candidate.topology.providers.entries.firstOrNull { (key, provider) ->
                val auth = provider.auth
                key != candidate.providerKey && auth.isApiKey && auth.effectiveApiKeyEnv(key) == envVar
            }?.key
            if (reader != null) {
                output.line("  [providers.$reader] already reads $envVar; import this head under another name")
                return false
            }
            claimed = Cancellables.runCatchingCancellable { store.placeholders.writeIfAbsent(envVar, token) }.fold(
                onSuccess = { it },
                onFailure = {
                    output.line("  could not store $envVar in ${store.path}: ${SafeFailureText.render(it)}")
                    return false
                },
            )
            if (!claimed) {
                val fix = "import this head under another name, or free the name with: splice key unset $envVar"
                output.line("  $envVar already holds a key in ${store.path}, which an import never replaces; $fix")
            }
            return claimed
        }

        override fun release() {
            if (!claimed) return
            Cancellables.runCatchingCancellable { store.placeholders.unsetIfValue(envVar, token) }
                .onFailure { output.line("  $envVar left in ${store.path}: ${SafeFailureText.render(it)}") }
        }
    }
}
