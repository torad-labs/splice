// NEW: Oct 20, 2026 — the hours ProbeEconomics has judged, kept across a restart.
//
// why: the answers lived only in memory, so the first hourly read after a start judged every hour the rollup holds, and
// decoded every archived perf generation to do it: about 0.9 GB of archives for claudex and 1.2 GB for claude-splice,
// about 45 GB of allocation in the first 30 s of the 02:28 CT boot on Oct 20, which grew the committed heap to 17.5 GB.
// A settled hour's answer stands while its rollup is as it was, so it is kept beside the backfill refusal and read back
// at start; only the hours whose rollup changed, and the hours still settling, are judged against the log.
package splice.app.sources

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import splice.core.util.SecureFile
import splice.head.usage.BucketBytes
import splice.head.usage.BucketCost
import splice.head.usage.BucketTokens
import splice.head.usage.BucketTools
import splice.head.usage.EconomicsBucket
import splice.head.usage.EconomicsTurnCounts
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: which hours a torn line may hold decides which hours are not read and which the backfill leaves out, and a
// judgment or a backfill edge kept under an older rule stays wrong while its rollup and files stand, so a kept hour
// that was not read is judged again once under a newer one, and an edge is covered again (EconomicsBackfill).
// 2: Oct 23, 2026, a line with no time ends at the row written after it; claude-grok kept 41 hours from Oct 13 to Oct 22
// not read under rule 1 for a NUL line of Jul 20 (round 13 of #247).
internal const val TEAR_RULE = 2

/** One hour's answer: the rollup signature it answered, the probes to take off, or why the hour cannot be shown. */
internal data class ProbeJudged(val signature: List<Long>, val probes: EconomicsBucket?, val gap: ProbeGap?)

/** The answers a previous run kept, and the files they were judged from (null when that run could not mark them). */
internal data class KeptJudgments(val judged: Map<Long, ProbeJudged>, val stamp: PerfSourceStamp?)

/** An hour's probe token sums as they are kept on disk. */
@Serializable
internal data class SavedProbeTokens(
    val inTokens: Long,
    val cachedTokens: Long,
    val cacheWriteTokens: Long,
    val outTokens: Long,
)

/** An hour's probes as they are kept on disk: every sum [ProbeEconomics] counts a probe into. */
@Serializable
internal data class SavedProbeSums(
    val turns: Long,
    val localSteps: Long,
    val tokens: SavedProbeTokens,
    val reqBytes: Long,
    val upstreamBytes: Long,
    val toolsEager: Long,
    val toolsDeferred: Long,
    val deferralTurns: Long,
    val unpricedTurns: Long,
    val failedTurns: Long? = null,
) {
    /** The bucket these sums were taken from, as ProbeEconomics summed it: every other figure at its default. */
    fun bucket(hour: Long): EconomicsBucket = EconomicsBucket(
        hour = hour,
        counts = EconomicsTurnCounts(turns = turns, localSteps = localSteps),
        tokens = BucketTokens(tokens.inTokens, tokens.cachedTokens, tokens.cacheWriteTokens, tokens.outTokens),
        bytes = BucketBytes(reqBytes, upstreamBytes),
        tools = BucketTools(toolsEager, toolsDeferred, deferralTurns),
        cost = BucketCost(unpricedTurns = unpricedTurns, failedTurns = failedTurns),
    )
}

/** One hour's answer as it is kept on disk; the gap by its enum name. */
@Serializable
internal data class SavedJudgment(
    val hour: Long,
    val signature: List<Long>,
    val probes: SavedProbeSums? = null,
    val gap: String? = null,
) {
    /** The answer, or null when the gap names none this build knows, so the hour is judged again. */
    fun judged(): ProbeJudged? {
        val known = gap?.let { name -> ProbeGap.entries.firstOrNull { it.name == name } ?: return null }
        return ProbeJudged(signature, probes?.bucket(hour), known)
    }
}

/** Every kept answer, the files they were judged from, and the [TEAR_RULE] they were judged under (null before 2). */
@Serializable
internal data class SavedJudgments(
    val marks: List<SavedPerfMark>? = null,
    val hours: List<SavedJudgment>,
    val tearRule: Int? = null,
)

/** Where one head's judged hours are kept: `<head>-perf-probe-judgments.json`, beside the live request log. */
internal class ProbeJudgments(private val files: PerfReadFiles) {
    private val path = files.stateFile("probe-judgments")
    private val json = Json { ignoreUnknownKeys = true }

    /** What a previous run kept; a missing or unreadable file keeps nothing, so every hour is judged as before. */
    fun load(): KeptJudgments? {
        val kept = decoded() ?: return null
        val judged = kept.hours.mapNotNull { saved -> saved.judged()?.let { saved.hour to it } }
            .filter { (_, answer) -> answer.gap == null || kept.tearRule == TEAR_RULE }.toMap()
        val stamp = kept.marks?.let { marks ->
            files.stampOf(marks.map { PerfFileMark(Path.of(it.path), it.fileKey, it.size, it.checksum) })
        }
        return KeptJudgments(judged, stamp)
    }

    /** Best effort: answers that cannot be kept are judged again after the next restart, as before. */
    fun save(judged: Map<Long, ProbeJudged>, stamp: PerfSourceStamp?) {
        try {
            val marks = stamp?.marks?.map { SavedPerfMark(it.path.toString(), it.fileKey, it.size, it.checksum) }
            val hours = judged.entries.sortedBy { it.key }.map { (hour, answer) ->
                SavedJudgment(hour, answer.signature, answer.probes?.let(::sums), answer.gap?.name)
            }
            val text = json.encodeToString(SavedJudgments.serializer(), SavedJudgments(marks, hours, TEAR_RULE))
            SecureFile.writeAtomic0600(path, text)
        } catch (_: IOException) {
            // The answers still stand in memory for this run.
        }
    }

    private fun decoded(): SavedJudgments? = try {
        json.decodeFromString(SavedJudgments.serializer(), Files.readString(path))
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun sums(bucket: EconomicsBucket): SavedProbeSums = SavedProbeSums(
        turns = bucket.turns,
        localSteps = bucket.localSteps,
        tokens = SavedProbeTokens(bucket.inTokens, bucket.cachedTokens, bucket.cacheWriteTokens, bucket.outTokens),
        reqBytes = bucket.reqBytes,
        upstreamBytes = bucket.upstreamBytes,
        toolsEager = bucket.toolsEager,
        toolsDeferred = bucket.toolsDeferred,
        deferralTurns = bucket.deferralTurns,
        unpricedTurns = bucket.unpricedTurns,
        failedTurns = bucket.failedTurns,
    )
}
