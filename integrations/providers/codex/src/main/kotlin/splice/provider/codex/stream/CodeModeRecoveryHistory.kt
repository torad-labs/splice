// NEW: client replay excludes private recovery inputs while the current turn keeps its original upstream view.
package splice.provider.codex.stream

import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeContinuity
import splice.provider.codex.CodeModeInputBoundary
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeWire
import splice.provider.codex.state.CodeModeNativeChain
import java.util.concurrent.ConcurrentHashMap

/** Owned by one prepared turn, never shared by sessions or persisted. */
internal class CodeModeRecoveryHistory(private val baseline: CodeModeBody) {
    private var prefix = Prefix()
    private var generated = Prefix()
    private var continuityPrefix = Prefix()
    private val posted = ConcurrentHashMap<String, PostedHistory>()

    internal data class PostedHistory(
        val boundary: CodeModeInputBoundary,
        val native: CodeModeNativeChain.Capture,
        @Volatile var continuity: CodeModeContinuity,
        val prefix: Prefix,
        val before: Prefix,
        val generated: Prefix,
        @Volatile var text: String,
    )

    /** Immutable segments share every earlier script; joining is transient and iterative, never retained. */
    internal class Prefix(
        private val text: String = "",
        private val envelopes: List<String> = emptyList(),
        private val before: Prefix? = null,
    ) {
        fun extend(partial: TurnOutcome.PartialRound): Prefix {
            val emitted = partial.bodyText.takeIf { partial.emittedText }.orEmpty()
            return if (emitted.isEmpty() && partial.reasoningEnvelopes.isEmpty()) {
                this
            } else {
                Prefix(emitted, partial.reasoningEnvelopes.toList(), this)
            }
        }

        fun remaining(partial: TurnOutcome.PartialRound): TurnOutcome.PartialRound {
            val envelopes = reasoning()
            return partial.copy(
                bodyText = partial.bodyText.removePrefix(text()),
                reasoningEnvelopes = if (partial.reasoningEnvelopes.take(envelopes.size) == envelopes) {
                    partial.reasoningEnvelopes.drop(envelopes.size)
                } else {
                    partial.reasoningEnvelopes
                },
            )
        }

        fun continuity(outcome: TurnOutcome.Success, wire: CodexCodeModeWire): CodeModeContinuity {
            val text = text()
            return wire.continuity(
                outcome.copy(
                    bodyText = text + outcome.bodyText,
                    emittedText = text.isNotEmpty() || outcome.emittedText,
                    reasoningEnvelopes = reasoning() + outcome.reasoningEnvelopes,
                ),
            )
        }

        fun appendText(target: StringBuilder) = visit { target.append(it.text) }

        private fun text(): String = buildString { appendText(this) }

        private fun reasoning(): List<String> = buildList { visit { addAll(it.envelopes) } }

        private inline fun visit(action: (Prefix) -> Unit) {
            val segments = ArrayDeque<Prefix>()
            var cursor: Prefix? = this
            while (cursor != null) {
                segments.addFirst(cursor)
                cursor = cursor.before
            }
            segments.forEach(action)
        }
    }

    /** A parked reader keeps only its emitted prefix and its own small view, never the original request. */
    internal class Source(private val prefix: Prefix, private val posted: PostedHistory) {
        fun finish(outcome: TurnOutcome.Success, wire: CodexCodeModeWire): CodeModeContinuity {
            posted.continuity = wire.continuity(outcome)
            posted.text = outcome.bodyText.takeIf { outcome.emittedText }.orEmpty()
            return prefix.continuity(outcome, wire)
        }
    }

    /** Full client echoes may span completed scripts; upstream continuity still belongs to each script alone. */
    internal class Delivery(private val posted: PostedHistory) {
        fun text(cell: CodeModeStreamingCell?): String? {
            val current = if (cell == null) posted.text else cell.deliveredText ?: return null
            return buildString {
                posted.before.appendText(this)
                posted.generated.appendText(this)
                append(current)
            }.takeIf(String::isNotEmpty)
        }
    }

    fun extend(partial: TurnOutcome.PartialRound) {
        prefix = prefix.extend(partial)
        continuityPrefix = continuityPrefix.extend(generated.remaining(partial))
        generated = Prefix()
    }

    fun generated(outcome: TurnOutcome?) {
        val success = outcome as? TurnOutcome.Success ?: return
        generated = generated.extend(
            TurnOutcome.PartialRound(
                bodyText = success.bodyText,
                emittedText = success.emittedText,
                reasoningEnvelopes = success.reasoningEnvelopes,
            ),
        )
    }

    fun clientBoundary(completed: List<CodeModeRecord>, wire: CodexCodeModeWire): CodeModeInputBoundary? {
        val rewritten = wire.canonicalize(baseline, completed)
        return rewritten.body?.let { wire.anchoredBoundary(it, completed) }
    }

    fun continuity(outcome: TurnOutcome.Success, wire: CodexCodeModeWire): CodeModeContinuity =
        continuityPrefix.continuity(outcome, wire)

    fun source(record: CodeModeRecord): Source? = posted[record.id]?.let { Source(it.prefix, it) }

    fun delivery(record: CodeModeRecord): Delivery? = posted[record.id]?.let(::Delivery)

    fun remember(
        record: CodeModeRecord,
        boundary: CodeModeInputBoundary,
        native: CodeModeNativeChain.Capture,
        continuity: CodeModeContinuity,
        text: String,
    ) {
        posted[record.id] = PostedHistory(
            boundary, native, continuity, continuityPrefix, prefix, generated, text,
        )
        continuityPrefix = Prefix()
    }

    /** Ephemeral immutable snapshots keep same-turn continuation bytes exactly as before recovery rebasing. */
    fun upstream(records: List<CodeModeRecord>): List<CodeModeRecord> = records.map { record ->
        val history = posted[record.id] ?: return@map record
        val state = record.snapshot()
        state.copy(
            baselineInputCount = history.boundary.fullCount,
            baselineInputDigest = history.boundary.fullDigest,
            baselineLogicalCount = history.boundary.logicalCount,
            baselineLogicalDigest = history.boundary.logicalDigest,
            nativeSegments = history.native.segments,
            continuity = history.continuity.logicalItems,
            continuityReplay = history.continuity.replayItems,
        ).also {
            it.issued = state.issued
            it.sessionId = state.sessionId
            it.conversationId = state.conversationId
            it.nativeBaseId = history.native.parent?.id
            it.replayAnchors = history.boundary.replayAnchors
            it.sourceState = state.sourceState
        }.restore().also { it.nativeParent = history.native.parent }
    }
}
