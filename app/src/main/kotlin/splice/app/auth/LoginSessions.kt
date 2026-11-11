// NEW: V4-132 — the console's login-id/poll seam. POST /api/auth/{head}/login starts a
// DeviceLoginFlow or OAuthLoginFlow OFF the HTTP request that asked for it (RFC 8628 polling and
// the OAuth loopback callback both run for minutes; a request handler must not), and this is where
// GET /api/auth/{head}/login/{id} reads back what the flow has announced so far. On landing (the
// credential persisted), it calls the caller-supplied HeadRestart so the account joins its pool —
// the row's "the state reads signed in, live after restart until then". DELETE/PATCH
// /api/auth/{head}/accounts/{label} are plain OAuthAccountFiles calls, no off-request state.
//
// ConsoleAccountsImpl owns account mutations and delegates asynchronous sign-in to this store.
//
// A sign-in can be ended ([cancel]), and a newer one to the same place ends the one before it ([start]): its flow is
// stopped, and the label or callback port it held is free before the next flow asks for it. Until Nov 11, 2026 a
// sign-in could only run out. A second press on the same account was then refused by the first one's own label hold
// or callback port, as a config error or a bare "not completed", for up to five minutes.
package splice.app.auth

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
import splice.core.util.LogSafe
import splice.core.util.LogSink
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
    private val log: LogSink = LogSink { },
) {
    private val lock = Any()
    private val sessions = object : LinkedHashMap<String, Session>(
        LruSizing.INITIAL_CAPACITY,
        LruSizing.LOAD_FACTOR,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>?) =
            size > MAX_TRACKED_LOGINS
    }

    /** One sign-in: the place it signs in to, the status the console polls, and the job a cancel stops. */
    private class Session(val place: String, val cell: AtomicReference<LoginStatus>) {
        @Volatile
        var job: Job? = null

        /** Ends it when it is still open: it reads cancelled for [said], and its flow is stopped. False for one that
         *  already ended, which keeps its own end and whatever it is still finishing (the head's restart). */
        fun end(said: String): Boolean {
            val before = cell.getAndUpdate { it.closed(LoginFailure.CANCELLED, said) }
            if (before.open) job?.cancel()
            return before.open
        }

        /** Waits until a sign-in that ended without signing in has let go of what it held: its callback port and
         *  its label. That is its job's end, whoever ended it and however long ago. One that signed in is not
         *  waited for: it holds neither any more, and keeps what it is finishing (the head's restart). */
        suspend fun released() {
            if (cell.get().failure != null) job?.join()
        }

        /** A line for the daemon's log, written when a sign-in starts and when it ends. It names the sign-in's
         *  command and label and never its code, its page's address or a credential. */
        fun line(what: String): String = "[accounts] sign-in ${cell.get().shortId} on $place: ${LogSafe.str(what)}\n"
    }

    /** Starts [provider]'s login flow off-request and returns immediately with its STARTING view.
     *  [restart] null is a head the daemon does not run yet (V4-220, a console add): the credential
     *  lands and the state stays signed in, since no restart of this login's would bring it up.
     *
     *  A sign-in to the same head and label that is still open ends here, replaced: pressing Sign in again is the
     *  person asking for a sign-in page now. The new flow starts once the earlier one has let go of what it held. */
    internal fun start(
        headKey: String,
        provider: ProviderConfig,
        topology: Topology,
        label: String?,
        restart: HeadRestart?,
    ): LoginStatus {
        val id = UUID.randomUUID().toString()
        val place = place(headKey, label)
        val session = Session(place, AtomicReference(LoginStatus(id, headKey, LoginState.STARTING)))
        val observer = LoginObserver { detail -> announce(session.cell, detail) }
        val attempt = LoginAttempt(headKey, provider, topology, label)
        val job = synchronized(lock) {
            val replaced = "a newer sign-in on $place took its place"
            // Every earlier sign-in of this place is waited for, not only the ones ended here. Of three started
            // quickly, the second ends the first and waits for it; the third ends the second, whose wait ends with
            // it, and the first, already ended, was no longer counted: the third started while the first still held
            // the callback port or the label, and failed on the very conflict a replacement is there to prevent.
            val earlier = sessions.values.filter { it.place == place }.onEach { it.end(replaced) }
            sessions[id] = session
            scope.launch(start = CoroutineStart.LAZY) {
                earlier.forEach { it.released() }
                runAndLand(attempt, observer, restart, session.cell)
            }.also { session.job = it }
        }
        job.invokeOnCompletion { finished(session) }
        log(session.line("started"))
        job.start()
        return session.cell.get()
    }

    internal fun poll(id: String): LoginStatus? = synchronized(lock) { sessions[id] }?.cell?.get()

    /** Ends the sign-in [id] where it stands: its flow is stopped, and its label hold and callback port are free for
     *  the next one by the time this answers. Answers how it ended, which is its own end when it had already
     *  finished, and null for an id this daemon never started. */
    internal suspend fun cancel(id: String): LoginStatus? {
        val session = synchronized(lock) { sessions[id] } ?: return null
        session.end("the sign-in was cancelled")
        // one a newer sign-in or another Cancel ended a moment ago may still be stopping: this answers after it has
        session.released()
        return session.cell.get()
    }

    /** Ends the sign-in still open for [label] on [headKey], when there is one: an account being removed is not
     *  signed in again behind its removal. [label] is null for the head's first account, whose sign-in carries no
     *  label ([start]), so that one is found too. */
    internal suspend fun withdraw(headKey: String, label: String?) {
        val place = place(headKey, label)
        val removed = "the login it was for was removed from $headKey"
        val stopped = synchronized(lock) { sessions.values.filter { it.place == place }.onEach { it.end(removed) } }
        stopped.forEach { it.released() }
    }

    /** What a sign-in is for, as the log names it: the command, and the label when one was asked for. */
    private fun place(headKey: String, label: String?): String =
        if (label.isNullOrBlank()) headKey else "$headKey as $label"

    /** Every sign-in still starting or waiting on the person. */
    internal fun open(): List<LoginStatus> =
        synchronized(lock) { sessions.values.map { it.cell.get() } }.filter(LoginStatus::open)

    /** The job is over. One it left open was stopped with the daemon, the only end that says nothing itself. */
    private fun finished(session: Session) {
        val ended = session.cell.updateAndGet {
            it.closed(LoginFailure.CANCELLED, "splice stopped while the sign-in was open")
        }
        log(session.line("ended: " + (ended.failure?.let { "${it.kind.wire} (${it.reason})" } ?: ended.state.wire)))
    }

    private suspend fun runAndLand(
        attempt: LoginAttempt,
        observer: LoginObserver,
        restart: HeadRestart?,
        cell: AtomicReference<LoginStatus>,
    ) {
        val result = Cancellables.runCatchingBestEffort {
            LoginCommand().runLoginAttempt(attempt.headKey, attempt.provider, attempt.topology, attempt.label, observer)
        }.getOrElse { e ->
            update(cell) { it.closed(thrown(e), SafeFailureText.render(e)) }
            null
        } ?: return
        if (!result.ok) {
            update(cell) { it.closed(refused(result), result.refusal ?: "login did not complete") }
            return
        }
        // The credential is on disk, so signed in is what happened, even when a cancel reached it a moment before.
        update(cell) {
            it.copy(
                state = LoginState.SIGNED_IN,
                failure = null,
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
            if (!it.open) return@update it
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

    override suspend fun cancel(id: String): LoginStatus? = sessions.cancel(id)
}
