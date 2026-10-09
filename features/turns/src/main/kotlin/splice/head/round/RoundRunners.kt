// NEW: what the round strategy needs to build the runner a turn is driven by, split out of
// RoundStrategy so its constructor takes the turn's tag, log, signals, finish and tool-search policy
// once instead of beside the emitter and both post shapes, and the runners are built here.
package splice.head.round

import splice.core.util.LogSink
import splice.upstream.ReanchorPolicy
import splice.upstream.RetryNotice
import splice.upstream.RoundResult
import splice.upstream.ToolSearchPolicy
import splice.upstream.sse.WireSink
import splice.upstream.transport.UpstreamEnding

/** The turn-scoped collaborators every runner shares: the head tag, its log, the signals the runners
 *  read, [finish] for the one terminal outcome, and the tool-search policy when the head has one. */
internal class RoundRunners(
    private val key: String,
    log: LogSink,
    private val signals: RunnerSignals,
    private val finish: FinishTurn,
    private val toolSearch: ToolSearchPolicy? = null,
) {
    private val notice = RetryNotice { log(it) }
    private val rounds = RoundSplice()

    /** Whether the head searches tools, which keeps a turn off the single-round path. */
    fun searchesTools(): Boolean = toolSearch != null

    /** The runner for a fold-eligible turn. */
    fun fold(emitter: WireSink, post: PostRoundToSink, reanchor: ReanchorPolicy?): FoldRunner =
        FoldRunner(emitter, post, FoldRounds(key, notice, signals, finish, reanchor, toolSearch))

    /** The runner for a re-anchor or search-only turn. */
    fun reanchoring(post: PostRound): ReanchorRunner =
        ReanchorRunner(key, notice, post, finish, signals, toolSearch)

    /** The single-round path's end: DR-130 — the runners salvage a failed round's own burn through
     *  withFailureSalvage (DR-124); this path handed the raw outcome to finishTurn, which stamps ONLY
     *  salvagedUsage, so the tokens the vendor billed went unrecorded. There are no absorbed rounds
     *  here, so the accumulator is empty by construction and a Success or a clean abandonment passes
     *  through untouched. */
    suspend fun finishAlone(result: RoundResult): UpstreamEnding? = when (result) {
        is RoundResult.Outcome -> {
            finish(rounds.withFailureSalvage(result.outcome, RoundUsage()))
            null
        }
        is RoundResult.Ended -> result.ending
    }
}
