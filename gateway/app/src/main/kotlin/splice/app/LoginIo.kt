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
import splice.app.auth.OAuthAccountFiles
import splice.app.auth.OAuthLoginAccount
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

/** Set by the shared Gradle test task. Its presence means "you are inside the suite", and the
 *  system browser refuses rather than opening a window on the operator's desktop. A system PROPERTY
 *  rather than an env var: `System.getenv` is walled to core/config (kt-no-system-getenv), and a
 *  guard that exists only for the test JVM has no business on the layered config path anyway. */
private const val NO_SYSTEM_BROWSER = "splice.noSystemBrowser"

/** Opens a login URL; tests record the request without starting an operating-system process. */
internal fun interface BrowserOpener {
    fun open(url: String): Boolean
}

private class SystemBrowserOpener : BrowserOpener {

    /** WALL (2026-09-16). A TEST must never launch the operator's browser. SetupCommandTest
     *  constructed SetupCommand without overriding its loginHead seam, so the wizard ran a REAL
     *  grok OAuth login on every `:app:test`: it opened accounts.x.ai in the operator's Chrome,
     *  bound the loopback callback port, and then blocked in awaitCode for a code that could never
     *  arrive. For a full day that read as the DAEMON re-prompting for sign-in — the operator saw a
     *  login page appear again and again with no turn behind it — and it was the build all along.
     *  The guard is set by the shared Gradle test task, so any future test reaching this path fails
     *  loudly and names itself instead of opening a window on someone's desktop. */
    override fun open(url: String): Boolean {
        if (System.getProperty(NO_SYSTEM_BROWSER) != null) {
            error(
                "a test reached the real system browser (host=${host(url)}); inject a BrowserOpener " +
                    "fake, or override the flow's login seam (SetupCommand.loginHead)",
            )
        }
        return launch(url)
    }

    /** Host only — an authorize URL carries the PKCE challenge and state, which never belong in a
     *  failure message or a log. */
    private fun host(url: String): String =
        Cancellables.runCatchingCancellable { java.net.URI(url).host }.getOrNull() ?: "unknown"

    private fun launch(url: String): Boolean = Cancellables.runCatchingCancellable {
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
}

/** The shared login I/O primitives, held as a collaborator by each flow (Kotlin style law,
 *  2026-08-15): a helper used by several types is a small named class they construct, not a pair
 *  of free functions. */
internal class LoginIo(private val browser: BrowserOpener = SystemBrowserOpener()) {

    private val loginJson = Json { ignoreUnknownKeys = true }

    /** Best-effort open of a URL in the operator's default browser; false when unsupported/failed. */
    internal fun openBrowser(url: String): Boolean = browser.open(url)

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
    internal fun persistIfSignedIn(
        path: Path,
        authJson: String,
        account: OAuthLoginAccount? = null,
    ): Boolean {
        val parsed = Cancellables.runCatchingCancellable {
            loginJson.parseToJsonElement(authJson) as? JsonObject
        }.getOrNull()
        val token = parsed?.let(::accessTokenOf)
        if (token.isNullOrBlank()) {
            println("splice: token endpoint returned no access token — NOT signed in, nothing written")
            return false
        }
        val target = Cancellables.runCatchingCancellable {
            if (account == null || account.primary) {
                writeCredentialFile(path, authJson)
                path
            } else {
                persistLabeled(path, account, parsed)
            }
        }.getOrElse { failure ->
            println("splice: credential persistence error: ${SafeFailureText.render(failure)}")
            null
        } ?: return false
        println("splice: signed in — credentials written to $target")
        return true
    }

    private fun persistLabeled(path: Path, account: OAuthLoginAccount, parsed: JsonObject): Path? {
        val label = account.resolvedLabel(parsed)
        if (label.isNullOrBlank()) {
            println("splice: token endpoint returned no stable account id — NOT signed in, nothing written")
            return null
        }
        val files = OAuthAccountFiles(loginJson)
        val target = if (!account.tokenDerivedLabel) {
            files.writeLabeled(account.kind, path, label, parsed)
        } else {
            val written = files.writeTokenDerived(account.kind, path, label, parsed, account.identity)
            written.retainedQuota?.let { quota ->
                println("splice: retained quota in ${quota.fileName} — saved credentials as ${written.file.fileName}")
            }
            written.file
        }
        account.recordPersistedLabel(target.fileName.toString().removeSuffix(".json"))
        account.releaseReservation()
        return target
    }

    /** DR-172 gap (2026-09-01): the codex and grok login specs hand this the ON-DISK shape their
     *  providers read back — the token nested under "tokens" (CodexAuthJson / GrokAuthJson) — while
     *  kimi's is flat. The first cut read the top level only, so every real codex and grok exchange was
     *  refused as tokenless. A JSON null is not a token either: JsonNull is a JsonPrimitive whose
     *  content is the string "null", the same trap [errorCode] already steps around. */
    private fun accessTokenOf(obj: JsonObject): String? {
        val nested = (obj["tokens"] as? JsonObject)?.get("access_token")
        val primitive = (obj["access_token"] ?: nested) as? JsonPrimitive
        return primitive?.takeUnless { it is JsonNull }?.content
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
    internal fun writeLoginOutcome(headKey: String, ok: Boolean, account: OAuthLoginAccount? = null) {
        val persistedLabel = if (ok) account?.persistedLabel() else null
        LoginOutcomeFile.write(StatePaths().stateDir, headKey, outcomeText(headKey, ok, persistedLabel))
    }

    /** A labeled account is discovered when the head is assembled (ManagedHeadFactory), so it is on
     *  disk now and in the pool after the next restart: the receipt says that, never "using". */
    internal fun outcomeText(headKey: String, ok: Boolean, label: String?): String = when {
        ok && label != null ->
            "signed in as '$label' — saved beside the primary; it joins this head's pool after `splice restart`."
        ok -> "signed in — this session is using the new credentials."
        else -> "sign-in did not complete. Run `$headKey login` in a terminal to see why."
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
