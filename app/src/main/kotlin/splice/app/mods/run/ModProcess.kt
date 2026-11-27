package splice.app.mods.run

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.upstream.LifecycleScope

// why: the writer and two drains block independently of other mods and the daemon's I/O lane
private const val PIPE_LANES = 3
private const val LOAD_MS = 30_000L // why: an import that never registers cannot ever accept events
private const val PIPE_DRAIN_MS = 1000L // why: a foreign process may still hold the write end
private const val EOF_GRACE_MS = 4000L // why: allow the guardian to close its group after the host's stop hook
private const val GUARDIAN_START_MS = 5000L // why: an unsuccessful spawn must not hold the mod's pipe
private const val MOD_GUARDIAN_SIGNAL_EXIT = 128 // why: Unix Process reports a signal as 128 plus its number

internal interface ModProcessObserver {
    fun ready(process: ModProcess, loaded: ModLoad.Ready)
    fun disconnected(process: ModProcess)
}

internal sealed class ModExit {
    data class Failed(val why: String) : ModExit()
    data class Incomplete(val why: String, val group: Long) : ModExit()
    data object Crashed : ModExit()
    data object Stopped : ModExit()
}

internal class ModProcess(
    private val process: Process,
    private val name: String,
    private val calls: ModCalls,
    private val output: ModOutput,
    val pipe: ModPipe,
    private val io: CoroutineDispatcher,
    private val admission: ModRunAdmission,
) {
    private val receiver = ModReceiver(name, pipe, calls, output.log, output.drops)
    private val pipes = io.limitedParallelism(PIPE_LANES)

    @Volatile var pid: Long = process.pid()
        private set
    val lastError: String? get() = receiver.lastError

    fun exitReason(): String {
        val detail = receiver.lastStderr?.let { ", \"$it\"" }.orEmpty()
        return "exit code ${receiver.hostExit ?: process.exitValue()}$detail"
    }

    suspend fun run(stop: CompletableDeferred<String>, observer: ModProcessObserver): ModExit {
        val exited = process.onExit().asDeferred()
        // Cancelling a blocking JDK pipe read does not close an inherited write end.
        val wiring = LifecycleScope(pipes)
        val writer = wiring.launch { io("write") { write() } }
        val stdout = wiring.launch { io("read stdout") { ModLineReader(process.inputStream).drain(receiver::stdout) } }
        val stderr = wiring.launch { io("read stderr") { ModLineReader(process.errorStream).drain(receiver::stderr) } }
        var incomplete: ModExit.Incomplete? = null
        val outcome = try {
            supervise(stop, exited, stdout, observer)
        } finally {
            withContext(NonCancellable) {
                observer.disconnected(this@ModProcess)
                val why = if (stop.isCompleted) stop.await() else "daemon stop"
                try {
                    incomplete = close(why, exited, writer)
                    withTimeoutOrNull(PIPE_DRAIN_MS) { joinAll(writer, stdout, stderr) }
                } finally {
                    wiring.cancel()
                    output.log.reportDrops(flush = true)
                }
            }
        }
        return incomplete ?: outcome
    }

    private suspend fun supervise(
        stop: Deferred<String>,
        exited: Deferred<Process>,
        stdout: Job,
        observer: ModProcessObserver,
    ): ModExit {
        val loaded = withTimeoutOrNull(LOAD_MS) {
            select {
                stop.onAwait { null }
                receiver.loaded.onAwait { it }
                exited.onAwait {
                    withTimeoutOrNull(PIPE_DRAIN_MS) { stdout.join() }
                    if (receiver.loaded.isCompleted) receiver.loaded.await() else null
                }
            }
        }
        return when {
            stop.isCompleted -> ModExit.Stopped
            loaded is ModLoad.Failed -> ModExit.Failed(loaded.why)
            loaded is ModLoad.Ready -> {
                observer.ready(this, loaded)
                select {
                    stop.onAwait { ModExit.Stopped }
                    exited.onAwait { ModExit.Crashed }
                }
            }
            exited.isCompleted -> ModExit.Crashed
            else -> ModExit.Failed("did not finish loading in 30 s")
        }
    }

    private suspend fun close(reason: String, exited: Deferred<Process>, writer: Job): ModExit.Incomplete? {
        pipe.finish(reason)
        if (withTimeoutOrNull(PIPE_DRAIN_MS) { writer.join() } == null) {
            // Close only the input. A blocked JDK writer may hold its stream monitor, so do not join this close.
            Thread.startVirtualThread {
                Cancellables.discard(
                    Cancellables.runCatchingCancellable { process.outputStream.close() },
                    "the guardian's exit below tells us whether closing its input worked",
                )
            }
        }
        val status = withTimeoutOrNull(EOF_GRACE_MS) { exited.await().exitValue() }
        pipe.close()
        val failure = when {
            status == null -> "the guardian did not exit after stdin closed"
            status >= MOD_GUARDIAN_SIGNAL_EXIT -> "the guardian was killed"
            status != 0 -> "the guardian exited before confirming cleanup"
            else -> return null
        }
        val group = pid
        return ModExit.Incomplete("$failure; group $group may still be running", group)
    }

    private suspend fun write() {
        process.outputStream.use { stream ->
            val host = withTimeoutOrNull(GUARDIAN_START_MS) { receiver.child.await() } ?: return
            admission.host(host)
            pid = host
            pipe.write(stream, calls)
        }
    }

    private inline fun io(action: String, body: () -> Unit) {
        Cancellables.runCatchingCancellable(body).onFailure {
            if (process.isAlive) output.log.write("Could not $action the mod pipe: ${SafeFailureText.render(it)}")
        }
    }
}
