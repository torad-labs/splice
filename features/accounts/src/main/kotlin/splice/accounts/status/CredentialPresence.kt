// NEW: whether a head's credential is configured — file, env or KeyStore — the one answer `splice
// status`, `splice doctor`, setup and add all report. Moved out of the CLI's CliSignIn (LAYOUT-01) so
// the doctor in features/diagnostics reads the same fact the other surfaces do. [output] takes the
// one line an UNREADABLE credential file raises (DR-70).
package splice.accounts.status

import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthKindRegistry
import splice.core.topology.ProviderConfig
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths

public class CredentialPresence(private val output: TerminalOutput) {

    /** File / env / KeyStore presence for a head whose credential SPLICE holds. */
    public fun configured(key: String, provider: ProviderConfig, envReader: EnvReader): Boolean {
        val file = provider.auth.file ?: AuthKindRegistry.defaultAuthFileFor(provider.auth.kind)
        val filePresent = file?.let { fileConfigured(Paths.get(TopologyLoader.expandHome(it))) } == true
        // OAuth heads authenticate by file only; api-key heads read the effective env var (the explicit
        // auth.env OR the derived <KEY>_API_KEY default the daemon wires) so the derived path matches.
        val oauth = AuthKindRegistry.isOAuth(provider.auth.kind)
        val envVar = if (oauth) provider.auth.env else provider.auth.effectiveApiKeyEnv(key)
        val envPresent = envVar?.let { envReader(it)?.isNotBlank() } == true
        if (filePresent || envPresent) return true
        // The KeyStore is the third presence source for api-key heads — a key stored by
        // `splice key set` / `<head> login` / token capture reads as configured here too. Read only
        // when nothing above answered, so its unreadable line is never said for a head the env covers.
        return !oauth && envVar != null && storeConfigured(KeyStore(KeyStorePath.defaultPath(envReader)), envVar)
    }

    /** DR-70 for the key store too (V4-299): an UNREADABLE keys.toml may still hold this head's key, so
     *  it counts as configured, said out loud; only a readable store without the key is not configured. */
    private fun storeConfigured(store: KeyStore, envVar: String): Boolean =
        when (val presence = store.presence(envVar)) {
            KeyStore.Presence.Stored -> true
            KeyStore.Presence.Absent -> false
            is KeyStore.Presence.Unreadable -> {
                output.line(
                    "splice: ${store.path} unreadable (${presence.why}): treating $envVar as configured; " +
                        "fix access, not login",
                )
                true
            }
        }

    /** DR-70 (the DR-59 posture at CLI assembly): an UNREADABLE credential file counts as
     *  configured — intact tokens one chmod away must never re-prompt a login — said out loud.
     *  Only proven absence (NoSuch + no NOFOLLOW entry) reads as not-configured. */
    private fun fileConfigured(path: Path): Boolean = Cancellables
        .runCatchingCancellable { Files.getLastModifiedTime(path) }
        .exceptionOrNull()
        .let { failure ->
            val genuinelyAbsent = failure is NoSuchFileException && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            if (failure != null && !genuinelyAbsent) {
                output.line(
                    "splice: $path unreadable (${SafeFailureText.render(failure)}): " +
                        "treating the credential as configured; fix access, not login",
                )
            }
            !genuinelyAbsent
        }
}
