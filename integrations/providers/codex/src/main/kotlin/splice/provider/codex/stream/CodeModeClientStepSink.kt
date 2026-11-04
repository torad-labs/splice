// NEW: callback publication detaches the upstream reader from that completed client step.
package splice.provider.codex.stream

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.upstream.codemode.CodeModeCall
import splice.upstream.sse.WireSink

internal class CodeModeClientStepSink(
    private val round: CodeModeSwitchingSink,
    private val sink: WireSink,
) : WireSink by sink {
    fun belongsTo(round: CodeModeSwitchingSink): Boolean = this.round === round

    override suspend fun openTool(id: String, name: String): WireBlockIndex {
        round.detach()
        return sink.openTool(id, name)
    }

    /** The round's early Write block, when [call] is the Write it streamed: the call takes it over. */
    suspend fun claimEarly(call: CodeModeCall): String? = round.claimEarly(call.name, call.arguments)

    /** Ends the early block call [clientId] took over, in place of opening a block for it. */
    suspend fun finishEarly(clientId: String, arguments: JsonObject): Boolean {
        round.detach()
        return round.finishEarly(sink, clientId, arguments)
    }
}
