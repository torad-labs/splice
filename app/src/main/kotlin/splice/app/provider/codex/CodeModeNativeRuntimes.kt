package splice.app.provider.codex

import splice.codemode.NativeCodeModeRuntime
import splice.codemode.host.PoolLimits
import splice.codemode.nativehost.NativeHostArtifact
import splice.provider.codex.CodeModeRuntimes
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.availability.CODE_MODE_HOST_DISABLED
import splice.upstream.codemode.availability.CodeModeAvailability
import java.io.IOException

/** Readiness and open share the same pinned bundle; a disabled provider never opens an engine. */
internal class CodeModeNativeRuntimes(
    private val enabled: Boolean,
    private val limits: PoolLimits,
    private val cellHeapMb: Int,
) : CodeModeRuntimes {
    private val artifact = NativeHostArtifact()

    override fun availability(): CodeModeAvailability = if (enabled) {
        artifact.availability()
    } else {
        CodeModeAvailability.Unavailable(CODE_MODE_HOST_DISABLED)
    }

    override fun open(): CodeModeRuntime {
        if (!enabled) throw IOException(CODE_MODE_HOST_DISABLED)
        return NativeCodeModeRuntime(limits = limits, cellHeapMb = cellHeapMb, artifact = artifact)
    }
}
