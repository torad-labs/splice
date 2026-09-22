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
package splice.app.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.app.daemon.TopologyLoader
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
import splice.upstream.credentials.CredentialShape
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The wire spelling of the one non-OAuth kind that still carries a secret splice holds. */
private const val API_KEY_KIND = "api-key"

internal class StoredCredential(private val json: Json = Json { ignoreUnknownKeys = true }) {

    /** The credential file this provider's auth kind uses: what the operator named, else the kind's
     *  own default. Null for a kind that keeps nothing on disk (api-key, client). */
    fun pathFor(provider: ProviderConfig): Path? =
        (provider.auth.file ?: AuthKindRegistry.defaultAuthFileFor(provider.auth.kind))
            ?.let { Paths.get(TopologyLoader.expandHome(it)) }

    /** The reader for one wire kind, or null when splice cannot judge that kind's file. */
    fun shapeFor(kind: String): CredentialShape? = when (AuthKindRegistry.from(kind)) {
        AuthKind.ChatgptOAuth -> CodexCredentialShape()
        AuthKind.GrokOAuth -> GrokCredentialShape()
        AuthKind.KimiOAuth -> KimiCredentialShape()
        AuthKind.MuseOAuth -> MuseCredentialShape()
        AuthKind.Client, null -> null
    }

    /** The access token stored for this provider, or null when there is no file, no shape for the
     *  kind, or no token inside. The value is returned, never printed — callers put it on a header. */
    fun accessToken(provider: ProviderConfig): String? {
        val path = pathFor(provider) ?: return null
        val shape = shapeFor(provider.auth.kind) ?: return null
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-22: an unreadable or unparseable
        // credential file is the same answer as an absent one for every caller here ("splice holds no
        // token to ask with"), and the reason is already reported by AddCredentialFile.problem, which
        // exists to render it.
        val root = Cancellables.runCatchingCancellable { json.parseToJsonElement(Files.readString(path)).jsonObject }
            .getOrNull() ?: return null
        return shape.material(root)?.access?.takeIf { it.isNotBlank() }
    }

    /** The api-key this provider authenticates with: the environment first, then splice's own key
     *  store. Null for every other auth kind — an OAuth provider's token is [accessToken]'s answer. */
    fun apiKey(provider: ProviderConfig, key: String, env: EnvReader): String? {
        if (provider.auth.kind != API_KEY_KIND) return null
        val envVar = provider.auth.effectiveApiKeyEnv(key)
        return env(envVar)?.takeIf { it.isNotBlank() } ?: KeyStore(KeyStorePath.defaultPath(env)).read(envVar)
    }

    /** The bearer to present for this provider, whichever way it authenticates. */
    fun bearer(provider: ProviderConfig, key: String, env: EnvReader): String? =
        apiKey(provider, key, env) ?: accessToken(provider)
}
