// NEW: 2026-09-22 — WHERE an OAuth credential lives, WHAT shape reads it, and the access token
// inside it, in one place.
//
// Two callers already derived the first two independently: AddChecks.oauthPath and
// AddCredentialFile.shape. A third (`splice models`, which must present a bearer to ask a provider
// for its model list) would have made three hand-authored copies of "where does this kind keep its
// token" — the drift class this repo fails builds over. Both originals now delegate here.
//
// NO NETWORK, NO REFRESH. This reads what is on disk. A token near expiry is the daemon's business;
// a caller here reports the 401 it gets, which is the honest answer rather than a second refresh
// implementation racing the daemon's.
//
// In integrations/oauth since LAYOUT-01, beside the account files and sign-in persistence that write
// what it reads: `splice add` (features/configuration) and app's model discovery both ask it. The
// model probe's adapter (StoredModelCredentials) stays in app, because the port it implements is the
// models feature's and an integration never depends on a feature.
package splice.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.ProviderConfig
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.provider.codex.CodexCredentialShape
import splice.provider.grok.GrokCredentialShape
import splice.provider.kimi.KimiCredentialShape
import splice.provider.muse.MuseCredentialShape
import splice.topology.TopologyLoader
import splice.upstream.credentials.CredentialShape
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

public class StoredCredential(private val json: Json = Json { ignoreUnknownKeys = true }) {

    /** The credential file this provider's auth kind uses: what the operator named, else the kind's
     *  own default. Null for a kind that keeps nothing on disk (api-key, client). */
    public fun pathFor(provider: ProviderConfig): Path? =
        (provider.auth.file ?: AuthKindRegistry.defaultAuthFileFor(provider.auth.kind))
            ?.let { Paths.get(TopologyLoader.expandHome(it)) }

    /** The reader for one wire kind, or null when splice cannot judge that kind's file. */
    public fun shapeFor(kind: String): CredentialShape? = when (AuthKindRegistry.from(kind)) {
        AuthKind.ChatgptOAuth -> CodexCredentialShape()
        AuthKind.GrokOAuth -> GrokCredentialShape()
        AuthKind.KimiOAuth -> KimiCredentialShape()
        AuthKind.MuseOAuth -> MuseCredentialShape()
        AuthKind.Client, null -> null
    }

    /** The token stored for this provider that a request presents ([CredentialShape.presented] — the
     *  access token, or Muse's minted api_key), or null when there is no file, no shape for the kind,
     *  or no token inside. The value is returned, never printed — callers put it on a header. */
    public fun presentedToken(provider: ProviderConfig): String? =
        stored(provider)?.let { (shape, root) -> shape.presented(root) }?.takeIf { it.isNotBlank() }

    /** When the stored token expires, in epoch milliseconds, or null when there is no file, no shape
     *  for the kind, or no expiry the shape can read (Codex's comes from the access token's JWT `exp`). */
    public fun expiresAtMs(provider: ProviderConfig): Long? =
        stored(provider)?.let { (shape, root) -> shape.material(root)?.expiresAtMs }

    /** This provider's credential file, parsed, with the shape that reads it; null when either is absent. */
    private fun stored(provider: ProviderConfig): Pair<CredentialShape, JsonObject>? {
        // One guard, not two returns: "no file for this kind" and "no reader for this kind" are the
        // same answer to this method's question, and detekt caps a function at three exits.
        val path = pathFor(provider)
        val shape = shapeFor(provider.auth.kind)
        if (path == null || shape == null) return null
        // An unreadable or unparseable credential file is the same answer as an absent one for this
        // method's contract ("splice holds no token") — and the DIAGNOSIS is not lost, because every
        // caller reports the absence itself: `splice add` and `splice doctor` through
        // AddCredentialFile.problem, which renders the file's actual fault, and `splice models`
        // through the "splice holds none for '<key>'" sentence it prints instead of a bare 401.
        // The directive sits on the line directly above the expression: ast-grep attaches it to the
        // next node, so an explanation BELOW it detaches the suppression entirely (measured against
        // `bun tools/gate rules`, 2026-09-22; raised by the builder seat the same day).
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-22: absence is this method's whole contract; every caller names the absence itself (see above).
        val root = Cancellables.runCatchingCancellable { json.parseToJsonElement(Files.readString(path)).jsonObject }
            .getOrNull() ?: return null
        return shape to root
    }

    /** The api-key this provider authenticates with: the environment first, then splice's own key
     *  store. Null for every other auth kind — an OAuth provider's token is [presentedToken]'s answer. */
    public fun apiKey(provider: ProviderConfig, key: String, env: EnvReader): String? {
        if (!provider.auth.isApiKey) return null
        val envVar = provider.auth.effectiveApiKeyEnv(key)
        return env(envVar)?.takeIf { it.isNotBlank() } ?: KeyStore(KeyStorePath.defaultPath(env)).read(envVar)
    }

    /** The bearer to present for this provider, whichever way it authenticates. */
    public fun bearer(provider: ProviderConfig, key: String, env: EnvReader): String? =
        apiKey(provider, key, env) ?: presentedToken(provider)
}
