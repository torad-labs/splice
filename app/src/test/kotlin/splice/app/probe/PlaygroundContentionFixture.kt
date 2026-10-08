// A real guarded control server, real perf history reads, and a mock-only upstream.
package splice.app.probe

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splice.app.control.ManagedHead
import splice.app.control.controlServerFor
import splice.app.sources.PerfRowsFileSource
import splice.app.sources.SyntheticPerfHistory
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.parse.AnthropicTurnBody
import splice.diagnostics.logs.HeadLogSource
import splice.diagnostics.playground.PlaygroundProbe
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.usage.perf.PerfRowsSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal const val CONTENTION_HEAD = "synthetic-playground"

// Phase separation needs real cold/warm reads, not the independently covered full-history scale.
private const val PROFILE_HISTORY_REQUESTS = 256

internal class PlaygroundContentionFixture(
    private val root: Path,
    scope: CoroutineScope,
    historyRequests: Int = PROFILE_HISTORY_REQUESTS,
) : AutoCloseable {
    val samples = PlaygroundPhaseSamples()

    @Volatile var headerDelayMs = 0L

    @Volatile var bodyDelayMs = 0L

    @Volatile var readStarted = CompletableDeferred<Unit>()
        private set

    @Volatile private var readReleased = CountDownLatch(1)

    private val history = SyntheticPerfHistory(root).apply { create(requests = historyRequests) }

    @Volatile private var source = PerfRowsFileSource(history.file)

    private val paths = StatePaths(baseOverride = root.resolve("state"))
    private val mgmt = MgmtKey(paths)

    // The enclosing profile budget owns cancellation, not CIO's shorter engine default.
    private val client = HttpClient(CIO) {
        engine { requestTimeout = PROFILE_TIMEOUT_MS }
    }
    private val upstream = HttpClient(
        MockEngine {
            samples.posted = System.nanoTime()
            delay(headerDelayMs)
            samples.headers = System.nanoTime()
            val body = ByteChannel(autoFlush = true)
            scope.launch {
                delay(bodyDelayMs)
                body.writeStringUtf8("""{"synthetic":"complete"}""")
                body.close()
            }
            respond(content = body, status = HttpStatusCode.OK)
        },
    )
    private val control = controlServerFor(
        port = 0,
        heads = mapOf(CONTENTION_HEAD to managed()),
        config = ConfigService(paths),
        mgmtKey = mgmt,
        log = {},
    )

    suspend fun start() {
        val actual = assembledProviders(root).getValue("platformy")
        val measured = object : Provider by actual {
            override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn =
                actual.buildTurn(body, compact, sessionId).also { samples.built = System.nanoTime() }
        }
        val registry = PlaygroundProviders().apply { register(CONTENTION_HEAD, measured) }
        val probe = UpstreamPlaygroundProbe(registry, upstream)
        control.ports.playground = PlaygroundProbe { head, prompt, model ->
            samples.routed = System.nanoTime()
            probe.run(head, prompt, model).also { samples.completed = System.nanoTime() }
        }
        control.start()
    }

    fun coldReads() {
        source = PerfRowsFileSource(history.file)
    }

    fun beginReads() {
        readStarted = CompletableDeferred()
        readReleased = CountDownLatch(1)
    }

    suspend fun controlRead(): String =
        client.get("http://127.0.0.1:${control.listeningPort}/api/perf/summary?window=7d") {
            header("Authorization", "Bearer ${mgmt.get()}")
        }.bodyAsText()

    suspend fun playground(): String {
        samples.started = System.nanoTime()
        // Pending console reads overlap this instant even when a small warm scan finishes early.
        // Release before POST: this is not a fabricated dependency in the Playground route.
        readReleased.countDown()
        val response = client.post("http://127.0.0.1:${control.listeningPort}/api/playground") {
            header("Authorization", "Bearer ${mgmt.get()}")
            contentType(ContentType.Application.Json)
            setBody("""{"head":"$CONTENTION_HEAD","prompt":"synthetic prompt"}""")
        }
        check(response.status == HttpStatusCode.OK) { "synthetic Playground returned ${response.status}" }
        return response.bodyAsText().also { samples.finished = System.nanoTime() }
    }

    private fun managed(): ManagedHead = ManagedHead(
        head = object : Head {
            override val key = CONTENTION_HEAD
            override val label = CONTENTION_HEAD
            override val port = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot() = HeadHealth(true, true, 0, "synthetic")
        },
        auth = playgroundHead("platformy").auth,
        usage = HeadUsageSource { UsageView(0, 0, null) },
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int) = ""
            override fun path() = ""
        },
        warnPct = 80,
        warnTokens5h = 0,
        perfRows = PerfRowsSource { since ->
            readStarted.complete(Unit)
            source.window(since).also {
                check(readReleased.await(PROFILE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    "the Playground sample did not release the synthetic console read"
                }
            }
        },
    )

    override fun close() {
        readReleased.countDown()
        control.stop()
        client.close()
        upstream.close()
    }
}
