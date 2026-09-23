// NEW: 2026-09-22 — every head's DISCOVERED models, asked for once at daemon start and held for the
// daemon's life, so a head's catalog offers what its provider serves rather than only what
// splice.toml spells out (the operator: "make sure that splice probes the head endpoint for available
// models instead of having to hardcode them on the toml file").
//
// ONCE, AT START, like the rest of the roster: TopologyWindows' header keeps "the roster, ports,
// auth, quirks and knobs" boot-time, and a model that appears upstream while the daemon runs joins the
// picker at the next start — which every install and upgrade already is.
//
// NEVER BELOW THE STATUS QUO. Every head is asked at once, each bounded by [DISCOVERY_DEADLINE], and no
// failure stops a head from starting: an endpoint that does not answer is replaced by the list it
// published last (RosterCache), and with neither the head boots on splice.toml's rows exactly as it did
// before discovery existed. One daemon.log line per head says which of the three happened.
package splice.app.provider

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.DaemonBoundary
import splice.app.auth.StoredCredential
import splice.core.config.StatePaths
import splice.core.model.DiscoveredModel
import splice.core.model.HeadDiscoveredModels
import splice.core.topology.ProviderConfig
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.models.discovery.Discovery
import splice.models.discovery.ModelDiscovery
import splice.models.discovery.RosterCache
import splice.models.list.ModelCredentialSource
import splice.upstream.codemode.ProcessDispatchers
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// why: the whole wait a vendor can add to a daemon start. ModelsHttp bounds a RESPONSE at 10s but sets
// no connect timeout, so a black-holed SYN would otherwise hold the start for the kernel's TCP retry
// budget (about two minutes); 10s matches the response bound, and every head is asked at once, so ten
// providers cost one wait, not ten.
private val DISCOVERY_DEADLINE: Duration = 10.seconds

/** One head's provider asked what it serves. [EndpointModels] in production. */
internal fun interface HeadModelsSource {
    fun discover(key: String, provider: ProviderConfig): Discovery
}

/** Production [HeadModelsSource]: the models feature's discovery, presenting the credential a head's
 *  turns present (StoredCredential.bearer — the same one `splice models` uses). */
internal class EndpointModels(private val env: EnvReader = EnvReader(System::getenv)) : HeadModelsSource {
    private val discovery = ModelDiscovery(ModelCredentialSource(StoredCredential()::bearer))

    override fun discover(key: String, provider: ProviderConfig): Discovery = discovery.discover(key, provider, env)
}

internal class ModelRosters(
    private val source: HeadModelsSource,
    private val cache: RosterCache,
    private val log: LogSink,
    private val deadline: Duration = DISCOVERY_DEADLINE,
) : HeadDiscoveredModels {

    /** The daemon's: each head's own endpoint, cached under [statePaths]. */
    constructor(statePaths: StatePaths, log: LogSink) : this(EndpointModels(), RosterCache(statePaths), log)

    private val byHead = ConcurrentHashMap<String, List<DiscoveredModel>>()
    private val boundary = DaemonBoundary()

    /** What [key]'s provider published at start, or last published when it did not answer. Empty
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

    private suspend fun modelsFor(key: String, provider: ProviderConfig): List<DiscoveredModel> {
        val answer = withTimeoutOrNull(deadline) {
            runInterruptible(ProcessDispatchers().io()) { ask(key, provider) }
        } ?: Discovery.Unavailable(null, "no answer within $deadline")
        return when (answer) {
            is Discovery.Found -> answer.models.also {
                keep(key, answer)
                log("[$key] models: ${it.size} discovered at ${answer.url}\n")
            }
            is Discovery.Unavailable -> fallback(key, provider, answer.reason)
        }
    }

    /** A failure reading the credential or the answer is this head's no-answer, never the daemon's
     *  failed start. Rendered safe: a credential file's parse error can quote the file. */
    private fun ask(key: String, provider: ProviderConfig): Discovery =
        boundary.runCatchingDaemonBoundary { source.discover(key, provider) }
            .getOrElse { Discovery.Unavailable(null, "could not ask (${SafeFailureText.render(it)})") }

    /** The list [provider] published last, or nothing — said once, either way. */
    private fun fallback(key: String, provider: ProviderConfig, reason: String): List<DiscoveredModel> {
        val kept = cache.read(key, provider)
        val then = if (kept == null) "the picker is splice.toml's rows" else "using the ${kept.size} it published last"
        log("[$key] models: $reason; $then\n")
        return kept.orEmpty()
    }

    private fun keep(key: String, found: Discovery.Found) {
        Cancellables.runCatchingCancellable { cache.write(key, found) }
            .onFailure { log("[$key] models: could not keep the discovered list (${SafeFailureText.render(it)})\n") }
    }
}
