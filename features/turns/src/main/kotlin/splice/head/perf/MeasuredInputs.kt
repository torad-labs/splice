package splice.head.perf

import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputDigestCheckpointBudget
import splice.core.perf.InputDigestCheckpoints
import splice.core.perf.InputPrefix
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot
import splice.upstream.memory.JvmHeap

// why: bound in-memory sizing witnesses; a missing one passes through to the provider.
private const val MAX_PREFLIGHT_WITNESSES = 512

// why: evidence is optional; cap all heads' simultaneous retained and capture charges with one process-wide ceiling.
private const val MEASURED_DIGEST_BUDGET_BYTES = 32 * 1024 * 1024L

// why: one checkpoint admits up to four MiB of encoded history; larger requests retain only their byte-free witness.
private const val MEASURED_DIGEST_PREFIX_BYTES = 4 * 1024 * 1024

/** One evidence budget for all heads, rather than multiplying the optional allowance for every PerfStats owner. */
private object MeasuredDigestEvidence {
    val budget = InputDigestCheckpointBudget(
        JvmHeap.budget,
        MEASURED_DIGEST_BUDGET_BYTES,
        MEASURED_DIGEST_PREFIX_BYTES,
    )
}

/** The measured prefix is a lower bound; one token per added text byte is the upper bound. */
internal data class InputEstimate(val lowerTokens: Long, val upperTokens: Long, val basis: String)

/** Latest upstream input for one session, conversation and model; optional byte evidence is private and charged. */
internal class MeasuredInputs {
    private data class Key(val session: String, val conversation: String, val model: String)
    private data class Sample(val input: Long, val prefix: InputPrefix, val digests: InputDigestCheckpoints)

    private val samples = LinkedHashMap<Key, Sample>()
    private val lock = Any()

    fun remember(meta: PerfRowMeta, snap: PerfSnapshot, request: JsonObject?) {
        val key = key(meta) ?: return
        val input = snap.counters[PerfKeys.IN_TOKENS]?.takeIf { it > 0 }
        if (request == null || input == null) return
        val digests =
            synchronized(lock) { samples[key]?.digests } ?: InputDigestCheckpoints(MeasuredDigestEvidence.budget)
        var published = false
        try {
            val prefix = digests.capture(request) ?: return
            publish(key, Sample(input, prefix, digests))
            published = true
        } finally {
            if (!published && synchronized(lock) { samples[key]?.digests !== digests }) digests.close()
        }
    }

    private fun publish(key: Key, sample: Sample) {
        val retired = synchronized(lock) {
            val replaced = samples.remove(key)
            samples[key] = sample
            val evicted = if (samples.size > MAX_PREFLIGHT_WITNESSES) samples.remove(samples.keys.first()) else null
            listOfNotNull(replaced?.digests?.takeUnless { it === sample.digests }, evicted?.digests)
        }
        retired.forEach(InputDigestCheckpoints::close)
    }

    private fun key(meta: PerfRowMeta): Key? {
        val session = meta.transcript.sessionId ?: return null
        val conversation = meta.transcript.conversationKey ?: return null
        return meta.model?.let { Key(session, conversation, it) }
    }

    fun estimate(session: String?, conversation: String?, model: String, request: JsonObject): InputEstimate? {
        val key = if (session != null && conversation != null) Key(session, conversation, model) else null
        val measured = key?.let { synchronized(lock) { samples[it] } }
        val growth = measured?.prefix?.textGrowthBytes(request, measured.digests)
        return if (measured != null && growth != null) {
            InputEstimate(measured.input, measured.input + growth, "measured-text-prefix")
        } else {
            null
        }
    }
}
