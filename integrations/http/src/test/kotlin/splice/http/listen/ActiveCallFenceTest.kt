// #525 item 2: stopping a Ktor/Netty server while a streaming route is still open unwinds active calls before
// Netty shuts down its event loops, so NettyApplicationResponse.cancel never posts to a terminated executor and
// logs zero SEVERE RejectedExecutionException stack traces.
package splice.http.listen

import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

internal class ActiveCallFenceTest {
    @Test
    fun `stopping a Netty server with an active streaming call logs no RejectedExecutionException`() = runBlocking {
        val fence = ActiveCallFence()
        val entered = CountDownLatch(1)
        val severe = Collections.synchronizedList(mutableListOf<String>())
        val promiseLogger = Logger.getLogger("io.netty.util.concurrent.DefaultPromise")
        val handler = captureSevere(severe)
        promiseLogger.addHandler(handler)
        val server = streamingServer(fence, entered)
        try {
            fence.open()
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().single().port
            Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().write("GET /stream HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS), "streaming route started")
                fence.cancelAndAwait(UNWIND_MS)
                server.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
            }
            assertEquals(emptyList<String>(), severe.toList(), "Netty logged SEVERE during shutdown: $severe")
        } finally {
            promiseLogger.removeHandler(handler)
            server.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        }
    }

    private fun streamingServer(
        fence: ActiveCallFence,
        entered: CountDownLatch,
    ): EmbeddedServer<NettyApplicationEngine, *> = embeddedServer(
        Netty,
        serverConfig {
            module {
                intercept(ApplicationCallPipeline.Call) {
                    fence.track(coroutineContext[Job]) {
                        withContext(Dispatchers.IO) { proceed() }
                    }
                }
                routing {
                    get("/stream") {
                        entered.countDown()
                        call.respondTextWriter {
                            write("open\n")
                            flush()
                            delay(HOLD_MS)
                        }
                    }
                }
            }
        },
    ) {
        connector {
            host = "127.0.0.1"
            port = 0
        }
        shutdownGracePeriod = STOP_GRACE_MS
        shutdownTimeout = STOP_TIMEOUT_MS
    }

    private fun captureSevere(into: MutableList<String>): Handler = object : Handler() {
        override fun publish(record: LogRecord) {
            if (record.level.intValue() >= Level.SEVERE.intValue()) {
                into += "${record.message} ${record.thrown}"
            }
        }

        override fun flush() = Unit

        override fun close() = Unit
    }
}

private const val HOLD_MS = 60_000L
private const val WAIT_MS = 5_000L
private const val UNWIND_MS = 500L
private const val STOP_GRACE_MS = 50L
private const val STOP_TIMEOUT_MS = 200L
