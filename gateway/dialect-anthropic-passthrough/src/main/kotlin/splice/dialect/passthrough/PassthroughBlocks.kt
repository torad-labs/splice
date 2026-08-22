// PORT-OF: PassthroughStreamTranslator.kt @ 71a203c — invariants unchanged: the per-block record
// the registry stores, moved verbatim (private nested -> internal top-level, same names, same
// fields). A null [Block.wire] is the IGNORED shape: the block was recorded so its deltas are
// swallowed rather than logged as unmapped, and nothing was ever opened on the sink for it.
// Holding the record here is what keeps [PassthroughBlockRegistry] free of splice.core.index —
// the registry only ever reads `block.wire` back, never names its type (ChatToolFrame's idiom).
package splice.dialect.passthrough

import splice.core.index.WireBlockIndex

internal enum class Kind { TEXT, THINKING, TOOL, IGNORED }

internal data class Block(val kind: Kind, val wire: WireBlockIndex?) {
    var signatureSeen: Boolean = false
}
