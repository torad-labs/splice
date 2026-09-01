// NEW: the two OS-touching primitives shared by every login flow (browser OAuth + device flow):
// openBrowser (best-effort, loopback-safe) and writeCredentialFile (atomic 0600 write, no
// world-readable window). Extracted verbatim from OAuthLoginFlow so DeviceLoginFlow reuses the
// exact same secure-write pattern instead of re-deriving it. :app is wall-exempt.
package splice.app

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.config.InstallPaths
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.config.StatePaths
import splice.core.launch.LoginOutcomeFile
import splice.core.topology.AuthKindRegistry
import splice.core.topology.ProviderConfig
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The shared login I/O primitives, held as a collaborator by each flow (Kotlin style law,
 *  2026-08-15): a helper used by several types is a small named class they construct, not a pair
 *  of free functions. */
internal class LoginIo {

    private val loginJson = Json { ignoreUnknownKeys = true }

    /** Best-effort open of a URL in the operator's default browser; false when unsupported/failed. */
    internal fun openBrowser(url: String): Boolean = Cancellables.runCatchingCancellable {
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.contains("mac") -> listOf("open", url)
            os.contains("nux") || os.contains("nix") -> listOf("xdg-open", url)
            else -> return false
        }
        ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        true
    }.getOrDefault(false)

    // Write credentials atomically at 0600 — routes to the shared primitive. This file held the
    // canonical copy SecureFile was lifted from; delegating keeps a single source of truth.
    internal fun writeCredentialFile(path: Path, content: String) {
        SecureFile.writeAtomic0600(path, content)
    }

    /** DR-172: an HTTP 200 is not a sign-in, and this is the boundary that decides the message.
     *
     *  Both flows treated `isSuccess` as the whole test and wrote whatever the body produced. A
     *  token endpoint answering 200 with `{}` therefore had an EMPTY access token persisted at
     *  0600 under "signed in — credentials written to …", and the operator walked away believing
     *  they were authenticated while every later turn failed on a credential that was never
     *  issued. Kimi already refused exactly this input — kimiAuthJsonFromTokenResponse errors on a
     *  missing access_token, with a test pinning it — so the correct behaviour was established
     *  in-repo and two providers diverged from it.
     *
     *  The check lives here rather than in each provider's toAuthJson because BOTH login flows
     *  print the same sentence from the same collaborator; per-provider guards would have to be
     *  re-derived for the next provider and for the device flow, which had the identical shape.
     *
     *  Fail-closed on an unparseable body too: a credential file whose token we cannot read is not
     *  one to call a successful login. Nothing is written on refusal — the previous credential, if
     *  any, is left intact rather than replaced by a worthless one. */
    internal fun persistIfSignedIn(path: Path, authJson: String): Boolean {
        val token = Cancellables.runCatchingCancellable {
            loginJson.parseToJsonElement(authJson).let { element ->
                (element as? JsonObject)?.get("access_token") as? JsonPrimitive
            }?.content
        }.getOrNull()
        if (token.isNullOrBlank()) {
            println("splice: token endpoint returned no access token — NOT signed in, nothing written")
            return false
        }
        writeCredentialFile(path, authJson)
        println("splice: signed in — credentials written to $path")
        return true
    }

    internal fun formHeaders(request: HttpRequestBuilder, identityHeaders: Map<String, String>) {
        request.header("Content-Type", "application/x-www-form-urlencoded")
        request.header("Accept", "application/json")
        identityHeaders.forEach { (k, v) -> request.header(k, v) }
    }

    internal fun errorCode(body: String): String = Cancellables.runCatchingCancellable {
        (loginJson.parseToJsonElement(body) as? JsonObject)?.let { obj ->
            (obj["error"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
        }
    }.getOrNull().orEmpty()

    internal fun sanitize(s: String): String = s.filter { !it.isISOControl() }.take(ERR_BODY_CAP)

    /** THE RECEIPT (2026-08-01). /login runs detached, so stdout is lost; one line on disk is
     *  the only channel the head's /login hook can read back. Written for both outcomes. */
    internal fun writeLoginOutcome(headKey: String, ok: Boolean) {
        LoginOutcomeFile.write(
            StatePaths().stateDir,
            headKey,
            if (ok) {
                "signed in — this session is using the new credentials."
            } else {
                "sign-in did not complete. Run `$headKey login` in a terminal to see why."
            },
        )
    }

    // Masked read into ~/.config/splice/keys.toml — the key never hits shell history, ps, or a
    // transcript. Live daemons pick it up on the next request; restart only refreshes status.
    // DR-97: derives from the HEAD key — the same effectiveApiKeyEnv(ctx.key) every daemon arm
    // and doctor read; a provider-key derivation stored under a var nothing reads.
    internal fun apiKeyLogin(headKey: String, provider: ProviderConfig): Boolean {
        val envVar = provider.auth.effectiveApiKeyEnv(headKey)
        val console = System.console()
        val value = when {
            console == null -> {
                println("splice: no interactive console — pipe it instead:")
                println("  printf '%s' \"\$KEY\" | splice key set $envVar --stdin")
                null
            }
            else -> console.readPassword("$headKey API key ($envVar): ")?.let { String(it).trim() }
        }
        if (value != null && value.isEmpty()) println("splice: empty key — nothing stored.")
        return !value.isNullOrEmpty() && runCatching {
            val store = KeyStore(KeyStorePath.defaultPath())
            store.write(envVar, value)
            println("$envVar stored to ${store.path} (0600) — live daemons pick it up on the next request.")
        }.onFailure { System.err.println("splice: failed to store key: ${SafeFailureText.render(it)}") }.isSuccess
    }

    /** File / env / KeyStore presence for a head whose credential SPLICE holds. */
    internal fun credentialConfigured(
        key: String,
        provider: ProviderConfig,
        envReader: EnvReader,
    ): Boolean {
        val file = provider.auth.file ?: AuthKindRegistry.defaultAuthFileFor(provider.auth.kind)
        val filePresent = file?.let { credentialFileConfigured(Paths.get(TopologyLoader.expandHome(it))) } == true
        // OAuth heads authenticate by file only; api-key heads read the effective env var (the explicit
        // auth.env OR the derived <KEY>_API_KEY default the daemon wires) so the derived path matches.
        val oauth = AuthKindRegistry.isOAuth(provider.auth.kind)
        val envVar = if (oauth) provider.auth.env else provider.auth.effectiveApiKeyEnv(key)
        val envPresent = envVar?.let { envReader(it)?.isNotBlank() } == true
        // The KeyStore is the third presence source for api-key heads — a key stored by
        // `splice key set` / `<head> login` / token capture reads as configured here too.
        val storePresent = !oauth && envVar != null &&
            KeyStore(KeyStorePath.defaultPath(envReader)).read(envVar) != null
        return filePresent || envPresent || storePresent
    }

    /** DR-70 (the DR-59 posture at CLI assembly): an UNREADABLE credential file counts as
     *  configured — intact tokens one chmod away must never re-prompt a login — said out loud.
     *  Only proven absence (NoSuch + no NOFOLLOW entry) reads as not-configured. */
    private fun credentialFileConfigured(path: java.nio.file.Path): Boolean = Cancellables
        .runCatchingCancellable { Files.getLastModifiedTime(path) }
        .exceptionOrNull()
        .let { failure ->
            val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            if (failure != null && !genuinelyAbsent) {
                println(
                    "splice: $path unreadable (${SafeFailureText.render(failure)}) — " +
                        "treating the credential as configured; fix access, not login",
                )
            }
            !genuinelyAbsent
        }

    internal fun wrapperInstalled(command: String, envReader: EnvReader): Boolean =
        Files.isSymbolicLink(InstallPaths(envReader = envReader).binDir.resolve(command))
}

private const val ERR_BODY_CAP = 300
