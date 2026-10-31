package splice.provider.codex.diagnostics.current

import kotlinx.coroutines.CompletableDeferred
import splice.provider.codex.diagnostics.CodeModeGatewayBridgeSink
import splice.upstream.sse.WireSink
import java.util.concurrent.CopyOnWriteArrayList

/** Native progress can arrive after a yielded result, while the same request's model post remains open. */
internal class CodeModeGatewayInterruptedSink(
    private val marker: String,
    private val target: CodeModeGatewayBridgeSink = CodeModeGatewayBridgeSink(),
) : WireSink by target {
    val ready = CompletableDeferred<Unit>()
    val notifications: MutableList<String> = CopyOnWriteArrayList()

    override suspend fun addTextBlock(text: String) {
        target.addTextBlock(text)
        notifications += text
        if (text == marker) ready.complete(Unit)
    }
}
