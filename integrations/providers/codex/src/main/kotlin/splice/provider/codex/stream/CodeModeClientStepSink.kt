// NEW: callback publication detaches the upstream reader from that completed client step.
package splice.provider.codex.stream

import splice.core.index.WireBlockIndex
import splice.upstream.sse.WireSink

internal class CodeModeClientStepSink(
    private val round: CodeModeSwitchingSink,
    private val sink: WireSink,
) : WireSink by sink {
    override suspend fun openTool(id: String, name: String): WireBlockIndex {
        round.detach()
        return sink.openTool(id, name)
    }
}
