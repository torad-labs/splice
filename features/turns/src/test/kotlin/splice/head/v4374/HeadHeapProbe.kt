package splice.head.v4374

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.WindowRule
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.head.HeadServer
import splice.head.TestResponsesProvider
import splice.head.admission.MATERIALIZATION_RESIDENT_BYTES
import splice.head.awaitListening
import splice.head.headDeps
import splice.upstream.ProviderTuning
import java.lang.ref.Reference
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

private const val ANSWER_SECONDS = 300L

private class ProbeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-test", "acct-test")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

/** Run in a second JVM by RequestHeapBudgetTest, at the heap the daemon runs with: a head on its default
 *  policy takes args[0] Messages requests of args[1] bytes AT ONCE, and the process exits 0 only when every
 *  one is answered with a completed turn. The sender streams its bodies and the upstream drains and
 *  discards them, so what this JVM's heap holds is the head's own footprint: the request bytes, the text
 *  decoded from them, the parsed tree and the translated request, per request in flight.
 *  args[2] is the scratch directory the head keeps its state in. */
object HeadHeapProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val resident = ByteArray(MATERIALIZATION_RESIDENT_BYTES.toInt())
        val ok = run(args[0].toInt(), args[1].toInt(), Path.of(args[2]))
        Reference.reachabilityFence(resident)
        System.out.flush()
        exitProcess(if (ok) 0 else 1)
    }

    private fun run(requests: Int, bytes: Int, tmp: Path): Boolean {
        val started = System.nanoTime()
        val upstream = drainingUpstream(started)
        val head = head(upstream.address.port, tmp)
        try {
            runBlocking { head.start() }
            awaitListening(head.port)
            val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
            val answers = List(requests) { index ->
                send(client, head.port, bytes).whenComplete { response, failure ->
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    println(
                        "answer $index: elapsed_ms=$elapsed status=${response?.statusCode()} " +
                            "failure=${failure?.javaClass?.name}",
                    )
                }
            }
            CompletableFuture.allOf(*answers.toTypedArray()).get(ANSWER_SECONDS, TimeUnit.SECONDS)
            val done = answers.map { it.get() }
            done.forEachIndexed { i, response ->
                val body = response.body()
                val shown = if (body.contains("message_stop")) body.take(BODY_ECHO) else body
                println("request $i: ${response.statusCode()} $shown")
            }
            return done.all { it.statusCode() == 200 && it.body().contains("message_stop") }
        } finally {
            runBlocking { head.stop() }
            upstream.stop(0)
        }
    }

    private fun send(client: HttpClient, port: Int, bytes: Int): CompletableFuture<HttpResponse<String>> {
        val body = ScreenshotBody(bytes)
        val publisher = HttpRequest.BodyPublishers.fromPublisher(
            HttpRequest.BodyPublishers.ofInputStream { body.stream() },
            bytes.toLong(),
        )
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/messages"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer test-inference-token")
            .POST(publisher)
            .build()
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun head(upstreamPort: Int, tmp: Path): HeadServer {
        val catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
            windowRules = listOf(WindowRule("gpt-5.6", 272_000)),
            defaultContextWindow = 272_000,
        )
        val provider = TestResponsesProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = "gpt-5.6-sol",
                auth = ProbeAuth(),
                baseUrl = "http://127.0.0.1:$upstreamPort",
                watchdog = WatchdogBudget(30.seconds, 30.seconds, 60.seconds),
                loginCommand = "claudex login",
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        )
        return HeadServer(provider = provider, listenPort = 0, deps = headDeps(tmp = tmp))
    }

    /** A ChatGPT stand-in that reads the request in 64 KiB pieces and keeps none of it. */
    private fun drainingUpstream(started: Long): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val arrivals = AtomicInteger()
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            val index = arrivals.getAndIncrement()
            val arrived = System.nanoTime()
            println("upstream $index: arrived_ms=${TimeUnit.NANOSECONDS.toMillis(arrived - started)}")
            val piece = ByteArray(DRAIN_BYTES)
            var received = 0L
            exchange.requestBody.use { input ->
                var count = input.read(piece)
                while (count >= 0) {
                    received += count
                    count = input.read(piece)
                }
            }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - arrived)
            println("upstream $index: drained_bytes=$received drain_ms=$elapsed")
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                STREAM.forEach { out.write("data: $it\n\n".toByteArray()) }
                out.write("data: [DONE]\n\n".toByteArray())
            }
        }
        server.start()
        return server
    }
}

private const val DRAIN_BYTES = 64 * 1024
private const val BODY_ECHO = 120

private val STREAM = listOf(
    """{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""",
    """{"type":"response.output_text.delta","output_index":0,"delta":"ok"}""",
    """{"type":"response.output_item.done","output_index":0}""",
    """{"type":"response.completed","response":{"id":"r1","status":"completed","output":[],""" +
        """"usage":{"input_tokens":10,"output_tokens":5}}}""",
)
