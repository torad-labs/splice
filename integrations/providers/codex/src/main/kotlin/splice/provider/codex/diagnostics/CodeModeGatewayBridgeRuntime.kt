package splice.provider.codex.diagnostics

import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions

/** Supplies deterministic native observations while the real bridge owns admission, resume and persistence. */
internal class CodeModeGatewayBridgeRuntime(private val steps: ArrayDeque<CodeModeStep>) : NativeCodeModeLifecycle {
    val accepted = mutableListOf<List<CodeModeResult>>()

    override suspend fun start(
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell = Cell()

    override suspend fun startNativeSession(
        sessionKey: String,
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String>,
        options: NativeCodeModeOptions,
    ): NativeCodeModeCell = Cell()

    override fun close() = Unit

    private inner class Cell : NativeCodeModeCell {
        override val cellId: String = "fixture-native-cell"
        override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
            submitResults(results)
            return observe(0)
        }

        override suspend fun submitResults(results: List<CodeModeResult>) {
            accepted += results
        }

        override suspend fun observe(yieldTimeMs: Long): CodeModeStep = steps.removeFirst()
        override suspend fun terminate(): CodeModeStep = CodeModeStep.Completed("", terminated = true)

        override fun close() = Unit
    }
}
