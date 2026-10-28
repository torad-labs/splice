package splice.provider.codex

import splice.core.turn.HeadStopSignal
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeRecoveryHistory
import splice.provider.codex.stream.CodeModeUpstreamPost
import splice.upstream.sse.WireSink

/** State owned by one coordinated client step while its branch lock is held. */
internal data class CodeModeRunContext(
    val turn: CodexCodeModeBridge.Turn,
    val disableParallel: Boolean,
    val key: String,
    val digest: String,
    val link: CodeModeRoundLink,
    val headStop: HeadStopSignal? = null,
    val recovery: CodeModeRecoveryHistory? = null,
) {
    /** The client request; each post in this step extends its preceding post. */
    val client: CodeModeBody? get() = link.client

    /** One filtered history, extended only by scripts completed by this request. */
    val completed: MutableList<CodeModeRecord> = java.util.concurrent.CopyOnWriteArrayList()

    /** Posted sources can be cut before this step parks or holds a billing row. */
    val postedSources: MutableList<CodeModeLiveRound> = mutableListOf()

    /** Placement rejection removes execution while preserving this step's source bill. */
    val abandonedSources: MutableList<CodeModeRecord> = mutableListOf()
    var scripts: Int = 0

    /** Forked accepted results still name calls owned by the client. */
    var conflicts: Set<String> = emptySet()

    /** A sibling may post concurrently, so continuation follows this step's own post. */
    var postId: Long? = null
}

/** Delivery and posting capabilities attached to the client request, including resumed sinks. */
internal data class CodeModeRoundLink(
    val sink: WireSink,
    val post: CodeModeUpstreamPost,
    var client: CodeModeBody? = null,
    var priorDeliveryCounts: Map<String, Int> = emptyMap(),
)
