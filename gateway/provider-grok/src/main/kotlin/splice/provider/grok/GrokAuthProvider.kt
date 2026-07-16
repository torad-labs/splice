// NEW: xAI api-key auth (the grok single biggest missing piece per the Node inventory — codex
// had OAuth, grok had only a config path). Flat API key from env (XAI_API_KEY) or a key file
// (~/.local/share/claude-grok/auth.json, {"api_key": "..."} or a bare key line). No refresh —
// api keys don't expire like OAuth tokens; refresh() is a no-op returning the same key.
package splice.provider.grok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import java.nio.file.Files
import java.nio.file.Path

public class GrokAuthProvider(
    private val keyFile: Path?,
    private val envReader: (String) -> String? = System::getenv,
    private val envVar: String = "XAI_API_KEY",
) : RefreshableAuthProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun credentials(): Credentials? {
        val key = readKey() ?: return null
        return Credentials.ApiKey(key)
    }

    override suspend fun refresh(): Credentials? = credentials() // api keys don't refresh

    override suspend fun describe(): AuthDescription {
        val key = readKey()
        val masked = key?.let { if (it.length > MASK_MIN) "${it.take(MASK_KEEP)}…${it.takeLast(MASK_KEEP)}" else "set" }
        return AuthDescription(
            present = key != null,
            kind = "api-key",
            fields = buildMap {
                put("source", if (envReader(envVar) != null) "env:$envVar" else keyFile?.toString().orEmpty())
                masked?.let { put("api_key_masked", it) }
            },
        )
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "ReturnCount") // missing/garbled key -> null
    private fun readKey(): String? {
        envReader(envVar)?.takeIf { it.isNotEmpty() }?.let { return it }
        val file = keyFile ?: return null
        return try {
            if (!Files.exists(file)) return null
            val text = Files.readString(file).trim()
            if (text.startsWith("{")) {
                (json.parseToJsonElement(text).jsonObject["api_key"])?.jsonPrimitive?.content
            } else {
                text.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            null
        }
    }

    private companion object {
        const val MASK_MIN = 8
        const val MASK_KEEP = 4
    }
}
