// PORT-OF: LoginIo.kt (writeLoginOutcome, outcomeText, apiKeyLogin, credentialConfigured,
// credentialFileConfigured, wrapperInstalled) @ eedb2539 — invariants unchanged: the terminal-and-
// install half of LoginIo, split off (LAYOUT-01) because the sign-in flows need none of it. Each
// member is the CLI's: the login receipt the /login hook reads, the masked api-key prompt, and the
// credential and wrapper presence that status, doctor, setup and add report.
package splice.app.cli.auth

import splice.client.login.LoginOutcomeFile
import splice.core.config.InstallPaths
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.config.StatePaths
import splice.core.topology.AuthKindRegistry
import splice.core.topology.ProviderConfig
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.oauth.OAuthLoginAccount
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Paths

/** The CLI's sign-in facts and prompts, held as a collaborator like LoginIo was (Kotlin style law,
 *  2026-08-15). */
internal class CliSignIn {

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
        return !value.isNullOrEmpty() && Cancellables.runCatchingCancellable {
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
