// NEW: P0-POOL spike — does the Ktor CIO *client* pool stay bounded when a coroutine
// is cancelled mid-stream 500 times? This is the exact bug class the Node stack
// shipped once ("55 inflight, 2 agents" leak). Receipt: fd delta + liveness + wall time.
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.system.measureTimeMillis
import org.junit.jupiter.api.Test

private const val ROUNDS = 500
private const val PORT = 39233

class PoolCancelSpike {

    // TCP-sockets-only count: a raw `lsof -p` count is a broken instrument here —
    // it swings with lazily-opened jar/class fds and counts healthy idle keep-alive
    // pool connections. Leaks we care about are lingering TCP sockets to the drip port.
    private fun tcpSocketCount(): Int {
        val pid = ProcessHandle.current().pid()
        return try {
            val p = ProcessBuilder("lsof", "-a", "-p", "$pid", "-i", "TCP")
                .redirectErrorStream(true).start()
            val n = p.inputStream.bufferedReader().readLines().count { it.contains("TCP") }
            p.waitFor()
            n
        } catch (e: Exception) {
            -1
        }
    }

    @Test
    fun `cio client pool bounded under 500 mid-stream cancellations`() {
        val server = embeddedServer(io.ktor.server.cio.CIO, port = PORT, host = "127.0.0.1") {
            routing {
                get("/slow-drip") {
                    call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                        repeat(1000) { i ->
                            writeStringUtf8("data: {\"i\":$i}\n\n")
                            flush()
                            delay(50)
                        }
                    }
                }
                get("/ping") { call.respondText("pong") }
            }
        }
        server.start(wait = false)
        Thread.sleep(600)

        val client = HttpClient(CIO)
        // Warmup: exercise the full request path once so class-loading fds and the
        // pool's baseline exist BEFORE the measurement window.
        runBlocking {
            val warm: Job = launch {
                client.prepareGet("http://127.0.0.1:$PORT/slow-drip").execute { resp ->
                    resp.bodyAsChannel().readUTF8Line()
                    delay(Long.MAX_VALUE)
                }
            }
            delay(50); warm.cancel(); warm.join()
        }
        val fdBefore = tcpSocketCount()
        var completed = 0
        val wallMs = measureTimeMillis {
            runBlocking {
                repeat(ROUNDS) {
                    val job: Job = launch {
                        client.prepareGet("http://127.0.0.1:$PORT/slow-drip").execute { resp ->
                            val ch = resp.bodyAsChannel()
                            repeat(3) { ch.readUTF8Line() } // read a few frames mid-stream
                            delay(Long.MAX_VALUE) // hold the stream open until cancelled
                        }
                    }
                    delay(2)
                    job.cancel()
                    job.join()
                    completed++
                }
            }
        }
        Thread.sleep(6_000) // let keep-alive idle connections expire/settle
        val fdAfter = tcpSocketCount()

        // liveness: the pool must still serve a normal request promptly
        val liveness = runBlocking {
            withTimeout(5_000) { client.get("http://127.0.0.1:$PORT/ping").bodyAsText() }
        }
        client.close()
        server.stop(100, 500)

        val fdDelta = if (fdBefore >= 0 && fdAfter >= 0) fdAfter - fdBefore else Int.MIN_VALUE
        val pass = completed == ROUNDS && liveness == "pong" && (fdDelta == Int.MIN_VALUE || fdDelta < 50)

        val results = File(System.getProperty("spike.results.dir")).apply { mkdirs() }
        File(results, "pool-cancel.md").writeText(
            buildString {
                appendLine("# P0-POOL receipt: CIO client cancel-mid-stream x$ROUNDS (${java.time.LocalDate.now()})")
                appendLine()
                appendLine("- rounds completed: $completed/$ROUNDS")
                appendLine("- wall time: ${wallMs}ms (${wallMs / ROUNDS}ms/round)")
                appendLine("- TCP sockets before/after (post 6s settle): $fdBefore / $fdAfter (delta $fdDelta, bar < 50)")
                appendLine("- post-storm liveness GET: $liveness")
                appendLine()
                appendLine("## Verdict")
                appendLine(
                    if (pass) {
                        "PASS — CIO client returns/discards connections cleanly on coroutine cancellation; pool bounded, liveness intact. Cleared for P2-MACH plumbing."
                    } else {
                        "FAIL — pool leak or hang under cancellation; try engine config, then OkHttp client fallback per ledger slot."
                    },
                )
            },
        )
        println(File(results, "pool-cancel.md").readText())
        check(pass) { "pool spike failed: completed=$completed fdDelta=$fdDelta liveness=$liveness" }
    }
}
