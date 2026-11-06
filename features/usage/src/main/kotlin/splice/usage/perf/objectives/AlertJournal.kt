package splice.usage.perf.objectives

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Path

@Serializable
internal data class AlertJournalData(
    val states: Map<String, AlertState> = emptyMap(),
    val latest: Map<String, JsonObject> = emptyMap(),
    val events: List<JsonObject> = emptyList(),
    val deliveries: List<AlertAttempt> = emptyList(),
    val lastEvaluation: Long? = null,
    val expiredEvents: Long = 0,
    val deliveryRejected: Long = 0,
    val deliveryFailures: Long = 0,
    val enqueueFailures: Long = 0,
)

@Serializable
internal data class AlertAttempt(
    val key: String,
    val ruleId: String,
    val revision: String,
    val event: JsonObject,
    val attempts: Int = 0,
    val status: String = "pending",
    val nextAt: Long = 0,
)

/** One atomic document contains lifecycle and bounded outbox, so a failed write cannot acknowledge a fire. */
internal class AlertJournal(private val path: Path, admitted: Boolean) {
    var issue: String? = null
        private set
    var data: AlertJournalData = if (admitted) load() else AlertJournalData()
        private set
    val restarted: Boolean = data.lastEvaluation != null
    val loadFailed: Boolean = issue != null

    fun publish(candidate: AlertJournalData): Boolean {
        val bounded = retain(candidate)
        val encoded = ObjectiveJson.codec.encodeToString(bounded)
        if (encoded.toByteArray(Charsets.UTF_8).size > JOURNAL_LIMIT) {
            issue = "lifecycle_size_limit"
            return false
        }
        return try {
            SecureFile.writeAtomic0600(path, encoded)
            data = bounded
            issue = null
            true
        } catch (_: IOException) {
            issue = "lifecycle_write_failed"
            false
        }
    }

    fun clear() {
        data = AlertJournalData()
    }

    private fun load(): AlertJournalData = try {
        val encoded = ObjectiveJson.read(path, JOURNAL_LIMIT)
        if (encoded == null) {
            AlertJournalData()
        } else {
            val loaded = ObjectiveJson.codec.decodeFromString(AlertJournalData.serializer(), encoded)
            if (oversized(loaded)) {
                issue = "lifecycle_count_limit"
                AlertJournalData()
            } else {
                loaded.copy(
                    deliveries = loaded.deliveries.map {
                        // The remote may have accepted an in-flight attempt before the process stopped. Do not resend blindly.
                        if (it.status in setOf(
                                "dispatching",
                                "accepted",
                            )
                        ) {
                            it.copy(status = "restart_delivery_unknown")
                        } else {
                            it
                        }
                    },
                )
            }
        }
    } catch (_: SerializationException) {
        issue = "invalid_lifecycle_file"
        AlertJournalData()
    } catch (_: IOException) {
        issue = "lifecycle_read_failed"
        AlertJournalData()
    }

    private fun oversized(loaded: AlertJournalData): Boolean = listOf(
        loaded.states.size > MAX_RULES,
        loaded.latest.size > MAX_RULES,
        loaded.deliveries.size > MAX_DELIVERIES,
        loaded.events.size > MAX_EVENTS,
    ).any { it }

    private fun retain(candidate: AlertJournalData): AlertJournalData {
        var result = candidate.copy(events = candidate.events.takeLast(MAX_EVENTS))
        while (ObjectiveJson.codec.encodeToString(
                result,
            ).toByteArray(Charsets.UTF_8).size > JOURNAL_LIMIT && result.events.isNotEmpty()
        ) {
            result = result.copy(events = result.events.drop(1))
        }
        return result.copy(expiredEvents = candidate.expiredEvents + candidate.events.size - result.events.size)
    }
}

internal const val MAX_DELIVERIES = 32
private const val MAX_RULES = 32
private const val MAX_EVENTS = 128
private const val JOURNAL_LIMIT = 131_072
