// NEW: P0-ENG spike — does the Ktor server engine flush SSE frames immediately?
// Netty vs CIO, measured by a raw java.net.Socket client timestamping frame arrival
// against a route that drips one frame every DRIP_MS via respondBytesWriter + flush().
// PASS bar (per ledger slot): every frame arrives < 100ms after its send tick.
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test

private const val FRAMES = 10
private const val DRIP_MS = 300L
private const val PASS_JITTER_MS = 100L

class SseFlushSpike {

    @Test
    fun `netty vs cio sse flush timing`() {
        val nettyReport = measureEngine("netty", 39231) { port ->
            embeddedServer(io.ktor.server.netty.Netty, port = port, host = "127.0.0.1") { dripModule() }
        }
        val cioReport = measureEngine("cio", 39232) { port ->
            embeddedServer(io.ktor.server.cio.CIO, port = port, host = "127.0.0.1") { dripModule() }
        }

        val results = File(System.getProperty("spike.results.dir"))
        results.mkdirs()
        File(results, "sse-flush.md").writeText(
            buildString {
                appendLine("# P0-ENG receipt: server engine SSE flush timing (${java.time.LocalDate.now()})")
                appendLine()
                appendLine("Route drips $FRAMES SSE frames at ${DRIP_MS}ms intervals via respondBytesWriter+flush();")
                appendLine("raw Socket client timestamps arrival. PASS = inter-arrival jitter vs the ${DRIP_MS}ms tick")
                appendLine("stays < ${PASS_JITTER_MS}ms for every frame (i.e., no buffering/coalescing).")
                appendLine()
                appendLine(nettyReport)
                appendLine(cioReport)
                appendLine("## Verdict")
                appendLine(verdict(nettyReport, cioReport))
            },
        )
        println(File(results, "sse-flush.md").readText())
    }

    private fun io.ktor.server.application.Application.dripModule() {
        routing {
            get("/drip") {
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    repeat(FRAMES) { i ->
                        writeStringUtf8("event: tick\ndata: {\"i\":$i}\n\n")
                        flush()
                        delay(DRIP_MS)
                    }
                }
            }
        }
    }

    private fun measureEngine(
        name: String,
        port: Int,
        start: (Int) -> EmbeddedServer<*, *>,
    ): String {
        val server = start(port)
        server.start(wait = false)
        Thread.sleep(600) // engine warmup
        val arrivals = mutableListOf<Long>()
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.tcpNoDelay = true
                socket.getOutputStream().write(
                    "GET /drip HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAccept: text/event-stream\r\n\r\n"
                        .toByteArray(),
                )
                socket.getOutputStream().flush()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val t0 = System.nanoTime()
                var line: String?
                while (arrivals.size < FRAMES) {
                    line = reader.readLine() ?: break
                    if (line.startsWith("data:")) arrivals.add((System.nanoTime() - t0) / 1_000_000)
                }
            }
        } finally {
            server.stop(100, 500)
        }
        val deltas = arrivals.zipWithNext { a, b -> b - a }
        val maxJitter = deltas.maxOfOrNull { kotlin.math.abs(it - DRIP_MS) } ?: Long.MAX_VALUE
        return buildString {
            appendLine("## $name")
            appendLine("- frames received: ${arrivals.size}/$FRAMES")
            appendLine("- arrival ms since first byte: $arrivals")
            appendLine("- inter-arrival deltas ms (expect ~$DRIP_MS): $deltas")
            appendLine("- max jitter vs tick: ${maxJitter}ms -> ${if (arrivals.size == FRAMES && maxJitter < PASS_JITTER_MS) "PASS" else "FAIL"}")
        }
    }

    private fun verdict(vararg reports: String): String {
        val netty = reports[0].contains("PASS")
        val cio = reports[1].contains("PASS")
        return when {
            netty && cio -> "Both engines flush per-frame on this Ktor version. Plan default stays NETTY (documented CIO flush regressions history: ktor#1199, KTOR-7324); CIO empirically fine today."
            netty -> "NETTY confirmed (CIO buffered/failed — matches its documented history). Use Netty."
            cio -> "Unexpected: CIO passed but Netty failed — investigate before P2-EMIT."
            else -> "BOTH failed the jitter bar — investigate respondBytesWriter/flush usage before P2-EMIT."
        }
    }
}
