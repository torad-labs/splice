package splice.head.perf

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputDigestCheckpointBudget
import splice.core.perf.InputDigestCheckpoints
import splice.core.perf.InputPrefix
import splice.core.perf.InputPrefixDigests
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
    private data class Sample(val input: Long, val prefix: InputPrefix, val owner: Owner)

    private data class Evidence(val digests: InputDigestCheckpoints) {
        val lock = Any()
        var owners = 1
    }

    /** Each sample or caller owns an independent pin; eviction releases only the sample's pin. */
    private class Owner(private val evidence: Evidence) : AutoCloseable {
        val digests: InputDigestCheckpoints get() = evidence.digests
        private var closed = false

        fun share(): Owner = synchronized(evidence.lock) {
            check(!closed)
            evidence.owners++
            Owner(evidence)
        }

        override fun close() {
            val last = synchronized(evidence.lock) {
                if (closed) return@synchronized false
                closed = true
                evidence.owners--
                evidence.owners == 0
            }
            if (last) digests.close()
        }
    }

    /** Answers each digest with the measured one, so a growth bound can be asked without rendering history.
     *  Null from it is the real answer too; a bound from it holds only once the real digests agree. */
    private class MeasuredDigests(private val prefix: InputPrefix) : InputPrefixDigests {
        override fun input(value: JsonArray): String = prefix.inputDigest

        override fun properties(value: JsonObject): String = prefix.propertiesDigest
    }

    private val samples = LinkedHashMap<Key, Sample>()
    private val lock = Any()

    private fun borrow(key: Key): Sample? = synchronized(lock) {
        samples[key]?.let { it.copy(owner = it.owner.share()) }
    }

    fun remember(meta: PerfRowMeta, snap: PerfSnapshot, request: JsonObject?) {
        val key = key(meta) ?: return
        val input = snap.counters[PerfKeys.IN_TOKENS]?.takeIf { it > 0 }
        if (request == null || input == null) return
        val owner = borrow(key)?.owner ?: Owner(Evidence(InputDigestCheckpoints(MeasuredDigestEvidence.budget)))
        var published = false
        try {
            val prefix = owner.digests.capture(request) ?: return
            publish(key, Sample(input, prefix, owner))
            published = true
        } finally {
            if (!published) owner.close()
        }
    }

    private fun publish(key: Key, sample: Sample) {
        val retired = synchronized(lock) {
            val replaced = samples.remove(key)
            samples[key] = sample
            val evicted = if (samples.size > MAX_PREFLIGHT_WITNESSES) samples.remove(samples.keys.first()) else null
            listOfNotNull(replaced?.owner, evicted?.owner)
        }
        retired.forEach(Owner::close)
    }

    private fun key(meta: PerfRowMeta): Key? {
        val session = meta.transcript.sessionId ?: return null
        val conversation = meta.transcript.conversationKey ?: return null
        return meta.model?.let { Key(session, conversation, it) }
    }

    fun estimate(session: String?, conversation: String?, model: String, request: JsonObject): InputEstimate? {
        val key = if (session != null && conversation != null) Key(session, conversation, model) else null
        val measured = key?.let(::borrow) ?: return null
        return measured.owner.use { owner ->
            // why: an appended tool call bounds nothing whatever the history digests say, and most turns append one;
            // asking first with the measured digests standing in skips a history render the answer would discard.
            measured.prefix.textGrowthBytes(request, MeasuredDigests(measured.prefix)) ?: return@use null
            val growth = measured.prefix.textGrowthBytes(request, owner.digests) ?: return@use null
            InputEstimate(measured.input, measured.input + growth, "measured-text-prefix")
        }
    }
}
