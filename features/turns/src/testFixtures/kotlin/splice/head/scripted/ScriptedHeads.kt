// NEW: failure-handling slice 0, the harness. A real head of each API type in front of the scripted provider, on
// the virtual clock, built the way HeadServerEndingsTest builds its head (HeadDepsFixture's headDeps and headStores).
package splice.head.scripted

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import splice.core.auth.AuthDescription
import splice.core.auth.ClientAuthProvider
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.dialect.chat.ChatQuirks
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.awaitListening
import splice.head.headDeps
import splice.head.headStores
import splice.head.turn.LiveTurns
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.Waiter
import splice.upstream.retry.InflightGate
import splice.upstream.transport.RetryPacing
import splice.upstream.transport.UpstreamClient
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** How long a head waits on anything when a test does not say: longer than any test's virtual wait. */
private const val BUDGET_HOURS = 12

/** The model every head serves, and its window. */
private const val MODEL = "scripted-model"
private const val CONTEXT_WINDOW = 200_000L

/** The credential every head sends the scripted provider. Not a real one: the provider reads none. */
private const val PROVIDER_TOKEN = "scripted-provider-token"
private const val PROVIDER_ACCOUNT = "scripted-account"

/**
 * The knobs a test may set on a head. [maxInflight] and [maxQueued] are the gate's limits, 0 for no limit, so a
 * head that refuses a third request is `HeadShape(maxInflight = 1, maxQueued = 1)`. [retries] is the upstream
 * client's attempt budget. [budget] is every watchdog tier and the upstream deadline: far longer than any test's
 * virtual wait, so no watchdog fires unless a test shortens it. [firstOutput] shortens only the tier a round is held
 * to before the client saw content, and [progressCap] only the whole turn's no-progress limit, so a test can make
 * one of them fire and not the other. [relayed] puts a [PathRelay] between the head and the provider.
 */
public data class HeadShape(
    val maxInflight: Int = 0,
    val maxQueued: Int = 0,
    val retries: Int = 2,
    val budget: Duration = BUDGET_HOURS.hours,
    val firstOutput: Duration = budget,
    val progressCap: Duration = budget,
    val relayed: Boolean = false,
)

/**
 * The head's own waits (the drain's and the stop seal's polls): on the virtual clock while the head serves, and a
 * bare yield while it stops, because the stop measures its deadlines in real time and a parked poll on a clock no
 * one moves would hold the stop forever.
 */
internal class HeadWaiter(private val time: VirtualTime) : Waiter {
    @Volatile var stopping: Boolean = false

    override suspend fun wait(ms: Long) {
        if (stopping) yield() else time.waiter.wait(ms)
    }
}

/** One running head, the client in front of it, and the seams a test drives it through. */
public class ScriptedHead internal constructor(
    private val server: HeadServer,
    private val waiter: HeadWaiter,
    private val model: String,
    /** The head's running turns: what the console lists and stops. */
    public val liveTurns: LiveTurns,
    /** The head's admission gate. */
    public val gate: InflightGate,
    private val lines: List<String>,
    private val wiring: HeadWiring,
) {
    /** One reader per port the head listened on, so a stream opened before a restart keeps its connection. */
    private val readers = ConcurrentHashMap<Int, ClientReader>()

    /** The port the head listens on; a restart moves it. */
    public val port: Int get() = server.port

    /** Claude Code's side of this head, on the port it listens on now. */
    public val client: ClientReader get() = readers.computeIfAbsent(server.port) { ClientReader(it, model) }

    /** Lines the head logged, in order. */
    public val log: List<String> get() = lines.toList()

    /** The JSONL file the head writes each turn's perf row to. */
    public val perfFile: Path get() = wiring.perfFile

    /** The relay between the head and the provider; a test makes it refuse new connections. Fails on a head built
     *  without [HeadShape.relayed]. */
    public val relay: PathRelay get() = checkNotNull(wiring.relay) { "this head was built without a relay" }

    /** Returns once [count] requests wait in the gate's queue. The gate signals nothing when one joins, so this
     *  looks again each time the test's coroutine runs. */
    public suspend fun awaitQueued(count: Int) {
        while (gate.snapshot().queued < count) yield()
    }

    /** Restarts the head, as splice restarts a head: drain, end what still runs, listen again. */
    public suspend fun restart() {
        waiter.stopping = true
        try {
            server.restart()
            awaitListening(server.port)
        } finally {
            waiter.stopping = false
        }
    }

    /** Stops the head and the readers in front of it. */
    public suspend fun stop() {
        waiter.stopping = true
        try {
            server.stop()
        } finally {
            readers.values.forEach(ClientReader::close)
            readers.clear()
            wiring.relay?.close()
        }
    }

    internal suspend fun start() {
        server.start()
        awaitListening(server.port)
    }
}

/** Where a head's records go and the relay it reaches the provider through, when it has one. */
internal data class HeadWiring(val perfFile: Path, val relay: PathRelay?)

/** The scripted provider's credential: always there, never refused. */
private class ScriptedAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer(PROVIDER_TOKEN, PROVIDER_ACCOUNT)

    override suspend fun refresh(): Credentials = credentials()

    override suspend fun describe(): AuthDescription = AuthDescription(present = true, kind = "scripted")
}

/** A login that is gone: no credential to send and none to refresh, so a turn ends before any provider request. */
private class SignedOutAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials? = null

    override suspend fun refresh(): Credentials? = null

    override suspend fun describe(): AuthDescription = AuthDescription(present = false, kind = "scripted")
}

/** The login a head signs its provider requests with. */
internal enum class HeadLogin {
    /** The scripted provider's own credential. */
    SCRIPTED,

    /** None: the head's login is gone. */
    SIGNED_OUT,

    /** The caller's own, forwarded on each turn, as a head that serves a LAN share's nodes takes it. */
    FORWARDED,
    ;

    fun auth(key: String): RefreshableAuthProvider = when (this) {
        SCRIPTED -> ScriptedAuth()
        SIGNED_OUT -> SignedOutAuth()
        FORWARDED -> ClientAuthProvider(key)
    }
}

/** Builds heads of each API type in front of one [provider], on [time], storing what they keep under [tmp]. */
public class ScriptedHeads(private val tmp: Path, public val time: VirtualTime) {
    /** The provider every head built here talks to. */
    public val provider: ScriptedProvider = ScriptedProvider(time)

    private val heads = CopyOnWriteArrayList<ScriptedHead>()
    private val built = AtomicInteger()

    /** An Anthropic Messages passthrough head. */
    public suspend fun anthropic(shape: HeadShape = HeadShape()): ScriptedHead =
        head("anthropic", shape) { tuning, _ -> PassthroughProvider(tuning, PassthroughQuirks(tuning.key)) }

    /** An OpenAI Chat Completions head on a hosted vendor's profile. */
    public suspend fun chat(shape: HeadShape = HeadShape()): ScriptedHead =
        head("chat", shape) { tuning, _ -> ScriptedChatProvider(tuning, ChatQuirks(tuning.key)) }

    /** An OpenAI Chat Completions head on a local runtime's profile: usage in the stream, reasoning replayed. */
    public suspend fun localChat(shape: HeadShape = HeadShape()): ScriptedHead =
        head("local", shape) { tuning, _ ->
            ScriptedChatProvider(tuning, ChatQuirks(tuning.key).withStreamUsageToml(true).withReplayReasoning(true))
        }

    /** An OpenAI Chat Completions head whose login is gone. */
    public suspend fun signedOutChat(shape: HeadShape = HeadShape()): ScriptedHead =
        head("signed-out", shape, HeadLogin.SIGNED_OUT) { tuning, _ ->
            ScriptedChatProvider(tuning, ChatQuirks(tuning.key))
        }

    /** An Anthropic Messages passthrough head with no login of its own: each turn runs on the caller's
     *  (HeadPolicy.forwardClientAuth), as on a head a LAN share's nodes reach. */
    public suspend fun forwardingAnthropic(shape: HeadShape = HeadShape()): ScriptedHead =
        head("forwarding", shape, HeadLogin.FORWARDED) { tuning, _ ->
            PassthroughProvider(tuning, PassthroughQuirks(tuning.key))
        }

    /** An OpenAI Responses head over HTTP. */
    public suspend fun responses(shape: HeadShape = HeadShape()): ScriptedHead =
        head("responses", shape) { tuning, log -> ScriptedResponsesProvider(tuning, webSocket = false, log) }

    /** An OpenAI Responses head whose rounds ride the websocket. */
    public suspend fun responsesOverWebSocket(shape: HeadShape = HeadShape()): ScriptedHead =
        head("responses-ws", shape) { tuning, log -> ScriptedResponsesProvider(tuning, webSocket = true, log) }

    /** A head of [kind] whose provider [build] makes, for a provider this file does not build, such as a codex head. */
    public suspend fun custom(
        kind: String,
        shape: HeadShape = HeadShape(),
        build: (ProviderTuning, LogSink) -> Provider,
    ): ScriptedHead = head(kind, shape, HeadLogin.SCRIPTED, build)

    /** Releases the provider's holds, stops every head, closes the clock and stops the provider. */
    public suspend fun stop() {
        provider.release()
        try {
            heads.forEach { it.stop() }
        } finally {
            heads.clear()
            time.close()
            provider.stop()
        }
    }

    private suspend fun head(
        kind: String,
        shape: HeadShape,
        login: HeadLogin = HeadLogin.SCRIPTED,
        build: (ProviderTuning, LogSink) -> Provider,
    ): ScriptedHead {
        provider.start()
        val key = "scripted-$kind"
        val lines = CopyOnWriteArrayList<String>()
        val log = LogSink { lines += it }
        val waiter = HeadWaiter(time)
        val gate = InflightGate({ shape.maxInflight }, { shape.maxQueued }, time.elapsed)
        val liveTurns = LiveTurns(time.elapsed)
        val upstream = UpstreamClient(
            totalTimeoutMs = shape.budget.inWholeMilliseconds,
            maxRetries = shape.retries,
            pacing = RetryPacing(waiter = time.waiter),
            clock = time.elapsed,
        )
        val base = headDeps(tmp, upstream, log, gate, HeadDeps.HeadSeams(time.elapsed, waiter, time.ticker))
        val suffix = "-$key-${built.incrementAndGet()}"
        val deps = base.copy(
            traffic = base.traffic.copy(liveTurns = liveTurns),
            stores = headStores(tmp, suffix = suffix),
            policy = base.policy.copy(stopDrainMs = 0, forwardClientAuth = login == HeadLogin.FORWARDED),
        )
        val relay = if (shape.relayed) PathRelay(URI(provider.baseUrl).port) else null
        val baseUrl = relay?.let { "http://127.0.0.1:${it.port}" } ?: provider.baseUrl
        val server = HeadServer(build(tuning(key, shape, baseUrl, login), log), listenPort = 0, deps = deps)
        val wiring = HeadWiring(tmp.resolve("perf$suffix.jsonl"), relay)
        val head = ScriptedHead(server, waiter, "claude-$key--$MODEL", liveTurns, gate, lines, wiring)
        heads += head
        head.start()
        return head
    }

    private fun tuning(key: String, shape: HeadShape, baseUrl: String, login: HeadLogin): ProviderTuning =
        ProviderTuning(
            name = ProviderName(key = key, label = key),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-$key--",
                models = listOf(ModelEntry(MODEL, label = "Scripted", contextWindow = CONTEXT_WINDOW)),
                defaultContextWindow = CONTEXT_WINDOW,
            ),
            pinnedModel = MODEL,
            auth = login.auth(key),
            locations = ProviderLocations(baseUrl = baseUrl),
            watchdog = WatchdogBudget(shape.firstOutput, shape.budget, shape.progressCap),
        )
}

/**
 * Runs [block] against heads in front of one scripted provider, and stops them all when it ends, even when it was
 * cancelled. A failure to stop never hides the block's own failure: it rides on it as a suppressed exception.
 */
public suspend fun <T> withScriptedHeads(
    tmp: Path,
    time: VirtualTime = VirtualTime(autoAdvance = true),
    block: suspend ScriptedHeads.() -> T,
): T {
    val heads = ScriptedHeads(tmp, time)
    val outcome = runCatching { heads.block() }
    val stopped = withContext(NonCancellable) { runCatching { heads.stop() } }
    stopped.exceptionOrNull()?.let { failure -> outcome.exceptionOrNull()?.addSuppressed(failure) }
    return outcome.getOrThrow().also { stopped.getOrThrow() }
}
