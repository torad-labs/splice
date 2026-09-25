// NEW: the production side of RigPort — rig, its installer and nvidia-smi run as child processes.
// Nothing here decides anything: each call hands back the exit code and both streams, and the
// local-model step (SetupLocalModel) reads them against rig's contract.
//
// BOUNDED where it can be, and `rig up` is the one call that is not: it downloads ~8 GB and may
// compile llama.cpp for 5-20 minutes, and it narrates every step on stderr, so the operator watches
// it move and Ctrl-C stops it (rig exits 130 on SIGINT). A deadline there would kill a healthy
// download on a slow link. Every other call answers in seconds, and a hang there must not hang the
// wizard.
package splice.app.cli.setup

import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** What a JVM reports for a command it could not start: the shell's own "command not found" code,
 *  so a missing rig reads the same here as it would at a prompt. */
private const val NOT_STARTED = 127

/** The shell's `timeout` convention for a command killed at its deadline, so the number means the
 *  same thing to an operator who has seen it from coreutils. */
private const val KILLED_AT_DEADLINE = 124

/** `--version` and `describe` read files and print; thirty seconds is slack for a cold disk, and
 *  anything slower is a hang the wizard must not wait on. */
private const val QUICK_TIMEOUT_MS = 30_000L

/** `prepare` runs nvidia-smi and probes each build tool once; two minutes covers a driver that is
 *  slow to wake the card on first query. */
private const val PREPARE_TIMEOUT_MS = 120_000L

/** The installer fetches one release tarball (tens of MB) and checks its sha256; ten minutes covers
 *  a slow link without letting a stalled download hold the wizard forever. */
private const val INSTALL_TIMEOUT_MS = 600_000L

/** nvidia-smi answers in well under a second on a working driver; ten seconds is a wedged one. */
private const val SMI_TIMEOUT_MS = 10_000L

internal class ProcessRig(
    private val env: EnvReader,
    private val processes: ChildProcesses = ChildProcesses(),
) : Rig {

    override fun version(): RigRun = processes.run(rig("--version"), QUICK_TIMEOUT_MS)

    override fun install(): RigRun = processes.run(listOf("sh", "-c", RIG_INSTALL), INSTALL_TIMEOUT_MS)

    override fun prepare(): RigRun = processes.run(rig("prepare", "--json"), PREPARE_TIMEOUT_MS)

    override fun up(head: String, progress: RigProgress): RigRun =
        processes.streamed(rig("up", head, "--json"), progress)

    override fun describe(head: String): RigRun = processes.run(rig("describe", head), QUICK_TIMEOUT_MS)

    /** PATH first, then the installer's own link, ~/.local/bin/rig — found even before the operator's
     *  shell has ~/.local/bin on PATH, which rig's installer only warns about. */
    private fun rig(vararg args: String): List<String> {
        val onPath = env("PATH").orEmpty().split(':').filter { it.isNotEmpty() }
            .map { dir -> Paths.get(dir, RIG_BINARY) }
            .firstOrNull { Files.isExecutable(it) }
        val local = Paths.get(System.getProperty("user.home"), ".local", "bin", RIG_BINARY)
        return listOf((onPath ?: local).toString()) + args
    }
}

/** nvidia-smi's card list, one name a line. Any failure — no binary, no driver, a non-zero exit — is
 *  "no card", which is the answer that keeps the question from being asked. */
internal class NvidiaSmi(private val processes: ChildProcesses = ChildProcesses()) : GpuProbe {

    override fun invoke(): List<String> {
        val run = processes.run(listOf("nvidia-smi", "--query-gpu=name", "--format=csv,noheader"), SMI_TIMEOUT_MS)
        return if (run.exit == 0) run.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }
}

/** Runs one child with its stdin closed (nothing here answers a prompt) and both streams captured. */
internal class ChildProcesses {

    /** Bounded: at [timeoutMs] the child is killed and the run answers [KILLED_AT_DEADLINE]. Both
     *  streams drain on their own threads, so a chatty child cannot fill a pipe and stall. */
    fun run(command: List<String>, timeoutMs: Long): RigRun = started(command) { process ->
        val out = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        val err = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
        if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            RigRun(process.exitValue(), out.get(), err.get())
        } else {
            process.destroyForcibly()
            val seconds = TimeUnit.MILLISECONDS.toSeconds(timeoutMs)
            RigRun(KILLED_AT_DEADLINE, "", "${label(command)} did not finish within $seconds s")
        }
    }

    /** Unbounded, with stderr handed to [progress] line by line as the child writes it. */
    fun streamed(command: List<String>, progress: RigProgress): RigRun = started(command) { process ->
        val out = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        val err = StringBuilder()
        process.errorStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                err.appendLine(line)
                progress(line)
            }
        }
        RigRun(process.waitFor(), out.get(), err.toString())
    }

    private inline fun started(command: List<String>, body: (Process) -> RigRun): RigRun = try {
        val process = ProcessBuilder(command).start()
        process.outputStream.close()
        body(process)
    } catch (e: IOException) {
        RigRun(NOT_STARTED, "", "${label(command)} could not start: ${SafeFailureText.render(e)}")
    }

    /** The command as the operator would type it: the binary's file name, not its absolute path. */
    private fun label(command: List<String>): String =
        (listOf(Path.of(command.first()).fileName.toString()) + command.drop(1)).joinToString(" ")
}

private const val RIG_BINARY = "rig"
