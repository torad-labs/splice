// NEW: V4-132 — the console's login-id/poll seam. POST /api/auth/{head}/login starts a
// DeviceLoginFlow or OAuthLoginFlow OFF the HTTP request that asked for it (RFC 8628 polling and
// the OAuth loopback callback both run for minutes; a request handler must not), and this is where
// GET /api/auth/{head}/login/{id} reads back what the flow has announced so far. On landing (the
// credential persisted), it calls the caller-supplied HeadRestart so the account joins its pool —
// the row's "the state reads signed in, live after restart until then". DELETE/PATCH
// /api/auth/{head}/accounts/{label} are plain OAuthAccountFiles calls, no off-request state.
//
// ConsoleAccountsImpl owns account mutations and delegates asynchronous sign-in to this store.
package splice.app.auth

import kotlinx.coroutines.launch
import splice.accounts.signin.HeadRestart
import splice.accounts.signin.LoginFailure
import splice.accounts.signin.LoginPrompt
import splice.accounts.signin.LoginState
import splice.accounts.signin.LoginStatus
import splice.app.cli.auth.LoginCommand
import splice.configuration.add.AddSignIn
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.LruSizing
import splice.core.util.SafeFailureText
import splice.oauth.LoginAnnouncement
import splice.oauth.LoginObserver
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
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

    /** Starts [provider]'s login flow off-request and returns immediately with its STARTING view.
     *  [restart] null is a head the daemon does not run yet (V4-220, a console add): the credential
     *  lands and the state stays signed in, since no restart of this login's would bring it up. */
    internal fun start(
        headKey: String,
        provider: ProviderConfig,
        topology: Topology,
        label: String?,
        restart: HeadRestart?,
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
        restart: HeadRestart?,
        cell: AtomicReference<LoginStatus>,
    ) {
        val result = Cancellables.runCatchingBestEffort {
            LoginCommand().runLoginAttempt(attempt.headKey, attempt.provider, attempt.topology, attempt.label, observer)
        }.getOrElse { e ->
            update(cell) { it.failed(thrown(e), SafeFailureText.render(e)) }
            null
        } ?: return
        if (!result.ok) {
            update(cell) { it.failed(refused(result), result.refusal ?: "login did not complete") }
            return
        }
        update(cell) {
            it.copy(
                state = LoginState.SIGNED_IN,
                label = result.account?.persistedLabel(),
            )
        }
        if (restart == null) return
        val restarted = Cancellables.runCatchingBestEffort { restart.restart() }.isSuccess
        if (restarted) update(cell) { it.copy(state = LoginState.LIVE_AFTER_RESTART) }
    }

    /** The kinds SafeFailureText keeps whole: a path for a file, a host or a timeout for the network. */
    private fun thrown(e: Throwable): LoginFailure = when (e) {
        is java.nio.file.FileSystemException -> LoginFailure.FILE
        is java.net.SocketException,
        is java.net.UnknownHostException,
        is java.io.InterruptedIOException,
        is java.io.EOFException,
        -> LoginFailure.NETWORK
        else -> LoginFailure.NOT_COMPLETED
    }

    /** A refusal splice wrote: the credential not reaching disk, or the account or label refused by the config. */
    private fun refused(result: LoginCommand.LoginResult): LoginFailure = when {
        result.account?.refusedOnDisk() == true -> LoginFailure.FILE
        result.refusal != null -> LoginFailure.CONFIG
        else -> LoginFailure.NOT_COMPLETED
    }

    private fun announce(cell: AtomicReference<LoginStatus>, detail: LoginAnnouncement) {
        update(cell) {
            it.copy(
                state = LoginState.WAITING,
                prompt = LoginPrompt(
                    userCode = detail.userCode ?: it.prompt.userCode,
                    verificationUri = detail.verificationUri ?: it.prompt.verificationUri,
                    browserUrl = detail.browserUrl ?: it.prompt.browserUrl,
                ),
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

/** [LoginSessions.start]'s four flow-identifying args, bundled so [LoginSessions.runAndLand]
 *  stays under detekt's 6-param LongParameterList wall (functionThreshold: 6) alongside its own
 *  observer/restart/cell. */
private data class LoginAttempt(
    val headKey: String,
    val provider: ProviderConfig,
    val topology: Topology,
    val label: String?,
)

/** V4-220 item 3: the add's sign-in over this file's flows, for a head that is not in splice.toml yet.
 *  Its own [LoginSessions]: an add polls through GET /api/add/{id}, never /api/auth/{head}/login/{id},
 *  whose head resolution a candidate head would fail. */
internal class AddSignInSessions(private val sessions: LoginSessions = LoginSessions()) : AddSignIn {
    override fun start(key: String, provider: ProviderConfig, topology: Topology): LoginStatus =
        sessions.start(key, provider, topology, label = null, restart = null)

    override fun poll(id: String): LoginStatus? = sessions.poll(id)
}
