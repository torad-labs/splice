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
import splice.core.util.DaemonLog
import splice.core.util.runCatchingCancellable
import splice.core.util.str
import java.nio.file.Files
import java.nio.file.Path

public class ApiKeyAuthProvider(
    private val envVar: String,
    private val keyFile: Path? = null,
    private val envReader: (String) -> String? = System::getenv,
    // Resolved once at construction (daemon restart moves it); the FILE is re-read per call, so
    // a `splice key set` lands on the very next request without a restart. Tests inject a hermetic
    // store — the default points at the operator's real ~/.config/splice/keys.toml.
    private val keyStore: KeyStore = KeyStore(KeyStore.defaultPath(envReader)),
    /** Daemon log sink (Main.persistentLogger): writes BOTH stderr and daemon.log, which is what
     *  /mgmt/logs tails. A bare System.err.println reaches stderr ONLY, so its line never appears in
     *  the log endpoint — the failure you most want to read is the one you cannot (wall
     *  kt-no-println, 2026-07-27). Defaults to a no-op so tests need not thread it; the daemon
     *  always injects the real sink. */
    private val log: (String) -> Unit = DaemonLog::write,
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

    private fun readKeyFile(file: Path): String? =
        runCatchingCancellable {
            if (!Files.exists(file)) return@runCatchingCancellable null
            val text = Files.readString(file).trim()
            if (text.startsWith("{")) {
                json.parseToJsonElement(text).jsonObject.str("api_key")
            } else {
                text.takeIf { it.isNotEmpty() }
            }
        }.onFailure {
            log("[api-key-auth] failed to read $file: $it — treating as no key configured")
        }.getOrNull()

    private companion object {
        const val MASK_MIN = 8
        const val MASK_KEEP = 4
    }
}
