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

    private data class Borrowed(
        val source: Held,
        val unfinished: MessageDigest,
        val charge: InputDigestCheckpointBudget.Charge,
    )

    private data class Encoded(val hex: String, val checkpoint: Held?)

    private val lock = Any()
    private var held: Held? = null
    private var closed = false

    fun hex(value: JsonElement, remember: Boolean): String {
        val old = borrow()
        val supported = old != null || copy(MessageDigest.getInstance("SHA-256")) != null
        if (!supported) return InputDigest.hex(value)
        val charge = captureCharge(remember)
        try {
            val encoded = encode(value, old, charge)
            if (remember) publish(old?.source, encoded.checkpoint)
            return encoded.hex
        } finally {
            charge?.close()
            old?.charge?.close()
        }
    }

    /** Only cloning and ownership transfer take the monitor; rendering and matching run outside it. */
    private fun borrow(): Borrowed? = synchronized(lock) {
        val old = held ?: return@synchronized null
        val resumed = copy(old.unfinished) ?: return@synchronized null
        Borrowed(old, resumed, old.charge.share())
    }

    private fun captureCharge(remember: Boolean): InputDigestCheckpointBudget.Charge? {
        if (!remember || synchronized(lock) { closed }) return null
        return budget.reserve(
            budget.prefixLimitBytes.toLong() * DIGEST_CAPTURE_COPIES + DIGEST_CHECKPOINT_METADATA_BYTES,
        )
    }

    private fun encode(
        value: JsonElement,
        old: Borrowed?,
        charge: InputDigestCheckpointBudget.Charge?,
    ): Encoded {
        val wire = DigestPrefixStream(
            old?.source?.bytes,
            old?.unfinished,
            if (charge != null) budget.prefixLimitBytes else 0,
        )
        JsonWire.write(value, wire)
        val unfinished = wire.unfinished()
        val next = if (charge != null) copy(unfinished) else null
        wire.finalByte()?.let { unfinished.update(it.toByte()) }
        val hex = HexFormat.of().formatHex(unfinished.digest())
        return Encoded(hex, retained(next, wire, charge))
    }

    private fun publish(expected: Held?, replacement: Held?) {
        var adopted = false
        val retired = synchronized(lock) {
            if (closed || held !== expected) return@synchronized null
            val old = held
            held = replacement
            adopted = true
            old
        }
        if (adopted) retired?.charge?.close() else replacement?.charge?.close()
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

    override fun close() {
        val retired = synchronized(lock) {
            closed = true
            val old = held
            held = null
            old
        }
        retired?.charge?.close()
    }
}
