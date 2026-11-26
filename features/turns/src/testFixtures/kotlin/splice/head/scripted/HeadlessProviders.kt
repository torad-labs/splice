// NEW: gateway-no-heads. Providers served by a ProviderRuntime with no head anywhere in their construction (no
// HeadConfig, no ProviderBuild, no bound port), in front of the same scripted provider ScriptedHeads' heads talk to.
// Built the way ScriptedHeads builds a HeadServer, minus the port: what a test proves of them is what a gateway turn
// meets.
package splice.head.scripted

import splice.core.auth.ClientAuthProvider
import splice.core.config.RequestByteCap
import splice.core.model.ClientWindows
import splice.core.server.ServedTurnBoard
import splice.core.server.ServedTurns
import splice.core.topology.ForwardHeaders
import splice.core.util.LogSink
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.dialect.chat.ChatQuirks
import splice.dialect.chat.failure.ChatReplies
import splice.head.HeadDeps
import splice.head.ProviderRuntime
import splice.head.headDeps
import splice.head.headStores
import splice.head.quotaFor
import splice.head.turn.LiveTurns
import splice.upstream.Provider
import splice.upstream.credentials.AccountPool
import splice.upstream.failure.Replies
import splice.upstream.retry.InflightGate
import splice.upstream.transport.RetryPacing
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path

/** A provider served with no head: the [runtime] a gateway calls, and the JSONL file its turns' perf rows are
 *  written to. */
public data class HeadlessProvider(
    val runtime: ProviderRuntime,
    val perfFile: Path,
    val clientWindows: ClientWindows,
)

public class HeadlessProviders internal constructor(private val heads: ScriptedHeads, private val tmp: Path) {
    /** What a headless provider is wired with beside its own login: the caller headers its provider's forward_headers
     *  names, the Claude accounts added to splice, the body size limit, and whether it forwards a caller's login. */
    private data class Wiring(
        val forwarded: ForwardHeaders = ForwardHeaders(emptyList()),
        val pool: AccountPool? = null,
        val maxRequestBytes: Int? = null,
        val forwardsLogin: Boolean = false,
        val served: ServedTurns = ServedTurnBoard(),
    )

    /** An OpenAI Chat Completions provider holding its own login, as an API-key provider does. [forwarded] names the
     *  caller's headers its provider's forward_headers asks to ride upstream, and [served] is the board its turns tell
     *  how they went. */
    public suspend fun chat(
        shape: HeadShape = HeadShape(),
        maxRequestBytes: Int? = null,
        forwarded: ForwardHeaders = ForwardHeaders(emptyList()),
        served: ServedTurns = ServedTurnBoard(),
        contextWindow: Long? = null,
    ): HeadlessProvider {
        heads.provider.start()
        val key = "scripted-headless-chat"
        val auth = HeadAccounts().login.auth(key)
        val original = heads.tuning(key, shape, heads.provider.baseUrl, auth, loginCommand = "")
        val tuned = if (contextWindow == null) {
            original
        } else {
            original.copy(
                catalog = original.catalog.copy(
                    models = original.catalog.models.map { it.copy(contextWindow = contextWindow) },
                ),
            )
        }
        val provider = ScriptedChatProvider(tuned, ChatQuirks(tuned.key), Replies(ChatReplies))
        return serve(key, shape, provider, Wiring(forwarded, null, maxRequestBytes, served = served))
    }

    /** An Anthropic Messages provider that holds no login of its own and forwards a caller's (as `claude` does), with
     *  the Claude accounts named in [accounts] added to splice: each signs with [ScriptedLogin.of] its name. A turn
     *  through the gateway runs on one of them, or, with none added, has no login to run on. */
    public suspend fun claude(accounts: List<String> = emptyList()): HeadlessProvider {
        heads.provider.start()
        val key = "scripted-headless-claude"
        val tuned = heads.tuning(key, HeadShape(), heads.provider.baseUrl, ClientAuthProvider(key), loginCommand = "")
        val served = PassthroughProvider(tuned, PassthroughQuirks(tuned.key))
        val pool = HeadAccounts(pool = accounts).loginPool(heads.time)
        return serve(key, HeadShape(), served, Wiring(pool = pool, forwardsLogin = true))
    }

    private fun serve(key: String, shape: HeadShape, provider: Provider, wiring: Wiring): HeadlessProvider {
        val time = heads.time
        val upstream = UpstreamClient(
            totalTimeoutMs = shape.budget.inWholeMilliseconds,
            pacing = RetryPacing(waiter = time.waiter, wall = time.wall),
            clock = time.elapsed,
        )
        val seams = HeadDeps.HeadSeams(time.elapsed, HeadWaiter(time), HeadDeps.HeadTicks(time.ticker, time.ticker))
        val gate = InflightGate({ shape.maxInflight }, { shape.maxQueued }, time.elapsed)
        val base = headDeps(tmp, upstream, LogSink { }, gate, seams)
        val suffix = "-$key-${heads.built.incrementAndGet()}"
        val cap = wiring.maxRequestBytes
        val policy = base.policy.copy(stopDrainMs = 0, forwardClientAuth = wiring.forwardsLogin)
        val limited = if (cap == null) policy else policy.copy(maxRequestBytes = RequestByteCap { cap })
        val deps = base.copy(
            traffic = base.traffic.copy(liveTurns = LiveTurns(time.elapsed)),
            seams = base.seams.copy(reports = base.seams.reports.copy(served = wiring.served)),
            stores = headStores(tmp, suffix = suffix),
            policy = limited,
            quotaBundle = if (wiring.pool == null) base.quotaBundle else quotaFor(null, wiring.pool),
        )
        val runtime = ProviderRuntime(provider, deps, forwarded = wiring.forwarded)
        runtime.start()
        return HeadlessProvider(runtime, tmp.resolve("perf$suffix.jsonl"), deps.stores.clientWindows)
    }
}
