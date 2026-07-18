// NEW: generic api-key auth for OpenAI-platform + any OpenAI-compatible vendor. Key from env or a
// file (bare line or {"api_key":...}). No refresh (api keys don't expire like OAuth); refresh()
// returns the same key. Shared by OpenAiChatProvider and an openai-platform Responses provider.
package splice.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.runCatchingCancellable
import java.nio.file.Files
import java.nio.file.Path

public class ApiKeyAuthProvider(
    private val envVar: String,
    private val keyFile: Path? = null,
    private val envReader: (String) -> String? = System::getenv,
) : RefreshableAuthProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun credentials(): Credentials? = readKey()?.let { Credentials.ApiKey(it) }

    override suspend fun refresh(): Credentials? = credentials()

    override suspend fun describe(): AuthDescription {
        val key = readKey()
        return AuthDescription(
            present = key != null,
            kind = "api-key",
            fields = buildMap {
                put("env_var", envVar)
                key?.let {
                    val m = if (it.length > MASK_MIN) "${it.take(MASK_KEEP)}…${it.takeLast(MASK_KEEP)}" else "set"
                    put("api_key_masked", m)
                }
            },
        )
    }

    private fun readKey(): String? {
        envReader(envVar)?.takeIf { it.isNotEmpty() }?.let { return it }
        val file = keyFile ?: return null
        return runCatchingCancellable {
            if (!Files.exists(file)) return@runCatchingCancellable null
            val text = Files.readString(file).trim()
            if (text.startsWith("{")) {
                json.parseToJsonElement(text).jsonObject["api_key"]?.jsonPrimitive?.content
            } else {
                text.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    private companion object {
        const val MASK_MIN = 8
        const val MASK_KEEP = 4
    }
}
