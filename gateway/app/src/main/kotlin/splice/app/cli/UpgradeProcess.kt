// NEW: v0.4.0 FEATURES.md §5 — the process seam `splice upgrade` runs gh, the candidate jar,
// diff and systemctl through, and its JDK implementation. Split from UpgradeRelease.kt
// (concentration, 2026-09-13).
package splice.app.cli

import splice.core.util.SafeFailureText
import java.io.IOException

private const val NO_SUCH_COMMAND = 127

internal data class UpgradeExit(val code: Int, val stdout: String)

/** Run a command; [inherit] streams its output to the operator instead of capturing it. */
internal fun interface UpgradeProcess {
    operator fun invoke(command: List<String>, inherit: Boolean): UpgradeExit
}

internal class JdkUpgradeProcess : UpgradeProcess {
    override fun invoke(command: List<String>, inherit: Boolean): UpgradeExit = try {
        val builder = ProcessBuilder(command).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.INHERIT)
        if (inherit) builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        val process = builder.start()
        val out = if (inherit) "" else process.inputStream.bufferedReader().readText()
        UpgradeExit(process.waitFor(), out)
    } catch (e: IOException) {
        UpgradeExit(NO_SUCH_COMMAND, SafeFailureText.render(e))
    }
}
