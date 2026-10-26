// NEW: failure-handling slice 0, the harness. A real head of each API type in front of the scripted provider, on
// the virtual clock, built the way HeadServerEndingsTest builds its head (HeadDepsFixture's headDeps and headStores).
package splice.head.scripted

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.dialect.chat.ChatQuirks
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesProvider
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.awaitListening
import splice.head.headDeps
import splice.head.headStores
import splice.head.turn.LiveTurns
import splice.provider.openai.OpenAiChatProvider
import splice.provider.openai.OpenAiQuirks
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.Waiter
import splice.upstream.retry.InflightGate
import splice.upstream.transport.RetryPacing
import splice.upstream.transport.UpstreamClient
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
 * virtual wait, so no watchdog fires unless a test shortens it.
 */
public data class HeadShape(
    val maxInflight: Int = 0,
    val maxQueued: Int = 0,
    val retries: Int = 2,
    val budget: Duration = BUDGET_HOURS.hours,
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
) {
    /** One reader per port the head listened on, so a stream opened before a restart keeps its connection. */
    private val readers = ConcurrentHashMap<Int, ClientReader>()

    /** The port the head listens on; a restart moves it. */
    public val port: Int get() = server.port

    /** Claude Code's side of this head, on the port it listens on now. */
    public val client: ClientReader get() = readers.computeIfAbsent(server.port) { ClientReader(it, model) }

    /** Lines the head logged, in order. */
    public val log: List<String> get() = lines.toList()

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
        }
    }

    internal suspend fun start() {
        server.start()
        awaitListening(server.port)
    }
}

/** The scripted provider's credential: always there, never refused. */
private class ScriptedAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer(PROVIDER_TOKEN, PROVIDER_ACCOUNT)

    override suspend fun refresh(): Credentials = credentials()

    override suspend fun describe(): AuthDescription = AuthDescription(present = true, kind = "scripted")
}

/** A Responses head on the OpenAI platform's profile, whose rounds ride the websocket when [webSocket] is on. */
private class ScriptedResponsesProvider(
    tuning: ProviderTuning,
    private val webSocket: Boolean,
    log: LogSink,
) : ResponsesProvider(
    tuning,
    ReasoningSettings(ReasoningDisplay.TEXT, replay = false, effort = "high", summary = "detailed"),
    OpenAiQuirks().defaultQuirks().withWebSocketToml(webSocket),
    log = log,
) {
    override val supportsWebSocket: Boolean get() = webSocket
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
        head("chat", shape) { tuning, _ -> OpenAiChatProvider(tuning, ChatQuirks(tuning.key)) }

    /** An OpenAI Chat Completions head on a local runtime's profile: usage in the stream, reasoning replayed. */
    public suspend fun localChat(shape: HeadShape = HeadShape()): ScriptedHead =
        head("local", shape) { tuning, _ ->
            OpenAiChatProvider(tuning, ChatQuirks(tuning.key).withStreamUsageToml(true).withReplayReasoning(true))
        }

    /** An OpenAI Responses head over HTTP. */
    public suspend fun responses(shape: HeadShape = HeadShape()): ScriptedHead =
        head("responses", shape) { tuning, log -> ScriptedResponsesProvider(tuning, webSocket = false, log) }

    /** An OpenAI Responses head whose rounds ride the websocket. */
    public suspend fun responsesOverWebSocket(shape: HeadShape = HeadShape()): ScriptedHead =
        head("responses-ws", shape) { tuning, log -> ScriptedResponsesProvider(tuning, webSocket = true, log) }

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
        val deps = base.copy(
            traffic = base.traffic.copy(liveTurns = liveTurns),
            stores = headStores(tmp, suffix = "-$key-${built.incrementAndGet()}"),
            policy = base.policy.copy(stopDrainMs = 0),
        )
        val server = HeadServer(build(tuning(key, shape), log), listenPort = 0, deps = deps)
        val head = ScriptedHead(server, waiter, "claude-$key--$MODEL", liveTurns, gate, lines)
        heads += head
        head.start()
        return head
    }

    private fun tuning(key: String, shape: HeadShape): ProviderTuning = ProviderTuning(
        name = ProviderName(key = key, label = key),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-$key--",
            models = listOf(ModelEntry(MODEL, label = "Scripted", contextWindow = CONTEXT_WINDOW)),
            defaultContextWindow = CONTEXT_WINDOW,
        ),
        pinnedModel = MODEL,
        auth = ScriptedAuth(),
        locations = ProviderLocations(baseUrl = provider.baseUrl),
        watchdog = WatchdogBudget(shape.budget, shape.budget, shape.budget),
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
