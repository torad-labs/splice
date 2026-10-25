// NEW: what an attached turn needs to hand its compaction to a detached drive: the log it reports
// through, the ledger its retry reads, and the one scope both transport shapes launch onto.
package splice.head.turn

import kotlinx.coroutines.CoroutineScope
import splice.core.util.LogSink
import splice.head.compaction.CompactionReplay
import kotlin.coroutines.CoroutineContext

/** A named value bundle (V4-134 style) rather than four constructor parameters riding together:
 *  [log], [replay], [scope] and [context] always travel as one — TurnStreamer owns them for the
 *  streaming shape and hands the same three seams to CollectTurn for the collect one. */
internal data class DetachOwner(
    val log: LogSink,
    val replay: CompactionReplay,
    /** The detachment owner both shapes share (TurnStreamer): head stop cancels every child once. */
    val scope: CoroutineScope,
    val context: CoroutineContext,
)
