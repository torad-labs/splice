package splice.app.sources.observability

import splice.app.sources.observability.cursor.PerfReadIssue
import splice.app.sources.observability.cursor.PerfReadRefusal
import java.nio.ByteBuffer
import java.security.MessageDigest

internal fun interface PerfEvidenceDedup {
    fun add(identity: String, payload: ByteArray): PerfEvidenceDuplicate
}

/** Scratch fallback only: exact identity/payload hashes, fixed allocation, and an explicit half-full refusal. */
internal class PerfEvidenceMemorySeen : PerfEvidenceDedup {
    private val table = ByteArray(MEMORY_SLOTS * MEMORY_SLOT_BYTES)
    private val digest = MessageDigest.getInstance("SHA-256")
    private var count = 0

    override fun add(identity: String, payload: ByteArray): PerfEvidenceDuplicate {
        val hash = digest.digest(identity.toByteArray())
        val first = (ByteBuffer.wrap(hash).int and Int.MAX_VALUE) % MEMORY_SLOTS
        var slot = first
        do {
            val at = slot * MEMORY_SLOT_BYTES
            if (table[at] == 0.toByte()) return insert(at, hash, payload)
            if (same(at + 1, hash)) {
                return if (same(at + 1 + MEMORY_HASH_BYTES, payload)) {
                    PerfEvidenceDuplicate.SAME
                } else {
                    PerfEvidenceDuplicate.CONFLICT
                }
            }
            slot = (slot + 1) % MEMORY_SLOTS
        } while (slot != first)
        throw PerfReadRefusal(PerfReadIssue.EVIDENCE_MEMORY_LIMIT)
    }

    private fun insert(at: Int, hash: ByteArray, payload: ByteArray): PerfEvidenceDuplicate {
        if (count >= MEMORY_SLOTS / MEMORY_LOAD_DIVISOR) throw PerfReadRefusal(PerfReadIssue.EVIDENCE_MEMORY_LIMIT)
        table[at] = 1
        hash.copyInto(table, at + 1)
        payload.copyInto(table, at + 1 + MEMORY_HASH_BYTES)
        count++
        return PerfEvidenceDuplicate.NEW
    }

    private fun same(at: Int, bytes: ByteArray): Boolean =
        bytes.indices.all { table[at + it] == bytes[it] }
}

// Charge 512 KiB for the 266,240-byte table, hash scratch and owner overhead within the existing shared limit.
internal const val EVIDENCE_MEMORY_DEDUP_BYTES = 512L * 1_024

// Fix the 65-byte slot table at 266,240 bytes, leaving scratch and owner room in its 512 KiB lease.
private const val MEMORY_SLOTS = 4_096

// Refuse new identities at half capacity to bound probing while still resolving existing duplicates.
private const val MEMORY_LOAD_DIVISOR = 2

// SHA-256 identity and payload digests each occupy exactly 32 bytes in every slot.
private const val MEMORY_HASH_BYTES = 32
private const val MEMORY_SLOT_BYTES = 1 + 2 * MEMORY_HASH_BYTES
