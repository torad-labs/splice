// NEW: 2026-09-22 — every head's discovered models, asked at daemon start and refreshed hourly
// (V4-440), so its catalog offers what the provider serves rather than only splice.toml's rows.
// The daemon owns the refresh scope; a failed refresh retains the last live answer.
//
// NEVER BELOW THE STATUS QUO. Every head is asked at once, each bounded by [DISCOVERY_DEADLINE], and no
// failure stops a head from starting: an endpoint that does not answer is replaced by the list it
// published last (RosterCache), and with neither the head boots on splice.toml's rows exactly as it did
// before discovery existed. One daemon.log line per head says which of the three happened.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.DaemonBoundary
import splice.app.auth.StoredModelCredentials
import splice.core.config.StatePaths
import splice.core.model.DiscoveredModel
import splice.core.model.HeadDiscoveredModels
import splice.core.topology.ProviderConfig
import splice.core.topology.UpstreamRosterUrl
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.models.discovery.Discovery
import splice.models.discovery.KeptRoster
import splice.models.discovery.ModelDiscovery
import splice.models.discovery.RosterCache
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// why: the whole wait a vendor can add to a daemon start. ModelsHttp bounds a RESPONSE at 10s but sets
// no connect timeout, so a black-holed SYN would otherwise hold the start for the kernel's TCP retry
// budget (about two minutes); 10s matches the response bound, and every head is asked at once, so ten
// providers cost one wait, not ten.
private val DISCOVERY_DEADLINE: Duration = 10.seconds

// why: a provider release joins every running head within an hour, without polling its endpoint per turn.
private const val REFRESH_INTERVAL_MS = 3_600_000L

/** One head's provider asked what it serves. [EndpointModels] in production. */
internal fun interface HeadModelsSource {
    fun discover(key: String, provider: ProviderConfig): Discovery
}

/** Production [HeadModelsSource]: the models feature's discovery, presenting the credential a head's
 *  turns present (StoredCredential.bearer — the same one `splice models` uses). */
internal class EndpointModels(private val env: EnvReader = EnvReader(System::getenv)) : HeadModelsSource {
    private val discovery = ModelDiscovery(StoredModelCredentials())

    override fun discover(key: String, provider: ProviderConfig): Discovery = discovery.discover(key, provider, env)
}

/** Each head's discovered models, kept under [statePaths] between starts. [source] is each head's own
 *  endpoint in the daemon; a test hands a fake. */
internal class ModelRosters(
    statePaths: StatePaths,
    private val log: LogSink,
    private val source: HeadModelsSource = EndpointModels(),
    private val deadline: Duration = DISCOVERY_DEADLINE,
    private val ticker: Ticker = ProcessTicker(),
) : HeadDiscoveredModels {

    private val cache = RosterCache(statePaths)

    private val byHead = ConcurrentHashMap<String, List<DiscoveredModel>>()
    private val boundary = DaemonBoundary()

    /** What [key]'s provider published most recently, or last published when it did not answer. Empty
     *  before [resolve], and for a head whose provider discovered nothing. */
    override fun forHead(key: String): List<DiscoveredModel> = byHead[key].orEmpty()

    /** Ask every head's provider at once — [heads] maps a head key to the provider AS THAT HEAD
     *  RESOLVES IT (legacy knobs applied), because that is the endpoint its turns dial — and hold the
     *  answers. Returns when every head has one; never throws for a provider's sake. */
    suspend fun resolve(heads: Map<String, ProviderConfig>) {
        val answers = coroutineScope {
            heads.map { (key, provider) -> async { key to modelsFor(key, provider) } }.awaitAll()
        }
        answers.forEach { (key, models) -> byHead[key] = models }
    }

    /** Start after the initial [resolve]; cancellation of the daemon scope stops all refreshes. */
    fun start(scope: CoroutineScope, heads: Map<String, ProviderConfig>): Job = scope.launch {
        while (isActive && ticker.awaitTick(REFRESH_INTERVAL_MS)) resolve(heads)
    }

    private suspend fun modelsFor(key: String, provider: ProviderConfig): List<DiscoveredModel> {
        val answer = withTimeoutOrNull(deadline) {
            runInterruptible(ProcessDispatchers().io()) { ask(key, provider) }
        } ?: Discovery.Unavailable(UpstreamRosterUrl.of(provider), "no answer within $deadline")
        return when (answer) {
            is Discovery.Found -> answer.models.also {
                keep(key, answer)
                log("[$key] models: ${listed(answer, provider)}\n")
            }
            is Discovery.Unavailable -> fallback(key, provider, answer)
        }
    }

    /** What the endpoint listed, and how many of those stay out of the picker and why, so the line
     *  never reads as more models than can join it. A model a declared row covers is in the picker
     *  whatever the filter says, so only the undeclared ones count as kept out. */
    private fun listed(found: Discovery.Found, provider: ProviderConfig): String {
        val keptOut = provider.undeclared(found.models).count { !provider.discovery.admits(it.id) }
        val unusable = if (found.ruledOut == 0) "" else ", ${found.ruledOut} it marks unusable for a turn"
        val filtered = if (keptOut == 0) "" else ", $keptOut kept out by its discovery filter"
        return "${found.models.size + found.ruledOut} listed at ${found.url}$unusable$filtered"
    }

    /** A failure reading the credential or the answer is this head's no-answer, never the daemon's
     *  failed start. Rendered safe: a credential file's parse error can quote the file. */
    private fun ask(key: String, provider: ProviderConfig): Discovery =
        boundary.runCatchingDaemonBoundary { source.discover(key, provider) }
            .getOrElse { Discovery.Unavailable(null, "could not ask (${SafeFailureText.render(it)})") }

    /** The list [provider] published last, or nothing — said once, either way, with where it was asked
     *  when the reason does not already say, and why no kept list stood in when none did. */
    private fun fallback(key: String, provider: ProviderConfig, missed: Discovery.Unavailable): List<DiscoveredModel> {
        val at = missed.url?.takeUnless { it in missed.reason }?.let { " (asked at $it)" }.orEmpty()
        val kept = byHead[key]?.let { KeptRoster.Kept(it) } ?: cache.read(key, provider)
        log("[$key] models: ${missed.reason}$at; ${instead(kept)}\n")
        return (kept as? KeptRoster.Kept)?.models.orEmpty()
    }

    private fun instead(kept: KeptRoster): String = when (kept) {
        is KeptRoster.Kept -> "using the ${kept.models.size} it published last"
        KeptRoster.None -> "no list was kept, so the picker is splice.toml's rows"
        is KeptRoster.OtherUrl -> "the list kept was published at ${kept.url}, so the picker is splice.toml's rows"
        is KeptRoster.Unreadable -> "${kept.reason}, so the picker is splice.toml's rows"
    }

    private fun keep(key: String, found: Discovery.Found) {
        Cancellables.runCatchingCancellable { cache.write(key, found) }
            .onFailure { log("[$key] models: could not keep the discovered list (${SafeFailureText.render(it)})\n") }
    }
}
