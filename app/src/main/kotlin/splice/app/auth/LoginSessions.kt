// NEW: V4-132 — the console's login-id/poll seam. POST /api/auth/{head}/login starts a
// DeviceLoginFlow or OAuthLoginFlow OFF the HTTP request that asked for it (RFC 8628 polling and
// the OAuth loopback callback both run for minutes; a request handler must not), and this is where
// GET /api/auth/{head}/login/{id} reads back what the flow has announced so far. On landing (the
// credential persisted), it calls the caller-supplied HeadRestart so the account joins its pool —
// the row's "the state reads signed in, live after restart until then". DELETE/PATCH
// /api/auth/{head}/accounts/{label} are plain OAuthAccountFiles calls, no off-request state.
//
// This file is also the :app SIDE of the splice.accounts.signin.ConsoleAccounts port: :features-accounts
// sits below :app, and the flows + OAuthAccountFiles both live here, so the port is how an accounts route
// reaches them (ConsoleWiring assigns ConsolePorts.accounts, the same shape as its other nine).
package splice.app.auth

import kotlinx.coroutines.launch
import splice.accounts.signin.AccountMutation
import splice.accounts.signin.ConsoleAccounts
import splice.accounts.signin.HeadRestart
import splice.accounts.signin.LoginStart
import splice.accounts.signin.LoginState
import splice.accounts.signin.LoginStatus
import splice.app.cli.auth.LoginCommand
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.LruSizing
import splice.core.util.SafeFailureText
import splice.oauth.LoginAnnouncement
import splice.oauth.LoginObserver
import splice.oauth.OAuthAccountFiles
import splice.oauth.OAuthAccountRefused
import splice.topology.TopologyLoader
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

// why: a login attempt is a short-lived, operator-driven event — one per click in the console.
// 256 is far above any plausible burst of concurrent logins across every head, and the map is
// access-ordered so the cap only ever evicts attempts nobody has polled in a long time.
private const val MAX_TRACKED_LOGINS = 256

/** Off-request orchestration for one head's login attempts. A daemon-lifetime singleton (held by
 *  [ConsoleAccountsImpl]), so every login this daemon starts shares one bounded id space. */
internal class LoginSessions(
    private val scope: LifecycleScope = LifecycleScope(ProcessDispatchers().io()),
) {
    private val lock = Any()
    private val sessions = object : LinkedHashMap<String, AtomicReference<LoginStatus>>(
        LruSizing.INITIAL_CAPACITY,
        LruSizing.LOAD_FACTOR,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AtomicReference<LoginStatus>>?) =
            size > MAX_TRACKED_LOGINS
    }

    /** Starts [provider]'s login flow off-request and returns immediately with its STARTING view. */
    internal fun start(
        headKey: String,
        provider: ProviderConfig,
        topology: Topology,
        label: String?,
        restart: HeadRestart,
    ): LoginStatus {
        val id = UUID.randomUUID().toString()
        val cell = AtomicReference(LoginStatus(id, headKey, LoginState.STARTING))
        synchronized(lock) { sessions[id] = cell }
        val observer = LoginObserver { detail -> announce(cell, detail) }
        val attempt = LoginAttempt(headKey, provider, topology, label)
        scope.launch { runAndLand(attempt, observer, restart, cell) }
        return cell.get()
    }

    internal fun poll(id: String): LoginStatus? = synchronized(lock) { sessions[id] }?.get()

    private suspend fun runAndLand(
        attempt: LoginAttempt,
        observer: LoginObserver,
        restart: HeadRestart,
        cell: AtomicReference<LoginStatus>,
    ) {
        val result = Cancellables.runCatchingBestEffort {
            LoginCommand().runLoginAttempt(attempt.headKey, attempt.provider, attempt.topology, attempt.label, observer)
        }.getOrElse { e ->
            update(cell) { it.copy(state = LoginState.FAILED, failureReason = SafeFailureText.render(e)) }
            null
        } ?: return
        if (!result.ok) {
            update(cell) { it.copy(state = LoginState.FAILED, failureReason = "login did not complete") }
            return
        }
        update(cell) { it.copy(state = LoginState.SIGNED_IN) }
        val restarted = Cancellables.runCatchingBestEffort { restart.restart() }.isSuccess
        if (restarted) update(cell) { it.copy(state = LoginState.LIVE_AFTER_RESTART) }
    }

    private fun announce(cell: AtomicReference<LoginStatus>, detail: LoginAnnouncement) {
        update(cell) {
            it.copy(
                state = LoginState.WAITING,
                userCode = detail.userCode ?: it.userCode,
                verificationUri = detail.verificationUri ?: it.verificationUri,
                browserUrl = detail.browserUrl ?: it.browserUrl,
            )
        }
    }

    private fun update(cell: AtomicReference<LoginStatus>, transform: LoginStatusUpdate) {
        cell.getAndUpdate { transform(it) }
    }
}

/** The role [LoginSessions.update]'s transform plays (kt-no-lambda-seam): a pure edit of one
 *  session's [LoginStatus]. Named for what it does, not its shape — [AtomicReference.getAndUpdate]
 *  itself wants `java.util.function.UnaryOperator`, foreign and untouched, so [update] still wraps
 *  this in a lambda literal at that one call. */
private fun interface LoginStatusUpdate {
    operator fun invoke(status: LoginStatus): LoginStatus
}

/** One OAuth head's pool target: the typed kind [OAuthAccountFiles] needs, and the primary
 *  credential file its pool is keyed by — [LoginCommand.oauthAuthPath]'s own resolution, reused
 *  rather than re-derived (the file/default-file precedence is single-sourced there). */
private data class OAuthTarget(val kind: AuthKind.OAuth, val primaryFile: Path)

/** [LoginSessions.start]'s four flow-identifying args, bundled so [LoginSessions.runAndLand]
 *  stays under detekt's 6-param LongParameterList wall (functionThreshold: 6) alongside its own
 *  observer/restart/cell. */
private data class LoginAttempt(
    val headKey: String,
    val provider: ProviderConfig,
    val topology: Topology,
    val label: String?,
)

/** The :app implementation of [ConsoleAccounts] — see this file's header for why it lives here. */
internal class ConsoleAccountsImpl(
    private val sessions: LoginSessions = LoginSessions(),
    private val files: OAuthAccountFiles = OAuthAccountFiles(),
) : ConsoleAccounts {

    override suspend fun startLogin(headKey: String, label: String?, restart: HeadRestart): LoginStart {
        val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
        val provider = provider(headKey, topology) ?: return LoginStart.UnknownHead
        if (!AuthKindRegistry.isOAuth(provider.auth.kind)) {
            return LoginStart.UnsupportedAuthKind(provider.auth.kind)
        }
        return LoginStart.Started(sessions.start(headKey, provider, topology, label, restart))
    }

    override fun pollLogin(id: String): LoginStatus? = sessions.poll(id)

    override suspend fun removeAccount(headKey: String, label: String): AccountMutation {
        val target = oauthTarget(headKey) ?: return AccountMutation.UnknownHead
        return mutate {
            val removed = files.remove(target.kind, target.primaryFile, label)
            if (!removed) throw OAuthAccountRefused("no OAuth account labeled '$label'")
        }
    }

    override suspend fun relabelAccount(headKey: String, label: String, newLabel: String): AccountMutation {
        val target = oauthTarget(headKey) ?: return AccountMutation.UnknownHead
        return mutate { files.relabel(target.kind, target.primaryFile, label, newLabel) }
    }

    // inline (kt-no-lambda-seam exemption): a raw () -> Unit here would need a named fun interface
    // for one two-call-site try/catch wrapper — inlining is the same exemption Cancellables' own
    // higher-order helpers use.
    private inline fun mutate(block: () -> Unit): AccountMutation = try {
        block()
        AccountMutation.Ok
    } catch (e: OAuthAccountRefused) {
        AccountMutation.Refused(e.reason)
    }

    private fun provider(headKey: String, topology: Topology): ProviderConfig? {
        val providerKey = topology.heads[headKey]?.provider ?: return null
        return topology.providers[providerKey]
    }

    private fun oauthTarget(headKey: String): OAuthTarget? {
        val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
        val provider = provider(headKey, topology) ?: return null
        val kind = AuthKindRegistry.from(provider.auth.kind) as? AuthKind.OAuth ?: return null
        return OAuthTarget(kind, LoginCommand().oauthAuthPath(provider))
    }
}
