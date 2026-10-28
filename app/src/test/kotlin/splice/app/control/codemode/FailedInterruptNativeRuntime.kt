package splice.app.control.codemode

import splice.codemode.NativeCodeModeRuntime
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions
import java.io.IOException

/** A real native guest whose first cell's termination transport refuses once, then permits an explicit retry. */
internal class FailedInterruptNativeRuntime(
    private val native: NativeCodeModeRuntime = NativeCodeModeRuntime(),
) : NativeCodeModeLifecycle by native {
    private var first = true

    override suspend fun startNativeSession(
        sessionKey: String,
        source: String,
        definitions: List<CodeModeManual.NestedTool>,
        options: NativeCodeModeOptions,
    ): NativeCodeModeCell {
        val cell = native.startNativeSession(sessionKey, source, definitions, options)
        if (!first) return cell
        first = false
        return object : NativeCodeModeCell by cell {
            private var refused = false

            override suspend fun terminate(): CodeModeStep {
                if (!refused) {
                    refused = true
                    throw IOException("synthetic termination transport unavailable")
                }
                return cell.terminate()
            }
        }
    }
}
