package splice.codemode.nativehost

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Owns blocking stdio and the child process; protocol routing belongs to its connection. */
internal class NativeHostTransport(
    private val process: Process,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val connection: NativeConnection,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val writer = Mutex()
    private val output = DataOutputStream(process.outputStream)

    fun start() {
        scope.launch {
            try {
                DataInputStream(process.inputStream).use { input ->
                    while (!closed.get()) connection.receive(NativeFrames.read(input))
                }
            } catch (error: IOException) {
                failed(error)
            } catch (error: IllegalArgumentException) {
                failed(error)
            } catch (error: IllegalStateException) {
                failed(error)
            }
        }
        scope.launch {
            try {
                process.errorStream.use { it.transferTo(OutputStream.nullOutputStream()) }
            } catch (error: IOException) {
                failed(error)
            }
        }
    }

    suspend fun send(payload: ByteArray, operation: NativeOperation?) {
        withContext(ioDispatcher) {
            writer.withLock {
                if (closed.get()) throw CodeModeWorkerLostException()
                try {
                    operation?.markDispatched()
                    NativeFrames.write(output, payload)
                } catch (error: IOException) {
                    failed(error)
                    throw CodeModeWorkerLostException(error)
                }
            }
        }
    }

    private fun failed(error: Throwable) {
        if (!closed.get()) connection.fail(error)
    }

    fun afterExit(action: Runnable) {
        process.onExit().thenRun(action)
    }

    fun isAvailable(): Boolean = !closed.get() && process.isAlive

    override fun close() {
        if (closed.compareAndSet(false, true)) process.destroyForcibly()
    }
}
