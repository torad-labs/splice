// PORT-OF: PassthroughStreamTranslator.kt @ 71a203c — invariants unchanged: the per-block record
// the registry stores, moved verbatim (private nested -> internal top-level, same names, same
// fields). A null [Block.wire] is the IGNORED shape: the block was recorded so its deltas are
// swallowed rather than logged as unmapped, and nothing was ever opened on the sink for it.
// Holding the record here is what keeps [PassthroughBlockRegistry] free of splice.core.index —
// the registry only ever reads `block.wire` back, never names its type (ChatToolFrame's idiom).
package splice.dialect.passthrough

import splice.core.index.WireBlockIndex

// RAW (DR-119): a server-tool block forwarded verbatim — it owns a wire so its deltas and stop
// flow, but it is neither prose (never buffered) nor a client tool (never sets hasToolUse).
internal enum class Kind { TEXT, THINKING, TOOL, RAW, IGNORED }

internal data class Block(val kind: Kind, val wire: WireBlockIndex?) {
    var signatureSeen: Boolean = false

    // V4-157: did THIS block actually receive thinking text? Per-block and not the turn-wide
    // [PassthroughProseChannels.emittedThinking], which answers a different question — a turn with
    // two thinking blocks, one full and one empty, latches that flag once and cannot tell them
    // apart, and it is the empty one that must not be signed. Latched on isNotBlank, the same
    // threshold the prose channel uses for its own flag (CX-09/DR-75's empty-delta-latch family).
    var receivedThinkingText: Boolean = false
}
