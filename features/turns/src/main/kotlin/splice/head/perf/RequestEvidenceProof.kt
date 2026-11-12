package splice.head.perf

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A receipt for one durable evidence prefix, never an inference from event timestamps. */
@Serializable
public data class RequestEvidenceProof(
    val schemaVersion: Int = 1,
    val asOfMs: Long,
    val heads: List<String>,
    val intervals: List<RequestEvidenceInterval>,
    val prefixes: List<RequestEvidencePrefix>,
    val lossEpochs: Map<String, Long> = emptyMap(),
    val retentionEpochs: Map<String, Long> = emptyMap(),
    val scopeComplete: Boolean = true,
)

@Serializable
public data class RequestEvidenceInterval(val head: String, val bootId: String, val fromMs: Long, val untilMs: Long)

@Serializable
public data class RequestEvidencePrefix(val fileName: String, val end: Long, val digest: String)

public object RequestEvidenceProofFormat {
    // One prefix per retained segment bounds both receipt decoding and the producer's segment inventory.
    public const val MAX_PROOF_PREFIXES: Int = 128

    // Bound boot labels in producer state and receipt validation before they enter interval collections.
    public const val MAX_PROOF_BOOT_CHARS: Int = 256

    public const val COVERAGE_FILE: String = "coverage.json"

    // Cap durable coverage receipts at 128 KiB before JSON decoding or producer publication.
    public const val MAX_BYTES: Int = 128 * 1_024

    // Allow thirty-two evidence heads so coverage membership and loss maps remain bounded.
    public const val MAX_HEADS: Int = 32

    // Keep 256 coverage intervals; older intervals expire rather than extending receipt state indefinitely.
    public const val MAX_INTERVALS: Int = 256

    /** Bound object cardinality before decoding, including a corrupt receipt's maps and arrays. */
    public fun decode(bytes: ByteArray): RequestEvidenceProof {
        require(bytes.size <= MAX_BYTES && bytes.count { it == COLON || it == COMMA } <= MAX_TOKENS)
        val proof = Json.decodeFromString<RequestEvidenceProof>(bytes.toString(Charsets.UTF_8))
        require(proof.heads.size in 1..MAX_HEADS && proof.intervals.size <= MAX_INTERVALS)
        require(proof.prefixes.size <= MAX_PROOF_PREFIXES)
        require(
            proof.prefixes.all { it.fileName.length <= MAX_FILE_CHARS && it.digest.length <= MAX_PROOF_DIGEST_CHARS },
        )
        require(proof.lossEpochs.size <= MAX_HEADS && proof.retentionEpochs.size <= MAX_HEADS)
        scopes(proof)
        return proof
    }
    private fun scopes(proof: RequestEvidenceProof) {
        require(proof.lossEpochs.keys.all { it in proof.heads } && proof.retentionEpochs.keys.all { it in proof.heads })
        require(proof.heads.all { it.length <= MAX_HEAD_CHARS })
        require(proof.intervals.all { it.head in proof.heads && it.bootId.length <= MAX_PROOF_BOOT_CHARS })
    }
}

// Reject receipts with more than 4,096 structural separators before allocating decoded arrays and maps.
private const val MAX_TOKENS = 4_096

// Bound head labels to the existing 128-character head identifier contract before retaining coverage scopes.
private const val MAX_HEAD_CHARS = 128

// The byte value 58 is the JSON colon separator counted before receipt decoding.
private const val COLON: Byte = 58

// The byte value 44 is the JSON comma separator counted before receipt decoding.
private const val COMMA: Byte = 44

// Limit retained segment filenames to 256 characters before accepting proof-prefix descriptors.
private const val MAX_FILE_CHARS = 256

// A SHA-256 digest has sixty-four hexadecimal characters in the serialized prefix proof.
private const val MAX_PROOF_DIGEST_CHARS = 64
