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
private const val EOF_GRACE_MS = 4000L // why: the 2 s stop hook gets its full budget before TERM

internal interface ModProcessObserver {
    fun ready(process: ModProcess, loaded: ModLoad.Ready)
    fun disconnected(process: ModProcess)
}

internal sealed class ModExit {
    data class Failed(val why: String) : ModExit()
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
    private val tree: ModProcessTree,
) {
    private val receiver = ModReceiver(name, pipe, calls, output.log, output.drops)
    private val pipes = io.limitedParallelism(PIPE_LANES)
    val pid: Long get() = receiver.hostPid ?: process.pid()
    val lastError: String? get() = receiver.lastError

    fun exitReason(): String {
        val detail = receiver.lastStderr?.let { ", \"$it\"" }.orEmpty()
        return "exit code ${receiver.hostExit ?: process.exitValue()}$detail"
    }

    suspend fun run(stop: CompletableDeferred<String>, observer: ModProcessObserver): ModExit {
        val exited = process.onExit().asDeferred()
        // Cancelling a blocking JDK pipe read does not close an inherited write end.
        val wiring = LifecycleScope(pipes)
        val writer = wiring.launch { io("write") { pipe.write(process.outputStream, calls) } }
        val stdout = wiring.launch { io("read stdout") { ModLineReader(process.inputStream).drain(receiver::stdout) } }
        val stderr = wiring.launch { io("read stderr") { ModLineReader(process.errorStream).drain(receiver::stderr) } }
        return try {
            supervise(stop, exited, stdout, observer)
        } finally {
            withContext(NonCancellable) {
                observer.disconnected(this@ModProcess)
                val why = if (stop.isCompleted) stop.await() else "daemon stop"
                try {
                    close(why, exited)
                    withTimeoutOrNull(PIPE_DRAIN_MS) { joinAll(writer, stdout, stderr) }
                } finally {
                    wiring.cancel()
                    output.log.reportDrops(flush = true)
                }
            }
        }
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

    private suspend fun close(reason: String, exited: Deferred<Process>) {
        pipe.finish(reason)
        if (process.isAlive) withTimeoutOrNull(EOF_GRACE_MS) { exited.await() }
        if (!tree.terminate()) output.log.write("A process of this mod could not be stopped and is left running.")
        pipe.close()
    }

    private inline fun io(action: String, body: () -> Unit) {
        Cancellables.runCatchingCancellable(body).onFailure {
            if (process.isAlive) output.log.write("Could not $action the mod pipe: ${SafeFailureText.render(it)}")
        }
    }
}
