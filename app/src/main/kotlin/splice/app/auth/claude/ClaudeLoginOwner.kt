// NEW: destination-scoped native login attempts share the daemon lifecycle and preserve save-back policy.
package splice.app.auth.claude

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.claude.ClaudeCarrying
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginPlaces
import splice.accounts.signin.AccountMutation
import splice.accounts.signin.LoginState
import splice.accounts.signin.LoginStatus
import splice.client.ClaudeLoginResult
import splice.client.ClaudeLogins
import splice.client.ClaudeNativeLoginPreparation
import splice.core.util.Cancellables
import splice.launch.recipe.LaunchLoginGuard
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

// why: two destinations can run concurrently; 32 statuses retain recent completed clicks without an unbounded map.
private const val NATIVE_LOGIN_HISTORY = 32

// Browser authentication cannot hold a destination or daemon child indefinitely.
private const val NATIVE_LOGIN_TIMEOUT_MS = 10L * 60L * 1000L

private class NativeAttempt(
    val location: ClaudeLoginLocation,
    val logins: ClaudeLogins,
    val prepared: ClaudeNativeLoginPreparation.Ready,
    val status: AtomicReference<LoginStatus>,
) {
    @Volatile
    var child: NativeClaudeAuthRun? = null

    fun fail(reason: String): LoginStatus =
        status.updateAndGet { it.copy(state = LoginState.FAILED, failureReason = reason) }
}

internal class ClaudeLoginOwner(
    private val locations: List<ClaudeLoginLocation>,
    private val reads: ClaudeLoginRead,
    private val sessions: ClaudeLoginSessions,
    auth: NativeClaudeAuth,
    private val scope: CoroutineScope,
    private val changes: ClaudePoolChanges? = null,
    private val carried: ClaudeCarryingPlaces = ClaudeCarryingPlaces(locations, reads),
) : ClaudeLoginPlaces, LaunchLoginGuard, ClaudeCarrying by carried {
    private val lock = Any()
    private val logins = locations.associate { it.id to ClaudeLogins(it.storeDir) }
    private val active = mutableMapOf<ClaudeLoginPlaceId, NativeAttempt>()
    private val history = LinkedHashMap<String, AtomicReference<LoginStatus>>()
    private val mutating = mutableSetOf<ClaudeLoginPlaceId>()
    private val edits = ClaudeLoginEdits(reads)
    private val runner = NativeLoginRunner(auth, sessions, changes)
    private val dirs = ConfigDirs()

    private val names = ClaudeLoginNames { place, key -> logins[place]?.labelForCredential(key) }

    override fun places(): List<ClaudeLoginPlaceView> = reads.places(locations, names)

    internal fun accountForCredential(key: String): ClaudeAccountIdentity? = reads.accountForCredential(locations, key)

    /** The digest of a credential [head] just sent for [session], never the token: it decides which place carries
     *  that head, and which carries that session. */
    internal fun sent(head: String, session: String?, key: String) = carried.sent(head, session, key)

    // One place is still read against ALL of them: a login's window belongs to its account, and the account's
    // other logins are where that reading may have been filed.
    override suspend fun refresh(place: ClaudeLoginPlaceId): ClaudeLoginPlaceView {
        val location = locations.single { it.id == place }
        reads.refresh(location)
        changes?.publish(location.target.head.key)
        return places().single { it.id == place }
    }

    override fun poll(id: String): LoginStatus? = synchronized(lock) { history[id]?.get() }

    override fun refusal(configDir: Path): String? = synchronized(lock) {
        if (locations.any { it.id in mutating && dirs.same(it.target.head.configDir, configDir) }) {
            return@synchronized "this native login is being edited; launch after it finishes"
        }
        active.values.firstOrNull { dirs.same(it.location.target.head.configDir, configDir) }
            ?.let { "native login is replacing this command's credential; launch after it finishes" }
    }

    override suspend fun login(place: ClaudeLoginPlaceId, label: String?): LoginStatus {
        // The status names the login's OWN head. A fixed string here made every card's "Sign in again" report
        // claude-splice's head, so the console drew one command's running sign-in on another command's card.
        val head = locations.firstOrNull { it.id == place }?.target?.head?.key ?: place.command
        val cell = AtomicReference(LoginStatus(UUID.randomUUID().toString(), head, LoginState.STARTING))
        val attempt = synchronized(lock) {
            remember(cell)
            prepare(place, label, cell)
        } ?: return cell.get()
        val job = scope.launch { runner.run(attempt) }
        job.invokeOnCompletion { releaseQuietly(attempt) }
        return cell.get()
    }

    private fun prepare(
        place: ClaudeLoginPlaceId,
        label: String?,
        cell: AtomicReference<LoginStatus>,
    ): NativeAttempt? {
        val location = locations.singleOrNull { it.id == place }
        if (location == null) {
            runner.failed(cell, "native login place is not configured")
            return null
        }
        val busy = refusal(location.target.head.configDir)
        if (busy != null) {
            runner.failed(cell, busy)
            return null
        }
        val store = logins.getValue(place)
        return when (val prepared = store.prepareNative(location.target, label, sessions.read(location))) {
            is ClaudeNativeLoginPreparation.Refused -> {
                runner.failed(cell, prepared.reason)
                null
            }
            is ClaudeNativeLoginPreparation.Ready -> NativeAttempt(location, store, prepared, cell)
                .also { active[place] = it }
        }
    }

    override suspend fun remove(place: ClaudeLoginPlaceId): AccountMutation = edit(place, null)

    override suspend fun relabel(place: ClaudeLoginPlaceId, label: String): AccountMutation = edit(place, label)

    private fun edit(place: ClaudeLoginPlaceId, label: String?): AccountMutation {
        val location = synchronized(lock) {
            val found = locations.singleOrNull { it.id == place }
                ?: return AccountMutation.Refused("native login place is not configured")
            if (refusal(found.target.head.configDir) != null) {
                return AccountMutation.Refused("this native login is busy")
            }
            mutating += place
            found
        }
        return try {
            val store = logins.getValue(place)
            if (label == null) {
                edits.remove(location, store, sessions.read(location))
            } else {
                edits.relabel(location, store, label)
            }
        } finally {
            changes?.publish(location.target.head.key)
            synchronized(lock) { mutating -= place }
        }
    }

    override suspend fun submit(id: String, code: String): Boolean {
        val child = synchronized(lock) { active.values.firstOrNull { it.status.get().id == id }?.child } ?: return false
        return Cancellables.runCatchingCancellable { child.submit(code) }
            .onFailure { /* Closed stdin refuses submission; neither code nor child text enters diagnostics. */ }
            .getOrDefault(false)
    }

    private fun remember(cell: AtomicReference<LoginStatus>) {
        while (history.size >= NATIVE_LOGIN_HISTORY) {
            val removable = history.entries.firstOrNull { entry -> active.values.none { it.status === entry.value } }
                ?: break
            history.remove(removable.key)
        }
        history[cell.get().id] = cell
    }

    private fun releaseQuietly(attempt: NativeAttempt) {
        synchronized(lock) {
            if (attempt.status.get().state == LoginState.STARTING || attempt.status.get().state == LoginState.WAITING) {
                runner.failed(attempt.status, "native Claude login was cancelled")
            }
            if (active[attempt.location.id] === attempt && attempt.child?.stopped != false) {
                active.remove(attempt.location.id)
            }
        }
    }
}

/** Drives one native login attempt from the CLI's browser step to a landed account, split out of the owner. */
private class NativeLoginRunner(
    private val auth: NativeClaudeAuth,
    private val sessions: ClaudeLoginSessions,
    private val changes: ClaudePoolChanges?,
) {
    suspend fun run(attempt: NativeAttempt) {
        try {
            val outcome = Cancellables.runCatchingCancellable {
                withTimeout(NATIVE_LOGIN_TIMEOUT_MS) { authenticate(attempt) }
            }.onFailure { attempt.fail("native Claude login stopped (${it::class.simpleName})") }
            Cancellables.discard(outcome, "the attempt status records classified native process failure")
        } catch (_: TimeoutCancellationException) {
            attempt.fail("native Claude login expired")
        } catch (cancelled: CancellationException) {
            attempt.fail("native Claude login was cancelled")
            throw cancelled
        }
    }

    private suspend fun authenticate(attempt: NativeAttempt) {
        val child: NativeClaudeAuthRun = when (val begun = auth.begin(attempt.location)) {
            is NativeSignIn.Refused -> {
                attempt.fail(begun.reason)
                return
            }
            is NativeSignIn.Running -> begun.run
        }
        attempt.child = child
        try {
            val completed = child.await { url ->
                attempt.status.updateAndGet {
                    it.copy(state = LoginState.WAITING, prompt = it.prompt.copy(browserUrl = url))
                }
            }
            if (completed) land(attempt) else attempt.fail("native Claude login did not complete")
        } finally {
            child.close()
        }
    }

    private fun land(attempt: NativeAttempt) {
        val target = attempt.location.target
        when (val result = attempt.logins.completeNative(target, attempt.prepared, sessions.read(attempt.location))) {
            is ClaudeLoginResult.Refused -> failed(attempt.status, result.reason)
            is ClaudeLoginResult.Done -> {
                changes?.publish(target.head.key)
                attempt.status.updateAndGet { it.copy(state = LoginState.SIGNED_IN, label = attempt.logins.selected()) }
            }
            ClaudeLoginResult.Ok -> failed(attempt.status, "native login did not record a completed account")
        }
    }

    fun failed(cell: AtomicReference<LoginStatus>, reason: String): LoginStatus =
        cell.updateAndGet { it.copy(state = LoginState.FAILED, failureReason = reason) }
}

/** Whether two config folders are one directory, following links. */
private class ConfigDirs {
    fun same(first: Path, second: Path): Boolean = canonical(first) == canonical(second)

    private fun canonical(path: Path): Path = try {
        path.toRealPath()
    } catch (_: java.nio.file.NoSuchFileException) {
        path.toAbsolutePath().normalize()
    }
}
