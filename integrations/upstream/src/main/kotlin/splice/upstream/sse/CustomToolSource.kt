// NEW: an item identity owns streamed custom-tool source independently of rendered progress.
package splice.upstream.sse

import kotlinx.coroutines.CoroutineScope
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome

/** Added and completed carriers are identity-bearing; a delta can only extend its admitted item. */
public sealed class CustomToolSource {
    public data class Started(val call: GatewayCustomCall) : CustomToolSource()
    public data class Delta(val callId: String, val text: String) : CustomToolSource()
    public data class Completed(
        val call: GatewayCustomCall,
        /** Accumulated history, independent of client blocks. Usage is provisional until the response ends. */
        val snapshot: TurnOutcome.Success? = null,
    ) : CustomToolSource()

    /** Updates an already sealed source's verified history; never admits or dispatches another program. */
    public data class Continuity(val callId: String, val snapshot: TurnOutcome.Success) : CustomToolSource()

    /** The round's terminal was parsed: the source is whole, whatever cancels the reader after this. */
    public data object Terminal : CustomToolSource()
}

/** The transport borrows this lifetime when one upstream source round spans several client tool steps. */
public interface IndependentRoundSink : WireSink {
    public val ownerScope: CoroutineScope
}
