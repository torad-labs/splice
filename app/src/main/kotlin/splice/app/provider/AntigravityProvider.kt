// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 1 + door chat).
// Antigravity as a provider only Claude Desktop runs: its catalog is what `agy acp` offers the
// account signed in to it (AgyModels), published with every other provider's so Desktop's picker
// lists its models with their reasoning levels. Code-tab sessions run `agy acp` directly through
// `splice desktop acp`; Desktop Chat turns arriving at `/door/v1/messages` run through
// [AntigravityDoorEngine] (unmodified `agy acp` + virtual loopback MCP bridge for Desktop Chat's
// tools, never Claude Code CLI).
package splice.app.provider

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CoroutineScope
import splice.core.config.ConfigService
import splice.core.model.ModelCatalog
import splice.core.usage.QuotaFull
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.head.GatewayTurn
import splice.provider.antigravity.ANTIGRAVITY_KEY
import splice.provider.antigravity.AgyModels
import splice.provider.antigravity.AgyRead
import splice.provider.antigravity.door.AntigravityDoorEngine
import splice.share.RefusalVoice
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessWaiter
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

/** Antigravity once agy has said what it offers ([join]); no provider before. Read with [config]'s limits; what each
 *  try came to goes to [log]. */
internal class AntigravityJoin(
    private val config: ConfigService,
    private val log: LogSink,
    private val waiter: Waiter = ProcessWaiter(),
) : ServingProviders {
    @Volatile
    private var doorEngine: AntigravityDoorEngine? = null

    @Volatile
    private var joined: ServedProvider? = null

    override fun invoke(): List<ServedProvider> = listOfNotNull(joined)

    /** Closes any pooled `agy acp` door chat sessions on daemon shutdown. */
    fun close() {
        doorEngine?.close()
        doorEngine = null
    }

    /** Asks agy, reading its output in [scope], and joins Antigravity when it offers models: true when it joined. It
     *  does not join beside a provider of [door] that already has its key, whose selectors it would share. A try
     *  that fails is tried again after each of [RETRY_WAITS_MS]; a missing or signed-out agy is not, since only an
     *  install or a sign-in, and a restart, change that. */
    suspend fun join(scope: CoroutineScope, door: ServingProviders): Boolean {
        if (door().any { it.key == ANTIGRAVITY_KEY }) {
            log("[antigravity] not served: splice.toml has a provider of its own named '$ANTIGRAVITY_KEY'\n")
            return false
        }
        val io = ProcessDispatchers().io()
        val agy = AgyModels(log, scope, io)
        var read: AgyRead = AgyRead.Failed("agy was not asked")
        for ((tried, wait) in RETRY_WAITS_MS.withIndex()) {
            waiter.wait(wait)
            read = agy.read()
            if (read !is AgyRead.Failed) break
            log("[antigravity] not served (try ${tried + 1} of ${RETRY_WAITS_MS.size}): ${read.reason}\n")
        }
        when (read) {
            is AgyRead.Offered -> {
                val engine = AntigravityDoorEngine(scope, io, log)
                doorEngine = engine
                joined = AntigravityProvider(read.catalog, config, engine)
                log("[antigravity] served: ${read.catalog.models.size} models from agy\n")
            }
            is AgyRead.Absent -> log("[antigravity] not served: ${read.reason}\n")
            AgyRead.SignedOut ->
                log("[antigravity] not served: agy is signed in to no account; sign in with `agy`, then restart\n")
            is AgyRead.Failed -> Unit
        }
        return read is AgyRead.Offered
    }
}

/** Antigravity's models, read from agy as [catalog], under the limits [config] sets for every provider. */
internal class AntigravityProvider(
    override val catalog: ModelCatalog,
    private val config: ConfigService,
    private val doorEngine: AntigravityDoorEngine? = null,
) : ServedProvider {
    override val key: String = ANTIGRAVITY_KEY
    override val tiers: Map<String, String> = emptyMap()
    override val pinnedModel: String? = catalog.pinnedModel.ifEmpty { null }
    override val dialect: String? = null
    override val usage: HeadUsageSource = HeadUsageSource { UsageView(0, 0, null) }
    override val usesLogin: Boolean = true
    override val forwardsClientAuth: Boolean = false
    override val maxRequestBytes: Int get() = config.getConfig().maxRequestBytes
    override val requestReadTimeoutMs: Long get() = config.getConfig().requestReadTimeoutMs
    private val voice = RefusalVoice("splice: ")
    private val adapter = doorEngine?.let { AntigravityDoorAdapter(catalog, it) }

    override fun quotaFull(): QuotaFull? = null

    override suspend fun login(): ProviderLogin = ProviderLogin.Ready

    override suspend fun serve(call: ApplicationCall, turn: GatewayTurn) {
        val active = adapter ?: return voice.refuse(call, HttpStatus.BAD_REQUEST, DESKTOP_ONLY)
        active.serve(call, turn)
    }

    override suspend fun countTokens(call: ApplicationCall, turn: GatewayTurn) {
        val active = adapter ?: return voice.refuse(call, HttpStatus.BAD_REQUEST, DESKTOP_ONLY)
        active.countTokens(call, turn)
    }
}

/** The waits before each try of agy: at once, then after half a minute, then after two minutes. */
private val RETRY_WAITS_MS = listOf(0L, HALF_A_MINUTE_MS, TWO_MINUTES_MS)

/** Long enough for a network that was not up at boot to come up. */
private const val HALF_A_MINUTE_MS = 30_000L

/** The last try, for an outage longer than that. */
private const val TWO_MINUTES_MS = 120_000L

private const val DESKTOP_ONLY = "Antigravity models run only in Claude Desktop (Chat or Code tab)."
