// NEW: 2026-09-22 — every head's discovered models, asked at daemon start and refreshed hourly
// (V4-440), so its catalog offers what the provider serves rather than only splice.toml's rows.
// The daemon owns the refresh scope; a failed refresh retains the last live answer.
//
// A NEW SIGN-IN IS ASKED AT ONCE (fix 3, 2026-10-27). Setup restarts the daemon before it signs in, so the list
// asked at the start had no credential, and code mode, which that list marks, waited up to an hour. Sign-in never
// calls the daemon (it runs before the daemon is up as often as after), so each head's list records a one-way key
// of the sign-in it was asked with, the sign-in is reread on a short beat, and a changed one is asked at once.
//
// NEVER BELOW THE STATUS QUO. Every head is asked at once, each bounded by [DISCOVERY_DEADLINE], and no
// failure stops a head from starting: an endpoint that does not answer is replaced by the list it
// published last (RosterCache), and with neither the head boots on splice.toml's rows exactly as it did
// before discovery existed. One daemon.log line per head says which of the three happened.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.DaemonBoundary
import splice.app.auth.StoredModelCredentials
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.config.StatePaths
import splice.core.model.DiscoveredModel
import splice.core.model.HeadDiscoveredModels
import splice.core.topology.ProviderConfig
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.models.discovery.Discovery
import splice.models.discovery.KeptRoster
import splice.models.discovery.ModelDiscovery
import splice.models.discovery.RosterCache
import splice.oauth.StoredCredential
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
private const val NONE_KEPT = "no list was kept, so the picker is splice.toml's rows"

// why: a provider release joins every running head within an hour, without polling its endpoint per turn.
private const val REFRESH_INTERVAL_MS = 3_600_000L

// why: a sign-in lands as a file the daemon is never told about, so each head's is reread on this beat. A reread is
// one small file read per head, and two seconds is less than a person takes from a sign-in to a first prompt.
private const val CREDENTIAL_RECHECK_MS = 2_000L

/** What a head's list was asked with when it had no sign-in, and when its sign-in could not be read. Neither is a
 *  [CredentialKey], which is hex. */
private const val NO_SIGN_IN = "none"
private const val SIGN_IN_UNREADABLE = "unreadable"

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

/** The sign-in a head's list is asked with, as a one-way key that is never logged; null when there is none. */
internal fun interface HeadSignIn {
    fun keyOf(key: String, provider: ProviderConfig): String?
}

/** Production [HeadSignIn]: the token a sign-in left in the head's credential file, which its list is asked with
 *  (StoredCredential.presentedToken). An api-key head has none here: its key is not a sign-in, and its list keeps
 *  the hourly refresh. */
internal class StoredSignIn(private val stored: StoredCredential = StoredCredential()) : HeadSignIn {
    override fun keyOf(key: String, provider: ProviderConfig): String? =
        stored.presentedToken(provider)?.let { CredentialKey.fromCredentials(Credentials.Bearer(it)) }
}

/** Each head's discovered models, kept under [statePaths] between starts. [source] is each head's own
 *  endpoint in the daemon; a test hands a fake. */
internal class ModelRosters(
    statePaths: StatePaths,
    private val log: LogSink,
    private val source: HeadModelsSource = EndpointModels(),
    private val deadline: Duration = DISCOVERY_DEADLINE,
    private val ticker: Ticker = ProcessTicker(),
    /** Each head's sign-in, reread on [credentialTicker]'s beat; a changed one is asked for its list at once. */
    private val signIn: HeadSignIn = StoredSignIn(),
    private val credentialTicker: Ticker = ProcessTicker(),
) : HeadDiscoveredModels {

    private val cache = RosterCache(statePaths)

    private val byHead = ConcurrentHashMap<String, KeptRoster.Kept>()

    /** The one-way key of the sign-in each head's list was last asked with. */
    private val askedWith = ConcurrentHashMap<String, String>()

    /** One ask per head at a time, so an hourly refresh and a recheck never publish out of order. */
    private val asking = ConcurrentHashMap<String, Mutex>()

    /** Each head's last fallback line: the hourly refresh says it again only when it changed. A forwarded Claude login
     *  and a local runtime are never asked, so their line printed every hour (the field audit, Oct 13). */
    private val said = ConcurrentHashMap<String, String>()
    private val boundary = DaemonBoundary()

    /** What [key]'s provider published most recently, or last published when it did not answer. Empty
     *  before [resolve], and for a head whose provider discovered nothing. */
    override fun forHead(key: String): List<DiscoveredModel> = byHead[key]?.models.orEmpty()

    override fun metadataForHead(key: String): List<DiscoveredModel> = byHead[key]?.metadata.orEmpty()

    /** Ask every head's provider at once — [heads] maps a head key to the provider AS THAT HEAD
     *  RESOLVES IT (legacy knobs applied), because that is the endpoint its turns dial — and hold the
     *  answers. Returns when every head has one; never throws for a provider's sake. */
    suspend fun resolve(heads: Map<String, ProviderConfig>) {
        coroutineScope { heads.forEach { (key, provider) -> launch { refresh(key, provider, always = true) } } }
    }

    /** Ask again, at once, each head whose sign-in changed since its list was asked: a sign-in, another account's, a
     *  sign-out. A head whose sign-in is unchanged is not asked, so a list that could not be fetched waits for the
     *  hourly refresh, as it always has. */
    suspend fun recheck(heads: Map<String, ProviderConfig>) {
        coroutineScope { heads.forEach { (key, provider) -> launch { refresh(key, provider, always = false) } } }
    }

    /** Start after the initial [resolve]: the hourly refresh, and the recheck of every head's sign-in. Cancelling the
     *  daemon scope stops both. */
    fun start(scope: CoroutineScope, heads: Map<String, ProviderConfig>): Job = scope.launch {
        launch { while (isActive && credentialTicker.awaitTick(CREDENTIAL_RECHECK_MS)) recheck(heads) }
        while (isActive && ticker.awaitTick(REFRESH_INTERVAL_MS)) resolve(heads)
    }

    /** The sign-in is read before the ask, which reads it again: one that changes in between is asked twice, never
     *  missed. */
    private suspend fun refresh(key: String, provider: ProviderConfig, always: Boolean) {
        asking.computeIfAbsent(key) { Mutex() }.withLock {
            val now = signInOf(key, provider)
            if (always || askedWith[key] != now) {
                askedWith[key] = now
                byHead[key] = modelsFor(key, provider)
            }
        }
    }

    /** [key]'s sign-in now, keyed one way. One that cannot be read is [SIGN_IN_UNREADABLE]: the ask that follows
     *  reads the same sign-in, and its line says why. */
    private suspend fun signInOf(key: String, provider: ProviderConfig): String =
        withTimeoutOrNull(deadline) {
            runInterruptible(ProcessDispatchers().io()) {
                boundary.runCatchingDaemonBoundary { signIn.keyOf(key, provider) ?: NO_SIGN_IN }
                    .getOrElse { SIGN_IN_UNREADABLE }
            }
        } ?: SIGN_IN_UNREADABLE

    private suspend fun modelsFor(key: String, provider: ProviderConfig): KeptRoster.Kept {
        val answer = withTimeoutOrNull(deadline) {
            runInterruptible(ProcessDispatchers().io()) { ask(key, provider) }
        } ?: Discovery.Unavailable(provider.rosterUrl, "no answer within $deadline")
        return when (answer) {
            is Discovery.Found -> KeptRoster.Kept(answer.models, answer.metadata).also {
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
    private fun fallback(key: String, provider: ProviderConfig, missed: Discovery.Unavailable): KeptRoster.Kept {
        val at = missed.url?.takeUnless { it in missed.reason }?.let { " (asked at $it)" }.orEmpty()
        val kept = byHead[key]?.takeIf { it.models.isNotEmpty() || it.metadata.isNotEmpty() } ?: cache.read(
            key,
            provider,
        )
        val line = "[$key] models: ${missed.reason}$at; ${instead(kept)}\n"
        if (said.put(key, line) != line) log(line)
        return (kept as? KeptRoster.Kept) ?: KeptRoster.Kept(emptyList())
    }

    private fun instead(kept: KeptRoster): String = when (kept) {
        is KeptRoster.Kept ->
            if (kept.models.isEmpty()) NONE_KEPT else "using the ${kept.models.size} it published last"
        KeptRoster.None -> NONE_KEPT
        is KeptRoster.OtherUrl -> "the list kept was published at ${kept.url}, so the picker is splice.toml's rows"
        is KeptRoster.Unreadable -> "${kept.reason}, so the picker is splice.toml's rows"
    }

    private fun keep(key: String, found: Discovery.Found) {
        said.remove(key)
        Cancellables.runCatchingCancellable { cache.write(key, found) }
            .onFailure { log("[$key] models: could not keep the discovered list (${SafeFailureText.render(it)})\n") }
    }
}
