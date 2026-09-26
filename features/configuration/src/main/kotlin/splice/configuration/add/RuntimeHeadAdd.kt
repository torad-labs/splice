// NEW: the head a local model RUNTIME describes — `splice setup` hands over what rig reported about the
// model it serves, and this module renders the row and adds it through AddCommand: the same refusals
// (taken key, taken command), the same checks (parses, credential, base URL answers, models), the same
// atomic save and the same restart path as `splice add`. The wizard never writes topology tables
// itself (SetupCommand's header), so the row's shape lives here beside the catalogue's.
//
// THE PLACEHOLDER KEY. A runtime endpoint on 127.0.0.1 takes no key (rig never passes --api-key), but
// the api-key kind needs one that RESOLVES, or the credential check fails and the daemon reports the
// head unauthenticated ("any value works" — splice.example.toml's local-runtime block). It goes into
// the shared KeyStore, the keys.toml `splice key set` writes, so a daemon started from any shell finds
// it. Written only when absent: an operator's own value is never overwritten. The env var it is
// written under and the one the row names come from ONE function, AddProfiles.apiKeyEnv.
package splice.configuration.add

import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.terminal.TerminalOutput
import splice.core.topology.Dialect
import splice.core.topology.DialectWires
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.topology.TopologyLoader
import java.nio.file.Files

/** What keys.toml holds for a runtime head. Readable on purpose: an operator who opens the file sees
 *  why a key with no secret in it is there. */
private const val RUNTIME_KEY_PLACEHOLDER = "local-runtime-no-auth"

/**
 * A model a local runtime serves on this machine, as the runtime DESCRIBED it — the only facts the
 * row needs. The runtime owns every value here but [key]; an openai-chat endpoint by contract (the
 * caller refuses any other dialect before building one).
 */
public data class RuntimeHead(
    /** Provider AND head key; the wrapper command is `claude-<key>`. */
    public val key: String,
    /** The runtime call the facts came from, named in the row's comment (`rig describe bonsai-2-27b`). */
    public val describedBy: String,
    public val baseUrl: String,
    public val modelId: String,
    public val modelLabel: String,
    /** The window the runtime ADVERTISES, never the model's raw ceiling. */
    public val contextWindow: Long,
    /** QuirksConfig.reasoningEffort, which means "EMIT reasoning_effort": false for a server that
     *  rejects the field. */
    public val emitReasoningEffort: Boolean,
    /** QuirksConfig.slotAffinity: the server pins a conversation to a slot (llama-server). */
    public val slotAffinity: Boolean,
    /** The server answers any model id, so its /models list is not authoritative for the row. */
    public val anyModelId: Boolean,
)

internal class RuntimeHeadAdd(
    private val output: TerminalOutput,
    private val command: AddCommand,
) {
    private val profiles = AddProfiles()

    /** True when the head was saved and is reachable as printed, AddCommand's own answer. The
     *  placeholder this call planted is taken back when no row landed, so a refused add leaves
     *  keys.toml as it found it; a row that DID land keeps it even when the restart after it failed. */
    suspend fun add(head: RuntimeHead, env: EnvReader): Boolean {
        val envVar = profiles.apiKeyEnv(head.key)
        val store = KeyStore(KeyStorePath.defaultPath(env))
        val planted = plant(store, envVar) ?: return false
        val added = command.addDescribed(profile(head, envVar), env)
        if (planted && !landed(head.key, env)) {
            Cancellables.runCatchingCancellable { store.unset(envVar) }
                .onFailure { output.line("  $envVar placeholder left in ${store.path}: ${SafeFailureText.render(it)}") }
        }
        return added
    }

    /** True when this call wrote the placeholder, false when a value was already there, null when
     *  keys.toml could not be written — said, and the add does not run. */
    private fun plant(store: KeyStore, envVar: String): Boolean? {
        if (store.read(envVar) != null) return false
        return Cancellables.runCatchingCancellable { store.write(envVar, RUNTIME_KEY_PLACEHOLDER) }.fold(
            onSuccess = { true },
            onFailure = {
                output.line("  could not store the $envVar placeholder in ${store.path}: ${SafeFailureText.render(it)}")
                null
            },
        )
    }

    /** Asked of the file, the only witness: AddCommand's false also covers a restart that failed
     *  AFTER the save. A file that cannot be read counts as landed, so the key stays — a stray
     *  placeholder is harmless, a saved head with its key taken away is not. */
    private fun landed(key: String, env: EnvReader): Boolean = Cancellables.runCatchingCancellable {
        key in TopologyLoader.parse(Files.readString(TopologyLoader.configPath(env))).providers
    }.fold(onSuccess = { it }, onFailure = { true })

    private fun profile(head: RuntimeHead, envVar: String): AddProfile = AddProfile(
        name = head.key,
        summary = head.describedBy,
        dialect = DialectWires.name(Dialect.OPENAI_CHAT),
        authKind = API_KEY,
        baseUrl = head.baseUrl,
        headKey = head.key,
        command = "claude-${head.key}",
        models = listOf(AddModel(head.modelId, head.modelLabel, head.contextWindow)),
        providerExtra = listOf(
            "# Served on this machine; the facts are `${head.describedBy}`'s. The endpoint takes no key:",
            "# $envVar in keys.toml is a placeholder, because the api-key kind needs one that resolves.",
            "quirks = { reasoning_effort = ${head.emitReasoningEffort}, slot_affinity = ${head.slotAffinity} }",
        ),
        origin = "splice setup",
        listAuthoritative = !head.anyModelId,
    )
}
