package splice.provider.codex

import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonObject
import splice.core.turn.GatewayCustomCall
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions
import splice.upstream.sse.CustomToolSource
import splice.upstream.sse.WireSink

/** Holds a real bridge reader after its executable item while the client owns a callback. */
internal object CodeModeRawBodyFixture {
    fun runtime(): NativeCodeModeLifecycle = object : NativeCodeModeLifecycle {
        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell =
            error("native lifecycle required")
        override suspend fun startNativeSession(
            sessionKey: String,
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
            options: NativeCodeModeOptions,
        ): NativeCodeModeCell = object : NativeCodeModeCell {
            override val cellId: String = "gc-cell"
            override suspend fun submitResults(results: List<CodeModeResult>) = Unit
            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = observe(0)
            override suspend fun observe(yieldTimeMs: Long): CodeModeStep = CodeModeStep.Calls(
                listOf(CodeModeCall("gc-read", "Read", JsonObject(emptyMap()))),
            )
            override suspend fun terminate(): CodeModeStep = CodeModeStep.Completed("", terminated = true)
            override fun close() = Unit
        }
        override fun close() = Unit
    }

    fun post(call: GatewayCustomCall): RedirectableRoundPost = object : RedirectableRoundPost {
        override suspend fun invoke(bodyJson: String): RoundResult = error("redirect required")
        override suspend fun into(bodyJson: String, sink: WireSink): RoundResult {
            sink.customToolSource(CustomToolSource.Started(call.copy(input = "")))
            sink.customToolSource(CustomToolSource.Completed(call))
            awaitCancellation()
        }
    }
}
