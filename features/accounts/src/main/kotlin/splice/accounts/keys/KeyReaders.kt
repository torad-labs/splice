// NEW: V4-220 item 3 — which heads read a key, and from where, split from KeyRoutes.kt
// (concentration). The daemon's own providers are asked (describe()'s `env_var` and `key_source`),
// never the topology: the answer is what each head reads now, not what its config declares.
package splice.accounts.keys

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.accounts.AccountHead

/** One head that reads a key, and which link of its read chain supplies it right now. */
internal data class KeyReader(val head: String, val source: String, val fingerprint: String? = null)

internal class KeyReaders(private val heads: Map<String, AccountHead>) {

    /** Every head whose auth reads an env-var key, grouped by that variable. */
    suspend fun byName(): Map<String, List<KeyReader>> =
        heads.values.mapNotNull { head ->
            val fields = head.auth.describe().fields
            fields["env_var"]?.let { name ->
                name to KeyReader(head.key, fields["key_source"] ?: UNKNOWN_SOURCE, fields["key_fingerprint"])
            }
        }.groupBy({ it.first }, { it.second })

    /** `{name, stored, fingerprint, first_seen_epoch_seconds, replaced, heads: [{head, source}]}`, heads sorted by key.
     *  The fingerprint and date are the key in use; replaced lists the keys before it, newest first ([KeyLedger]). */
    fun json(
        name: String,
        stored: Boolean,
        readers: List<KeyReader>,
        history: KeyHistory,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("stored", stored)
        put("fingerprint", history.current?.fingerprint)
        put("first_seen_epoch_seconds", history.current?.firstSeen)
        putJsonArray("replaced") {
            history.replaced.forEach { old ->
                addJsonObject {
                    put("fingerprint", old.fingerprint)
                    put("first_seen_epoch_seconds", old.firstSeen)
                }
            }
        }
        putJsonArray("heads") {
            readers.sortedBy { it.head }.forEach { reader ->
                addJsonObject {
                    put("head", reader.head)
                    put("source", reader.source)
                }
            }
        }
    }
}

// A head whose auth names an env var but predates `key_source`: said, not guessed.
private const val UNKNOWN_SOURCE = "unknown"
