package splice.core.perf

import kotlinx.serialization.json.JsonElement
import splice.core.util.JsonWire
import java.security.MessageDigest
import java.util.HexFormat

// why: capture temporarily owns a growing collector and its copied evidence array before replacing an old entry.
private const val DIGEST_CAPTURE_COPIES = 3L

// why: charge the unfinished digest state, evidence array header, ownership token and slot alongside payload capacity.
private const val DIGEST_CHECKPOINT_METADATA_BYTES = 512L

/** A slot owns only admitted encoded evidence and private unfinished state, never a JSON tree or finalized digest. */
internal class DigestPrefixCheckpoint(private val budget: InputDigestCheckpointBudget) : AutoCloseable {
    private data class Held(
        val bytes: ByteArray,
        val unfinished: MessageDigest,
        val charge: InputDigestCheckpointBudget.Charge,
    )

    private var held: Held? = null
    private var closed = false

    @Synchronized
    fun hex(value: JsonElement, remember: Boolean): String {
        val old = held
        val resumed = old?.let { copy(it.unfinished) }
        val supported = resumed != null || copy(MessageDigest.getInstance("SHA-256")) != null
        if (!supported) return InputDigest.hex(value)
        val charge = captureCharge(remember)
        try {
            val wire = DigestPrefixStream(
                old?.bytes?.takeIf { resumed != null },
                resumed,
                if (charge != null) budget.prefixLimitBytes else 0,
            )
            JsonWire.write(value, wire)
            val unfinished = wire.unfinished()
            val next = if (charge != null) copy(unfinished) else null
            wire.finalByte()?.let { unfinished.update(it.toByte()) }
            val result = HexFormat.of().formatHex(unfinished.digest())
            if (remember && !closed) replace(next, wire, charge)
            return result
        } finally {
            charge?.close()
        }
    }

    private fun captureCharge(remember: Boolean): InputDigestCheckpointBudget.Charge? {
        if (!remember || closed) return null
        return budget.reserve(
            budget.prefixLimitBytes.toLong() * DIGEST_CAPTURE_COPIES + DIGEST_CHECKPOINT_METADATA_BYTES,
        )
    }

    private fun replace(
        next: MessageDigest?,
        wire: DigestPrefixStream,
        charge: InputDigestCheckpointBudget.Charge?,
    ) {
        val replacement = retained(next, wire, charge)
        held?.charge?.close()
        held = replacement
    }

    private fun retained(
        next: MessageDigest?,
        wire: DigestPrefixStream,
        charge: InputDigestCheckpointBudget.Charge?,
    ): Held? {
        if (charge == null || next == null) return null
        val bytes = wire.prefixBytes() ?: return null
        val retained = charge.retain(bytes, bytes.size.toLong() + DIGEST_CHECKPOINT_METADATA_BYTES)
        return Held(bytes, next, retained)
    }

    /** Clone support is provider-dependent; absence is an optimization miss, never a different digest. */
    private fun copy(value: MessageDigest): MessageDigest? = try {
        value.clone() as? MessageDigest
    } catch (_: CloneNotSupportedException) {
        null
    }

    @Synchronized
    override fun close() {
        closed = true
        held?.charge?.close()
        held = null
    }
}
