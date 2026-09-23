// NEW: v0.4.0 FEATURES.md §5 — the process seam `splice upgrade` runs gh, the candidate jar,
// diff and systemctl through, and its JDK implementation. Split from UpgradeRelease.kt
// (concentration, 2026-09-13).
package splice.lifecycle.upgrade

import splice.core.util.SafeFailureText
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val NO_SUCH_COMMAND = 127
private const val TIMED_OUT = 124
private const val MILLIS_PER_SECOND = 1_000L

/** Long enough for `gh attestation verify` on a slow link; an upgrade must never hang forever on it. */
private const val DEFAULT_TIMEOUT_MS = 300_000L

internal data class UpgradeExit(val code: Int, val stdout: String)

/** Run a command; [inherit] streams its output to the operator instead of capturing it. */
internal fun interface UpgradeProcess {
    operator fun invoke(command: List<String>, inherit: Boolean): UpgradeExit
}

internal class JdkUpgradeProcess(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) : UpgradeProcess {
    override fun invoke(command: List<String>, inherit: Boolean): UpgradeExit = try {
        val builder = ProcessBuilder(command).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.INHERIT)
        if (inherit) builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        val process = builder.start()
        // Read on a thread so a command that neither exits nor closes stdout still hits the deadline.
        val out = if (inherit) {
            CompletableFuture.completedFuture("")
        } else {
            CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        }
        if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            UpgradeExit(process.exitValue(), out.get())
        } else {
            process.destroyForcibly()
            UpgradeExit(TIMED_OUT, "${command.first()} did not finish within ${timeoutMs / MILLIS_PER_SECOND} s")
        }
    } catch (e: IOException) {
        UpgradeExit(NO_SUCH_COMMAND, SafeFailureText.render(e))
    }
}
