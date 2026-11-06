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
    public const val FILE: String = "coverage.json"
    public const val MAX_BYTES: Int = 128 * 1_024
    public const val MAX_HEADS: Int = 32
    public const val MAX_INTERVALS: Int = 256

    /** Bound object cardinality before decoding, including a corrupt receipt's maps and arrays. */
    public fun decode(bytes: ByteArray): RequestEvidenceProof {
        require(bytes.size <= MAX_BYTES && bytes.count { it == COLON || it == COMMA } <= MAX_TOKENS)
        val proof = Json.decodeFromString<RequestEvidenceProof>(bytes.toString(Charsets.UTF_8))
        require(proof.heads.size in 1..MAX_HEADS && proof.intervals.size <= MAX_INTERVALS)
        require(proof.prefixes.size <= MAX_PREFIXES)
        require(proof.prefixes.all { it.fileName.length <= MAX_FILE_CHARS && it.digest.length <= MAX_DIGEST_CHARS })
        require(proof.lossEpochs.size <= MAX_HEADS && proof.retentionEpochs.size <= MAX_HEADS)
        scopes(proof)
        return proof
    }
    private fun scopes(proof: RequestEvidenceProof) {
        require(proof.lossEpochs.keys.all { it in proof.heads } && proof.retentionEpochs.keys.all { it in proof.heads })
        require(proof.heads.all { it.length <= MAX_HEAD_CHARS })
        require(proof.intervals.all { it.head in proof.heads && it.bootId.length <= MAX_BOOT_CHARS })
    }
}

private const val MAX_TOKENS = 4_096
private const val MAX_PREFIXES = 128
private const val MAX_HEAD_CHARS = 128
private const val MAX_BOOT_CHARS = 256
private const val COLON: Byte = 58
private const val COMMA: Byte = 44

private const val MAX_FILE_CHARS = 256
private const val MAX_DIGEST_CHARS = 64
