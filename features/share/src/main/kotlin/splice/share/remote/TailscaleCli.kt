// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). `tailscale status --json` on this machine, for
// TailscalePeers: the tailscale on the PATH, else where it installs outside one (a daemon launchd started has a short
// PATH, and the Mac app keeps its CLI inside the app). What it prints is read while it runs, so a large tailnet cannot
// fill the pipe; a run that cannot start, fails, or takes longer than TAILSCALE_STATUS_MS is no status.
package splice.share.remote

import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class TailscaleCli(
    private val env: EnvReader = EnvReader(System::getenv),
    /** Where tailscale installs outside a PATH, asked after it. */
    private val places: List<String> = listOf(TAILSCALE_USR_LOCAL, TAILSCALE_HOMEBREW, TAILSCALE_MAC_APP),
) {
    private val reader = Executor { Thread.ofVirtual().name("splice-tailscale-status").start(it) }

    /** What `tailscale status --json` printed, or null when there is nothing to read. */
    fun json(): String? {
        val process = binary()?.let(::started) ?: return null
        val printed = CompletableFuture.supplyAsync({ process.inputReader().use { it.readText() } }, reader)
        val done = process.waitFor(TAILSCALE_STATUS_MS, TimeUnit.MILLISECONDS)
        if (!done) process.destroyForcibly()
        return if (done && process.exitValue() == 0) read(printed) else null
    }

    private fun binary(): File? {
        val onPath = env("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotEmpty() }
            .map { File(it, "tailscale") }
        return (onPath + places.map(::File)).firstOrNull { it.isFile && it.canExecute() }
    }

    private fun started(binary: File): Process? = Cancellables.runCatchingCancellable {
        ProcessBuilder(binary.path, "status", "--json").redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }.fold(onSuccess = { it }, onFailure = { null })

    /** What it printed; it has exited, so the rest of its output is already in the pipe. */
    private fun read(printed: CompletableFuture<String>): String? =
        try {
            printed.get(TAILSCALE_STATUS_MS, TimeUnit.MILLISECONDS)
        } catch (_: ExecutionException) {
            null
        } catch (_: TimeoutException) {
            null
        }
}

// why: `tailscale status --json` answers from the local tailscaled in milliseconds; a run still going after a second
// and a half is stuck, and the scan it serves has two and a half seconds in all.
private const val TAILSCALE_STATUS_MS = 1_500L

// why: where Tailscale's own installer and a source build link the CLI on macOS, outside launchd's PATH.
private const val TAILSCALE_USR_LOCAL = "/usr/local/bin/tailscale"

// why: Homebrew's tailscale formula on Apple silicon.
private const val TAILSCALE_HOMEBREW = "/opt/homebrew/bin/tailscale"

// why: the Mac app (App Store and standalone) answers as the CLI when run from inside its bundle.
private const val TAILSCALE_MAC_APP = "/Applications/Tailscale.app/Contents/MacOS/Tailscale"
