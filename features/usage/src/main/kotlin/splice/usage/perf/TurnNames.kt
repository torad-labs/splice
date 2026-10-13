// NEW: Oct 12, 2026 — the names a window's requests carry, for the menus that pick among them.
package splice.usage.perf

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.perf.PerfKeys

/** `?usage=names`: the models, accounts and sessions a window's requests name, in [TurnUsage]'s shapes and keys but
 *  without its figures, for a caller that only lists them. Requests' menus read a week of a command this way: the full
 *  fold priced every row and wrote every session's counters, 8.8 MB in 2.1 s for claude-splice's week on desk c,
 *  while the daemon held that command's other reads behind it (marlin, Oct 12). */
internal class TurnNames(rows: List<PerfRow>, reading: AccountReading) {
    private val models = linkedSetOf<String?>()
    private val accounts = linkedMapOf<String?, String?>()
    private val sessions = linkedMapOf<String?, Long?>()

    init {
        rows.forEach { row ->
            models += row.facts.model
            val account = reading.key(row.facts.account)
            val label = reading.label(row.facts.account)
            if (label != null || account !in accounts) accounts[account] = label ?: accounts[account]
            val session = if (row.fields[PerfKeys.COMPARE] == 1L) COMPARE_BUCKET else row.transcript.sessionId
            // the session's last answered request, as TurnUsage's last_model reads it: a compaction is not an answer
            val answered = row.facts.model != null && row.facts.compact != true && row.transcript.sessionId != null
            val last = sessions[session]
            sessions[session] = if (answered && row.ts >= (last ?: Long.MIN_VALUE)) row.ts else last
        }
    }

    fun json(): JsonObject = buildJsonObject {
        putJsonArray("models") { models.forEach { addJsonObject { put("key", it) } } }
        putJsonArray("accounts") {
            accounts.forEach { (key, label) ->
                addJsonObject {
                    put("key", key)
                    label?.let { put("label", it) }
                }
            }
        }
        putJsonArray("sessions") {
            sessions.forEach { (key, last) ->
                addJsonObject {
                    put("key", key)
                    put("last_model_ts_epoch_ms", last)
                }
            }
        }
    }
}
