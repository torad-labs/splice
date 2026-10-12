// The handover this whole package exists for, driven the way systemd drives it: a socket manager holds the listening
// socket, a daemon serves on it, that daemon goes away, and the next one serves on the same socket without a single
// connection being refused. The manager here is this test: it opens the listener and keeps it, exactly as PID 1 does.
package splice.http.listen

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollIoHandler
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.channel.socket.SocketProtocolFamily
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val CONTROL = "control"
private const val GRACE_MS = 100L
private const val TIMEOUT_MS = 15_000L
private const val QUIET_MS = 1_500

@Timeout(60)
class DescriptorAdoptionTest {

    @BeforeEach
    fun epollIsThere() {
        assertTrue(
            Epoll.isAvailable(),
            "the native transport must load, or adoption cannot be tested: ${Epoll.unavailabilityCause()}",
        )
    }

    /** The socket manager: a listening loopback socket this test keeps, whose descriptor it hands over. */
    private class Manager {
        private val loops = MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory())

        // INET, not Netty's dual-stack default: systemd's ListenStream=127.0.0.1:PORT makes an AF_INET socket, and an
        // IPv4 socket is what /proc/net/tcp lists and what the validation accepts.
        val channel: EpollServerSocketChannel = EpollServerSocketChannel(SocketProtocolFamily.INET).also { channel ->
            // AUTOREAD OFF, which is what makes this a manager and not a server: systemd holds the listening socket
            // and never accepts on it, so connections queue in the kernel until a daemon adopts the descriptor and
            // accepts them. A manager that accepts would answer nothing and swallow the very connection under test.
            channel.config().isAutoRead = false
            loops.register(channel).sync()
            channel.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).sync()
        }
        val fd: Int get() = channel.fd().intValue()
        val port: Int get() = (channel.localAddress() as InetSocketAddress).port

        fun close() {
            channel.close().sync()
            loops.shutdownGracefully()
        }
    }

    /** A handler held in flight: [entered] opens when it is reached, [release] lets it finish. */
    private data class Hold(val release: CountDownLatch, val entered: CountDownLatch)

    private fun serve(
        port: Int,
        adopted: AdoptedListeners,
        body: String,
        bootstrap: AdoptedBootstrap = adopted.bootstrapFor(CONTROL),
        hold: Hold? = null,
    ): EmbeddedServer<*, *> {
        val server = embeddedServer(
            Netty,
            configure = {
                connector {
                    host = "127.0.0.1"
                    this.port = port
                }
                configureBootstrap = { bootstrap.applyTo(this) }
            },
        ) {
            routing {
                get("/hi") { call.respondText(body) }
                get("/slow") {
                    hold?.entered?.countDown()
                    hold?.release?.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    call.respondText("slow-done")
                }
            }
        }
        server.start(wait = false)
        return server
    }

    private fun ready(fd: Int, port: Int): AdoptedListeners {
        val adoption = DescriptorAdoption().adopt(mapOf(CONTROL to fd), mapOf(CONTROL to port))
        return assertInstanceOf<Adoption.Ready>(adoption).listeners
    }

    private fun ask(port: Int): String = Socket().use { client ->
        client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), TIMEOUT_MS.toInt())
        get(client)
    }

    private fun request(path: String): ByteArray =
        "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray()

    private fun get(client: Socket, send: Boolean = true): String {
        client.soTimeout = TIMEOUT_MS.toInt()
        if (send) {
            client.getOutputStream().write(request("/hi"))
            client.getOutputStream().flush()
        }
        return client.getInputStream().readBytes().decodeToString()
    }

    @Test
    fun `a client that connects while no daemon is serving is answered by the next one`() {
        val manager = Manager()
        try {
            val adopted = ready(manager.fd, manager.port)
            val first = serve(manager.port, adopted, "first")
            assertTrue(ask(manager.port).endsWith("first"), "the adopted socket must serve before the gap")
            first.stop(GRACE_MS, TIMEOUT_MS)

            // THE GAP. Nothing is serving this port, and the connect must still be accepted: the manager holds the
            // listener, so the client waits in the kernel's accept queue instead of being refused.
            val waiting = Socket()
            waiting.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), manager.port), TIMEOUT_MS.toInt())
            val second = serve(manager.port, adopted, "second")
            waiting.use {
                val answer = get(it)
                assertTrue(answer.startsWith("HTTP/1.1 200"), "the connection opened in the gap was answered: $answer")
                assertTrue(answer.endsWith("second"), "and answered by the daemon that came up after it: $answer")
            }
            second.stop(GRACE_MS, TIMEOUT_MS)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `an engine stop leaves the manager's socket listening, so the same descriptor serves again`() {
        // A head restarted from the console is this case: within ONE process, the engine goes down and comes back. The
        // descriptor must survive, because the manager still holds the port and a second bind would be refused.
        val manager = Manager()
        try {
            val adopted = ready(manager.fd, manager.port)
            repeat(2) { round ->
                val engine = serve(manager.port, adopted, "round $round")
                assertTrue(ask(manager.port).endsWith("round $round"), "round $round must be served on the same socket")
                engine.stop(GRACE_MS, TIMEOUT_MS)
            }
            assertTrue(manager.channel.isActive, "the manager's own channel is untouched by the engines over it")
        } finally {
            manager.close()
        }
    }

    @Test
    fun `a draining engine stops accepting, finishes its turn, and the next engine answers the queued client`() {
        val manager = Manager()
        try {
            val adopted = ready(manager.fd, manager.port)
            val bootstrap = adopted.bootstrapFor(CONTROL)
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val draining = serve(manager.port, adopted, "draining", bootstrap, Hold(release, entered))

            // A turn in flight when the drain starts: connected and asking before accepting stops.
            val inFlight = Socket()
            inFlight.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), manager.port), TIMEOUT_MS.toInt())
            inFlight.getOutputStream().write(request("/slow"))
            inFlight.getOutputStream().flush()
            assertTrue(entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "the in-flight turn reached the handler")

            bootstrap.pauseAccepting()

            // A request queued at the start of a long drain: the kernel completes the connect, nobody accepts it.
            val queued = Socket()
            queued.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), manager.port), TIMEOUT_MS.toInt())
            queued.getOutputStream().write(request("/hi"))
            queued.getOutputStream().flush()
            queued.soTimeout = QUIET_MS
            assertThrows<SocketTimeoutException>("a paused engine must not answer a new connection") {
                queued.getInputStream().read()
            }

            // The drain finishes the turn it holds, whole, on the connection it already had.
            release.countDown()
            inFlight.use { assertTrue(get(it, send = false).endsWith("slow-done"), "the in-flight turn must finish") }

            // Stopping a paused engine closes its channel a second time; that must not fail, and the descriptor must
            // stay listening for the daemon that comes next, which answers the connection that waited the whole time.
            draining.stop(GRACE_MS, TIMEOUT_MS)
            assertTrue(manager.channel.isActive, "the manager's socket outlives the paused engine")
            val next = serve(manager.port, adopted, "next")
            queued.use {
                it.soTimeout = TIMEOUT_MS.toInt()
                val answer = it.getInputStream().readBytes().decodeToString()
                assertTrue(answer.startsWith("HTTP/1.1 200"), "the queued request was answered, not reset: $answer")
                assertTrue(answer.endsWith("next"), "and answered by the engine that came up after the drain: $answer")
            }
            next.stop(GRACE_MS, TIMEOUT_MS)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `a hand-off that is not a listening loopback socket on the resolved port is refused, with the reason`() {
        val manager = Manager()
        try {
            val wrongPort = DescriptorAdoption()
                .adopt(mapOf(CONTROL to manager.fd), mapOf(CONTROL to manager.port + 1))
            assertEquals(1, assertInstanceOf<Adoption.Refused>(wrongPort).reasons.size)
            assertTrue(assertInstanceOf<Adoption.Refused>(wrongPort).reasons.single().startsWith("wrong_endpoint"))

            // Descriptor 0 is this JVM's stdin: a real descriptor, not a socket, which is the shape a manager that
            // passed the wrong thing produces.
            val notASocket = DescriptorAdoption().adopt(mapOf(CONTROL to 0), mapOf(CONTROL to 1))
            assertTrue(assertInstanceOf<Adoption.Refused>(notASocket).reasons.single().startsWith("malformed"))
        } finally {
            manager.close()
        }
    }
}
