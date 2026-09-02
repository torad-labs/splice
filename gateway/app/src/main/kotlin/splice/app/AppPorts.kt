// NEW: the daemon-and-CLI ROLES of :app, named (HD-22, wave 4b).
//
// :app is the composition root, so most of what it threads is a role some LOWER module already
// names — [splice.control.DashboardPage], [splice.control.ShutdownDaemon],
// [splice.control.TopologyStale], [splice.control.TurnPathStalled],
// [splice.dialect.passthrough.IdentityHeaders]. Those are imported, never re-declared: a
// composition root that mints its own twin of a port it is wiring has stopped being a composition
// root. What lives HERE is only what :app itself owns — the OAuth login flows, the refresh HTTP
// hop, daemon lifecycle, and the CLI's process/HTTP edges.
//
// WHY BY ROLE AND NEVER BY SHAPE, the cleanest example in the tree: [HaltJvm] and [Teardown] are
// both `() -> Unit`, are adjacent parameters of the SAME function, and are exact opposites — one is
// the orderly shutdown, the other is the guillotine that fires when the orderly shutdown overruns
// its deadline. Transposing them was a two-token edit that compiled, and it would have made every
// clean stop a `halt(0)`. It is now a type error.
package splice.app

import io.ktor.client.statement.HttpResponse
import splice.control.ManagedHead
import splice.core.auth.RefreshAttempt
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.provider.codex.RefreshedTokens

/**
 * Builds the x-www-form-urlencoded token-exchange body for a real authorization code.
 *
 * Per-provider because the grant bodies genuinely differ (codex and grok disagree on which of
 * `client_id`, `code_verifier` and `redirect_uri` they require), and the ENCODING happens inside —
 * an unencoded `code` reaching the wire is an auth failure the operator sees as an opaque 400.
 */
public fun interface ExchangeForm {
    public operator fun invoke(code: String): String
}

/**
 * Turns a token-endpoint SUCCESS body into the exact `auth.json` content to persist.
 *
 * ONE role under two spellings until now: [OAuthLoginFlow]'s browser-redirect spec and
 * [DeviceLoginFlow]'s device-code spec each declared their own `toAuthJson: (String) -> String`,
 * and they are the same contract — the two flows differ in how the grant is OBTAINED, never in what
 * a provider's credential file looks like afterwards. Unified here, and both specs now name it.
 *
 * The result is written through `SecureFile.writeAtomic0600`, so what comes out of one of these is
 * a credential at rest.
 */
public fun interface AuthJsonFromResponse {
    public operator fun invoke(responseBody: String): String
}

/**
 * The refresh POST itself — one HTTP attempt, no retry policy and no classification.
 *
 * Deliberately the bare hop: `RefreshRetry` owns how many times it runs and how long it waits
 * between runs, and a `call` that retried internally would multiply against that budget invisibly.
 * A throw out of it is treated as a RETRY, not a permanent failure, so a network blip must surface
 * as an exception here rather than as a synthesized failure response.
 */
internal fun interface RefreshPost {
    suspend operator fun invoke(): HttpResponse
}

/**
 * Reads one refresh response and decides whether the loop is DONE or should try again.
 *
 * The decision, not the transport — which is what makes an unrecognized status retryable in one
 * provider and terminal in another without either of them re-implementing the backoff curve. Like
 * [RefreshPost], a throw out of it means retry.
 */
internal fun interface RefreshClassify<T> {
    suspend operator fun invoke(response: HttpResponse): RefreshStep<T>
}

/**
 * The daemon's refresh hop when the TOKEN URL is chosen per call rather than baked in.
 *
 * The two-argument sibling of [splice.core.auth.RefreshCall]: a provider knows its own endpoint and
 * takes only the refresh token, while the daemon builds providers for a whole topology and reads
 * each head's token URL out of it. Same verdict type, one more input, and NOT the same role — a
 * provider that could pick its own token URL would be able to send its credentials somewhere the
 * topology never named.
 */
public fun interface TokenUrlRefreshCall {
    public suspend operator fun invoke(tokenUrl: String, refreshToken: String): RefreshAttempt<RefreshedTokens>
}

/**
 * Builds ONE head from its config — the per-head half of daemon boot, injected so the assembly loop
 * owns the failure bookkeeping and not the construction.
 *
 * It is allowed to THROW, and that is the point of the seam: `assembleDaemonHeads` turns a throw
 * into an entry in the `failed` map and a boot log line, so one misconfigured head degrades the
 * daemon instead of aborting the boot. A head that failed here is counted in
 * [splice.control.FailedHeads] and is never in the `heads` map.
 */
public fun interface HeadAssembly {
    public operator fun invoke(key: String, head: HeadConfig, provider: ProviderConfig): ManagedHead
}

/**
 * Stops the control plane — run AFTER the heads, and run even when the head-stop budget tripped.
 *
 * The ordering is the contract: the control plane is what an operator's `splice stop` is talking
 * to, so it outlives the heads it reports on and goes down last.
 */
public fun interface StopControl {
    public operator fun invoke()
}

/**
 * Halts the JVM outright — `Runtime.halt`, no shutdown hooks, no finalizers.
 *
 * The floor under teardown and nothing else. A cancel cannot kill a thread stuck in uninterruptible
 * blocking work (a wedged engine stop), so this is the only thing that GUARANTEES the process ends.
 * Injected so a test can exercise the overrun path without ending the test JVM.
 *
 * NEVER [Teardown]. See this file's header — same shape, opposite meaning, adjacent parameters.
 */
public fun interface HaltJvm {
    public operator fun invoke()
}

/**
 * The orderly shutdown work, run under a hard deadline.
 *
 * Best-effort by construction: it is given a budget, and if it overruns, [HaltJvm] fires instead.
 * On a clean finish the halt watchdog is disarmed, so the halt never runs.
 */
public fun interface Teardown {
    public operator fun invoke()
}

/**
 * Reads the classpath-bundled dashboard page, or null when the jar carries none.
 *
 * The FALLBACK leg of the dashboard source: the built dist file wins, this is next, and a
 * placeholder is last. Injected because the shadow jar is the only place it really exists
 * (`webui/index.html`), so a test asserting the ladder cannot rely on the real resource.
 */
public fun interface ClasspathHtml {
    public operator fun invoke(): String?
}
