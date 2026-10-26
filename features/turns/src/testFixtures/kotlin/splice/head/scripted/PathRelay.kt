// NEW: failure-handling slice 1, the harness. A TCP relay between a head and the scripted provider, so a test can
// make the provider's address refuse new connections while the exchanges already open keep running. That is the one
// path a silent round is reaped on: the watchdog holds a round whose connection is open and whose provider still
// answers a connect (Watchdog.kt launchIn, PathLiveness.agrees, UpstreamTransport.reachabilityProbe).
package splice.head.scripted

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** How many connections may wait to be accepted at once. */
private const val BACKLOG = 50

/** How much one read moves from one side of a relayed connection to the other. */
private const val CHUNK_BYTES = 8_192

/** Relays every connection made to [port] to the provider on [targetPort], byte for byte, until either side ends. */
public class PathRelay(private val targetPort: Int) : AutoCloseable {
    private val loopback = InetAddress.getLoopbackAddress()
    private val listener = ServerSocket(0, BACKLOG, loopback)
    private val sockets = CopyOnWriteArrayList<Socket>()

    /** The port a head dials instead of the provider's. */
    public val port: Int = listener.localPort

    init {
        spawn("path-relay-$port") { accept() }
    }

    /** Stops answering new connections: a connect to [port] is refused from now on, while every connection already
     *  relayed keeps carrying its exchange. */
    public fun stopListening() {
        listener.close()
    }

    override fun close() {
        listener.close()
        sockets.forEach(Socket::close)
    }

    private fun accept() {
        while (!listener.isClosed) {
            val inbound = acceptOne() ?: return
            val outbound = Socket(loopback, targetPort)
            sockets += inbound
            sockets += outbound
            pump(inbound, outbound)
            pump(outbound, inbound)
        }
    }

    /** The next connection, or null once the listener is closed. */
    private fun acceptOne(): Socket? = if (listener.isClosed) {
        null
    } else {
        try {
            listener.accept()
        } catch (closed: IOException) {
            if (listener.isClosed) null else throw closed
        }
    }

    /** Copies [from] into [to] until [from] ends, then ends [to]'s output so the far side sees the end too. */
    private fun pump(from: Socket, to: Socket) {
        spawn("path-relay-$port-pump") {
            try {
                copy(from.getInputStream(), to.getOutputStream())
                to.shutdownOutput()
            } catch (ignored: IOException) {
                // One side reset or closed: end both, as a dropped connection ends for whoever is still reading.
                from.close()
                to.close()
            }
        }
    }

    /** Runs [body] on a daemon thread of its own, so a relay left open never holds the test JVM. */
    private fun spawn(name: String, body: () -> Unit) {
        val worker = Thread(body, name)
        worker.isDaemon = true
        worker.start()
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(CHUNK_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
            output.flush()
        }
    }
}
