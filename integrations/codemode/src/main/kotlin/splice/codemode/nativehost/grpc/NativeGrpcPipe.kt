package splice.codemode.nativehost.grpc

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One owned byte connection carries HTTP/2 directly to the official host's grpc+stdio transport. */
internal class NativeGrpcPipe(
    private val process: Process,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val onFailure: NativeGrpcTransportFailure,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val accepted = AtomicReference<Socket?>()
    val address: InetSocketAddress = InetSocketAddress(listener.inetAddress, listener.localPort)

    fun start() {
        check(started.compareAndSet(false, true)) { "Native gRPC pipe was already started" }
        scope.launch(ioDispatcher) {
            try {
                listener.accept().use { socket ->
                    accepted.set(socket)
                    listener.close()
                    if (!closed.get()) {
                        socket.tcpNoDelay = true
                        coroutineScope {
                            launch { pump(socket.getInputStream(), process.outputStream) }
                            launch { pump(process.inputStream, socket.getOutputStream()) }
                        }
                    }
                }
            } catch (error: IOException) {
                failed(error)
            }
        }
        scope.launch(ioDispatcher) {
            try {
                process.errorStream.use { it.transferTo(OutputStream.nullOutputStream()) }
            } catch (error: IOException) {
                failed(error)
            }
        }
    }

    private fun pump(input: InputStream, output: OutputStream) {
        try {
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            while (!closed.get()) {
                val count = input.read(bytes)
                if (count < 0) break
                output.write(bytes, 0, count)
                // Process.outputStream is buffered: even a small gRPC control frame must make progress.
                output.flush()
            }
            failed(IOException("Native gRPC stdio connection ended"))
        } catch (error: IOException) {
            failed(error)
        }
    }

    private fun failed(error: IOException) {
        if (!closed.get()) onFailure(error)
    }

    fun isAvailable(): Boolean = !closed.get() && process.isAlive

    fun afterExit(action: Runnable) {
        process.onExit().thenRun(action)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            listener.close()
            accepted.getAndSet(null)?.close()
            process.destroyForcibly()
        }
    }
}
