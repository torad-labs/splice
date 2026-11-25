// NEW: server mode (docs/specs/server-mode.md, F4 and the readiness probe). How each server of a pool is asked whether
// it can take a turn. `[server.probe] kind = "health"`, the default, asks `/health` as server mode always did: the same
// request, the same interval (`health_seconds`), and every server up from the start. `kind = "completion"` asks the
// server to really complete a minimal request for the probe's model, in its provider's dialect (ReadinessProbe) and
// with the login a turn of it sends, every `every_seconds` and within `timeout_seconds`; with `at_start` (the default)
// a server takes no turn until its first completion comes back.
package splice.app.server

import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.probe.MS_PER_SECOND
import splice.app.provider.local.JdkHealthHttp
import splice.app.provider.tls.ProviderTrust
import splice.core.auth.AuthProvider
import splice.core.auth.CredentialKey
import splice.core.topology.ProviderConfig
import splice.core.topology.ServerProbe
import splice.core.topology.UpstreamConfig
import splice.core.util.LogSink
import splice.upstream.readiness.ReadinessProbe
import splice.upstream.readiness.ReadinessReading
import splice.upstream.transport.UpstreamHeaders
import java.nio.file.Path
import java.time.Duration

/** What a pooled server is asked by a completion probe, as the head's arm knows it: its dialect's question
 *  ([readiness]), and what a turn of it carries, the login [auth] holds and the provider's [configured] headers. */
internal class PoolReadiness(
    val readiness: ReadinessProbe,
    private val auth: AuthProvider,
    private val configured: Map<String, String>,
) {
    /** The headers a probe carries now, as a turn's are composed: the credential in the header it rides in, then
     *  [configured] except that header, which the credential always wins, then the identity answer encoding. Read as
     *  a turn reads its login, which may renew it first. A provider with no credential now sends [configured] alone,
     *  and its server's refusal is what the probe reads. */
    suspend fun headers(): Map<String, String> {
        val creds = auth.credentials() ?: return configured
        val carriers = CredentialKey.headers(creds, emptyMap()).keys
        val beside = configured.filterKeys { name -> carriers.none { it.equals(name, ignoreCase = true) } }
        return UpstreamHeaders.compose(creds, beside)
    }
}

/** How every server of one pool is asked whether it can take a turn, and how often. */
internal sealed class PoolCheck {
    /** How long one check waits for the next, in milliseconds. */
    abstract val everyMs: Long

    /** Whether a server takes turns before its first check comes back. */
    abstract val startHealthy: Boolean

    /** Asks [upstream] once. */
    abstract suspend fun ask(upstream: UpstreamConfig): ReadinessReading

    /** `kind = "health"`: `GET /health` beside the server's API root with the headers a turn carries, up on any 2xx. */
    class Health(
        override val everyMs: Long,
        private val headers: ServerHeaders,
        private val http: HealthHttp,
    ) : PoolCheck() {
        override val startHealthy: Boolean = true

        override suspend fun ask(upstream: UpstreamConfig): ReadinessReading {
            val reply = http.with(headers.now())("GET", upstream.healthUrl, null)
                ?: return ReadinessReading(false, "no answer")
            return ReadinessReading(reply.status in OK_FROM until OK_BELOW, "HTTP ${reply.status}")
        }
    }

    /** `kind = "completion"`: the server is asked to complete [model]'s minimal request in its dialect, and is up only
     *  on a completion's envelope that comes back within [timeout]. */
    class Completion(
        private val ready: PoolReadiness,
        private val model: String,
        private val timeout: Duration,
        override val everyMs: Long,
        override val startHealthy: Boolean,
        private val http: HealthHttp,
    ) : PoolCheck() {
        override suspend fun ask(upstream: UpstreamConfig): ReadinessReading {
            val request = ready.readiness.request(upstream.base, model)
            val client = http.with(ready.headers())
            // Java 21's request timeout ends at the headers. Interrupting send cancels the exchange while its body
            // handler is still reading, so even an unfinished response cannot keep this member advertised as ready.
            val reply = withTimeoutOrNull(timeout.toMillis()) {
                runInterruptible { client("POST", request.url, request.body) }
            } ?: return ReadinessReading(false, "no answer within ${timeout.seconds} s")
            return ready.readiness.read(reply.status, reply.body)
        }
    }

    /** The configured check policy and the directory provider-relative files resolve against. */
    class Plan(
        private val healthHttp: HealthHttp,
        private val log: LogSink,
        private val probe: ServerProbe = ServerProbe(),
        private val configDir: Path? = null,
    ) {
        fun of(key: String, provider: ProviderConfig, headers: ServerHeaders, ready: PoolReadiness): PoolCheck {
            if (probe.kind != COMPLETION_KIND) {
                return Health(provider.healthSeconds * MS_PER_SECOND, headers, healthHttp)
            }
            val timeout = Duration.ofSeconds(probe.timeoutSeconds.toLong())
            return Completion(
                ready,
                checkNotNull(probe.model) { "a completion probe names its model (boot refuses one that does not)" },
                timeout,
                probe.everySeconds * MS_PER_SECOND,
                startHealthy = !probe.atStart,
                http = JdkHealthHttp(ProviderTrust(log).manager(key, provider.caFile, configDir), timeout),
            )
        }
    }
}

// The statuses that say a server can take a turn, the 2xx: llama-server answers 200 once its model is loaded and 503
// before.
private const val OK_FROM = 200

// The first status past the 2xx: an answer under it, from 200 up, is a healthy server's.
private const val OK_BELOW = 300

// The probe kind that asks for a completion; any other is the health probe (boot refuses a kind that is neither).
private const val COMPLETION_KIND = "completion"
