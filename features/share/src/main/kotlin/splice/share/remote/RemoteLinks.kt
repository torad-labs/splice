// NEW: LAN share, phase 2, milestone 3 (docs/specs/lan-share.md). The machines this one joined, as the daemon reaches
// them outside a turn: each `[remotes.<name>]` with the certificate pinned for it (RemoteTrust) and the token kept for
// it (RemotesStore, read per request, so a re-join lands with no restart). Built once from the splice.toml the daemon
// booted, and the one place every daemon path to a remote gets its trust: the relay's upstream client, the roster
// refresh, the quota poll and the reachability check. The check runs every minute and its answer is held here: the
// heads route reads a head whose machine is down as not answering, and /health names each machine's state for doctor.
package splice.share.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import splice.core.topology.AuthKind
import splice.core.topology.RemoteConfig
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

public class RemoteLinks(topology: Topology, private val store: RemotesStore) {
    private val remotes: Map<String, RemoteConfig> = topology.remotes

    /** Each head here that relays to a joined machine, by key: that machine, and the head's key there (the last
     *  segment of its base URL, `<url>/share/v1/heads/<key>`). */
    private val relayed: Map<String, RelayedHead> = topology.heads.mapNotNull { (key, head) ->
        val provider = topology.providers[head.provider] ?: return@mapNotNull null
        val remote = provider.auth.remote?.takeIf { provider.auth.kind == AuthKind.SpliceRemote.wire }
        remote?.let { key to RelayedHead(it, provider.baseUrl.trimEnd('/').substringAfterLast('/')) }
    }.toMap()

    private val apis = ConcurrentHashMap<String, RemoteApi>()
    private val reach = ConcurrentHashMap<String, RemoteReach>()

    public fun names(): Set<String> = remotes.keys

    /** Where [name]'s listener answers, as `[remotes]` keeps it; null for a machine not joined. */
    public fun url(name: String): String? = remotes[name]?.url

    /** Trust in exactly [name]'s pinned certificate. Boot refuses a remote without a pin (RemoteFindings), so the
     *  empty pin, which no certificate matches, stands in only for one that cannot be missing. */
    public fun trust(name: String): RemoteTrust = RemoteTrust(remotes[name]?.fingerprint.orEmpty(), name)

    /** [name]'s reads, one client per machine for the daemon's life (the pin cannot change without a restart). */
    public fun api(name: String): RemoteApi = apis.computeIfAbsent(name) { RemoteApi(it, trust(it), store) }

    /** Each joined machine as the last check found it, by name; empty before the first check. */
    public fun reach(): Map<String, RemoteReach> = reach.toSortedMap()

    /** Each head here whose machine did not answer the last check, or said that head is not running there, with that
     *  machine's name: what the heads route reads as "Not answering". */
    public fun notAnswering(): Map<String, String> = relayed.mapNotNull { (key, to) ->
        val found = reach[to.remote] ?: return@mapNotNull null
        (key to to.remote).takeIf { !found.reachable || to.head in found.down }
    }.toMap()

    /** Checks every joined machine now, then every [REMOTE_CHECK_MS] until [scope] ends. Null when this one joined
     *  none, so a machine that never used sharing runs nothing. */
    public fun watch(scope: CoroutineScope, log: LogSink, ticker: Ticker = ProcessTicker()): Job? {
        if (remotes.isEmpty()) return null
        return scope.launch {
            do {
                runInterruptible(ProcessDispatchers().io()) { check(log) }
            } while (isActive && ticker.awaitTick(REMOTE_CHECK_MS))
        }
    }

    /** One check of every joined machine. Blocking. A machine going down, or coming back, is said once in the log. */
    internal fun check(log: LogSink) {
        remotes.forEach { (name, remote) ->
            val now = Instant.now().toString()
            val found = Cancellables.runCatchingCancellable { api(name).health(remote.url) }.fold(
                onSuccess = { health ->
                    RemoteReach(true, now, down = health.heads.filterNot { it.running }.map { it.key }.toSet())
                },
                onFailure = { RemoteReach(false, now, why = said(it)) },
            )
            val was = reach[name]
            reach[name] = if (was != null && was.reachable == found.reachable) found.copy(since = was.since) else found
            if (was?.reachable != false && !found.reachable) {
                log("[remote $name] unreachable: ${found.why}; its commands answer no turn until it is back\n")
            } else if (was?.reachable == false && found.reachable) {
                log("[remote $name] reachable again\n")
            }
        }
    }

    /** Why a check found no answer: the read's own words, which name the machine and say what to try. */
    private fun said(failure: Throwable): String =
        (failure as? RemoteUnanswered)?.said ?: SafeFailureText.render(failure)
}

/** One joined machine as the last check found it: [reachable] since [since] (an ISO instant, when it last changed),
 *  [why] it is not, in words, and [down], the keys of the heads it offers that it said are not running. */
public data class RemoteReach(
    public val reachable: Boolean,
    public val since: String,
    public val why: String? = null,
    public val down: Set<String> = emptySet(),
)

private data class RelayedHead(val remote: String, val head: String)

// why: a machine going down shows within a minute, which is how soon a person notices a command not answering, and a
// check is one small request a minute per joined machine.
private const val REMOTE_CHECK_MS = 60_000L
