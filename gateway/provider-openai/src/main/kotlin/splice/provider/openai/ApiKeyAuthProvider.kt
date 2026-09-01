// NEW: generic api-key auth for OpenAI-platform + any OpenAI-compatible vendor. Key from env, a
// file (bare line or {"api_key":...}), or the shared KeyStore (~/.config/splice/keys.toml) — in
// that precedence order. No refresh (api keys don't expire like OAuth); refresh() returns the
// same key. Shared by OpenAiChatProvider and an openai-platform Responses provider.
package splice.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private const val MASK_MIN = 8
private const val MASK_KEEP = 4

public class ApiKeyAuthProvider(
    private val envVar: String,
    private val keyFile: Path? = null,
    private val envReader: EnvReader = EnvReader(System::getenv),
    // Resolved once at construction (daemon restart moves it); the FILE is re-read per call, so
    // a `splice key set` lands on the very next request without a restart. Tests inject a hermetic
    // store — the default points at the operator's real ~/.config/splice/keys.toml.
    private val keyStore: KeyStore = KeyStore(KeyStorePath.defaultPath(envReader)),
    /** Daemon log sink (Main.persistentLogger): writes BOTH stderr and daemon.log, which is what
     *  /mgmt/logs tails. A bare System.err.println reaches stderr ONLY, so its line never appears in
     *  the log endpoint — the failure you most want to read is the one you cannot (wall
     *  kt-no-println, 2026-07-27). Defaults through DaemonLog to the sink Main installs; tests that
     *  need isolation inject their own sink. */
    private val log: LogSink = LogSink(DaemonLog::write),
) : RefreshableAuthProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun credentials(): Credentials? = readKey()?.let { Credentials.ApiKey(it) }

    override suspend fun refresh(): Credentials? = credentials()

    /** Non-suspend presence peek for launch-time decisions (the SessionStart advertiser is
     *  installed only while this is false). Same read chain as credentials(). */
    public fun hasKeyNow(): Boolean = readKey() != null

    override suspend fun describe(): AuthDescription {
        val key = readKey()
        return AuthDescription(
            present = key != null,
            kind = "api-key",
            fields = buildMap {
                put("env_var", envVar)
                // Path only, never contents: a file-configured head can be told its key file is the
                // fix instead of an env var that was never the mechanism.
                keyFile?.let { put("key_file", it.toString()) }
                key?.let {
                    val m = if (it.length > MASK_MIN) "${it.take(MASK_KEEP)}…${it.takeLast(MASK_KEEP)}" else "set"
                    put("api_key_masked", m)
                }
            },
        )
    }

    private fun readKey(): String? {
        envReader(envVar)?.takeIf { it.isNotEmpty() }?.let { return it }
        keyFile?.let { file -> readKeyFile(file)?.let { return it } }
        // Durable fallback: `splice key set` / `<head> login` / token-capture wrote it once and
        // every later request picks it up — the export-then-restart dance is no longer load-bearing.
        return keyStore.read(envVar)
    }

    // DIRECT read, no Files.exists pre-gate (DR-57): an exists() check — bare OR NOFOLLOW — reads
    // false for an inaccessible target, an untraversable parent, and (bare) a dangling link alike,
    // so it turned "the key file cannot be read" into silent absence and auth just stopped with no
    // line. NoSuchFileException is the only positive evidence of absence, and even it is ambiguous:
    // a DANGLING symlink throws NoSuch while the path entry exists. exists(NOFOLLOW) is used only to
    // disambiguate that caught NoSuch — never as a pre-gate. Every other failure logs before the
    // existing no-key fallthrough.
    private fun readKeyFile(file: Path): String? =
        Cancellables.runCatchingCancellable {
            val text = Files.readString(file).trim()
            if (text.startsWith("{")) {
                JsonScalars.str(json.parseToJsonElement(text).jsonObject, "api_key")
            } else {
                text.takeIf { it.isNotEmpty() }
            }
        }.getOrElse { failure ->
            val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                !Files.exists(file, LinkOption.NOFOLLOW_LINKS)
            if (!genuinelyAbsent) {
                log(
                    "[api-key-auth] failed to read $file: ${SafeFailureText.render(failure)} — " +
                        "treating as no key configured",
                )
            }
            null
        }
}
