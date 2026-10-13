package splice.head.perf

import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputDigest
import splice.core.perf.InputPrefix
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot

// why: bound in-memory sizing witnesses; a missing one passes through to the provider.
private const val MAX_PREFLIGHT_WITNESSES = 512

/** The measured prefix is a lower bound; one token per added text byte is the upper bound. */
internal data class InputEstimate(val lowerTokens: Long, val upperTokens: Long, val basis: String)

/** Latest observed upstream input for one session, conversation and model; no prompt bytes persist. */
internal class MeasuredInputs {
    private data class Key(val session: String, val conversation: String, val model: String)
    private data class Sample(val input: Long, val prefix: InputPrefix)

    private val samples = LinkedHashMap<Key, Sample>()
    private val lock = Any()

    fun remember(meta: PerfRowMeta, snap: PerfSnapshot, request: JsonObject?) {
        val key = meta.transcript.sessionId?.let { session ->
            meta.transcript.conversationKey?.let { conversation ->
                meta.model?.let { model -> Key(session, conversation, model) }
            }
        }
        val sample = snap.counters[PerfKeys.IN_TOKENS]?.takeIf { it > 0 }?.let { input ->
            request?.let(InputDigest::capture)?.let { prefix -> Sample(input, prefix) }
        }
        if (key != null && sample != null) {
            synchronized(lock) {
                samples.remove(key)
                samples[key] = sample
                if (samples.size > MAX_PREFLIGHT_WITNESSES) samples.remove(samples.keys.first())
            }
        }
    }

    fun estimate(session: String?, conversation: String?, model: String, request: JsonObject): InputEstimate? {
        val key = if (session != null && conversation != null) Key(session, conversation, model) else null
        val measured = key?.let { synchronized(lock) { samples[it] } }
        val growth = measured?.prefix?.textGrowthBytes(request)
        return if (measured != null && growth != null) {
            InputEstimate(measured.input, measured.input + growth, "measured-text-prefix")
        } else {
            null
        }
    }
}
