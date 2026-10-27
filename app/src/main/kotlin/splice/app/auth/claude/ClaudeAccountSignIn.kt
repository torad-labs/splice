// NEW: the Claude half of failover within one provider (operator ruling, Oct 3, 11:44 PM CT: "each provider gets a
// head, each head can have multiple subscriptions"). "Sign in to another account" for a Claude head: Anthropic's own
// OAuth flow run by Claude Code's own `auth login`, never a form splice draws.
//
// The sign-in runs in a PENDING folder nothing reads as an account, and only lands once the account it recorded is
// read and proven new to this head's pool ([ClaudeAccountFolders.land]). So a sign-in for one head or label can
// never write another's folder, and adding an account never replaces a login that is already filed.
//
// This is the `client` arm of the add-an-account surface every other provider already has: the console's own add
// button, POST /api/auth/{head}/login with a label, and the same polling. An OAuth head mints a device code; a
// Claude head signs in through the client that owns these folders.
//
// It is also what Sign in and Sign in again do on a command's own login card ([ClaudeLoginOwner.login]). Marcos, Oct
// 27, 2026: "SPlice console login ADDs an account, it doesn't replace it". None of these writes a login a running
// session reads, so none of them waits for sessions to stop.
package splice.app.auth.claude

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import splice.accounts.signin.HeadRestart
import splice.accounts.signin.LoginFailure
import splice.accounts.signin.LoginState
import splice.accounts.signin.LoginStatus
import splice.core.util.Cancellables
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

// why: a browser cannot hold a daemon child open indefinitely, and the pending folder is discarded when the attempt
// ends either way.
private const val ADD_ACCOUNT_TIMEOUT_MS = 10L * 60L * 1000L

// why: completed attempts stay pollable for the console without an unbounded map.
private const val ADD_ACCOUNT_HISTORY = 32

/** Where an unlabelled request's label comes from: `account-2` for a head that holds one account already, so the
 *  first added account reads as the second login of the pool, which is what it is. */
private const val MINTED_LABEL_PREFIX = "account-"

internal class ClaudeAccountSignIn(
    private val folders: ClaudeAccountFolders,
    private val auth: NativeClaudeAuth,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val active = mutableMapOf<String, Attempt>()
    private val history = LinkedHashMap<String, AtomicReference<LoginStatus>>()

    /** One sign-in in flight: the folder Claude Code writes, the status the console polls, and the child a code
     *  pasted on the card goes to. */
    private class Attempt(val pending: ClaudePendingAccount, val status: AtomicReference<LoginStatus>) {
        @Volatile
        var child: NativeClaudeAuthRun? = null

        /** Whether it is still starting, or waiting on its browser page. */
        fun open(): Boolean = status.get().state.let { it == LoginState.STARTING || it == LoginState.WAITING }
    }

    /** Starts a sign-in that ADDS an account to [head] under [label], or mints one when none is asked for.
     *  [restart] brings the head up on its new pool once the account has landed, the same way an OAuth account
     *  joins: the pool is assembled at head start, so without it the new login would wait for the next one. */
    fun start(head: String, label: String?, restart: HeadRestart? = null): LoginStatus {
        val cell = AtomicReference(LoginStatus(UUID.randomUUID().toString(), head, LoginState.STARTING))
        val attempt = synchronized(lock) {
            remember(cell)
            prepare(head, label, cell)
        } ?: return cell.get()
        val job = scope.launch { run(attempt, restart) }
        job.invokeOnCompletion { release(attempt) }
        return cell.get()
    }

    fun poll(id: String): LoginStatus? = synchronized(lock) { history[id]?.get() }

    /** The code Claude Code's sign-in page shows, pasted on the card, for the sign-in [id] that is waiting for it.
     *  False when no sign-in by that id is running, or it would not take the code. */
    fun submit(id: String, code: String): Boolean {
        val child = synchronized(lock) { active.values.firstOrNull { it.status.get().id == id }?.child } ?: return false
        return Cancellables.runCatchingCancellable { child.submit(code) }
            .onFailure { /* Closed stdin refuses submission; neither code nor child text enters diagnostics. */ }
            .getOrDefault(false)
    }

    /** The attempt and its pending folder, or null with the refusal already on [cell]. */
    private fun prepare(head: String, label: String?, cell: AtomicReference<LoginStatus>): Attempt? {
        val wanted = label?.takeIf { it.isNotBlank() } ?: mint(head)
        if (busy(head, wanted)) {
            val said = "a sign-in for '$wanted' on $head is already running; finish it in its browser page, or wait " +
                "for it to end"
            failed(cell, LoginFailure.IN_PROGRESS, said)
            return null
        }
        val pending = Cancellables.runCatchingCancellable { folders.pending(head, wanted) }
            .onFailure { why -> failed(cell, LoginFailure.CONFIG, why.message ?: "that account label cannot be used") }
            .getOrNull() ?: return null
        val attempt = Attempt(pending, cell)
        active[key(head, wanted)] = attempt
        cell.updateAndGet { it.copy(label = wanted) }
        return attempt
    }

    private fun busy(head: String, label: String): Boolean = active[key(head, label)]?.open() == true

    /** The first `account-N` on [head] that no filed account and no sign-in still running holds, starting at 2
     *  because the caller's own sign-in is the first login. A sign-in the person walked away from keeps its label
     *  until it ends, so the next one takes the following number rather than being refused as already running. */
    private fun mint(head: String): String {
        val running = active.values.filter { it.pending.head == head }.map { it.pending.label }
        val taken = folders.accounts(head).map(ClaudeAccount::label).toSet() + running
        var next = 2
        while ("$MINTED_LABEL_PREFIX$next" in taken) next++
        return "$MINTED_LABEL_PREFIX$next"
    }

    private suspend fun run(attempt: Attempt, restart: HeadRestart?) {
        val cell = attempt.status
        try {
            val outcome = Cancellables.runCatchingCancellable {
                withTimeout(ADD_ACCOUNT_TIMEOUT_MS) { authenticate(attempt, restart) }
            }.onFailure { why ->
                val said = "Claude Code's sign-in stopped unexpectedly (${why::class.simpleName})"
                failed(cell, LoginFailure.STOPPED, said)
            }
            Cancellables.discard(outcome, "the attempt status records a classified sign-in failure")
        } catch (_: TimeoutCancellationException) {
            failed(cell, LoginFailure.EXPIRED, "the sign-in page was not finished within 10 minutes")
        } catch (cancelled: CancellationException) {
            failed(cell, LoginFailure.CANCELLED, "splice stopped while the sign-in was open")
            throw cancelled
        }
    }

    private suspend fun authenticate(attempt: Attempt, restart: HeadRestart?) {
        val cell = attempt.status
        val child = when (val begun = auth.begin(attempt.pending.directory)) {
            is NativeSignIn.Refused -> {
                failed(cell, LoginFailure.CONFIG, begun.reason)
                return
            }
            is NativeSignIn.Running -> begun.run
        }
        attempt.child = child
        try {
            val completed = child.await { url ->
                cell.updateAndGet { it.copy(state = LoginState.WAITING, prompt = it.prompt.copy(browserUrl = url)) }
            }
            if (completed) {
                land(attempt.pending, cell, restart)
            } else {
                failed(cell, LoginFailure.NOT_COMPLETED, "Claude Code's sign-in ended without signing in an account")
            }
        } finally {
            child.close()
        }
    }

    /** What the console reads after the browser: the account is this head's now, or it is refused BY NAME. The
     *  pending folder is gone either way, and no filed login was touched to find that out. */
    private suspend fun land(
        pending: ClaudePendingAccount,
        cell: AtomicReference<LoginStatus>,
        restart: HeadRestart?,
    ) {
        folders.verify(pending)
        when (val landed = folders.land(pending)) {
            is ClaudeAccountLanding.Added -> {
                cell.updateAndGet { it.copy(state = LoginState.SIGNED_IN, label = landed.label) }
                // A head that will not come up is still a landed account: the credential is filed and the row
                // reads signed in, live after the next start. Its failure is the restart's to report, not this
                // sign-in's to undo.
                if (!landed.membershipPublished) {
                    restart?.let {
                        Cancellables.discard(Cancellables.runCatchingBestEffort { it.restart() }, RESTARTED)
                    }
                }
            }
            is ClaudeAccountLanding.AlreadyAdded -> cell.updateAndGet { // the label names the account already there
                val said = "that account is already on this command as '${landed.label}'"
                it.failed(LoginFailure.ALREADY_ADDED, said).copy(label = landed.label)
            }
            is ClaudeAccountLanding.LabelTaken -> cell.updateAndGet {
                val said = "'${landed.label}' already holds another account on this command; sign in under a new label"
                it.failed(LoginFailure.CONFIG, said).copy(label = landed.label)
            }
            is ClaudeAccountLanding.Unreadable -> failed(cell, LoginFailure.NOT_COMPLETED, landed.why)
        }
    }

    private fun remember(cell: AtomicReference<LoginStatus>) {
        while (history.size >= ADD_ACCOUNT_HISTORY) {
            val removable = history.entries.firstOrNull { entry -> active.values.none { it.status === entry.value } }
                ?: break
            history.remove(removable.key)
        }
        history[cell.get().id] = cell
    }

    /** However the attempt ended (signed in, refused, failed, expired or cancelled), its pending folder goes with
     *  whatever Claude Code wrote in it, so no credential outlives the sign-in that wrote it. A newer sign-in that
     *  already took this label owns the folder now, and keeps it. */
    private fun release(attempt: Attempt) {
        val pending = attempt.pending
        val owned = synchronized(lock) {
            if (attempt.open()) {
                failed(attempt.status, LoginFailure.CANCELLED, "splice stopped while the sign-in was open")
            }
            active.remove(key(pending.head, pending.label), attempt)
        }
        if (owned) folders.discard(pending.directory)
    }

    private fun failed(cell: AtomicReference<LoginStatus>, kind: LoginFailure, reason: String): LoginStatus =
        cell.updateAndGet { it.failed(kind, reason) }

    private fun key(head: String, label: String): String = "$head/$label"
}

// why: the landed account is filed whether or not the head comes up again, so a refused restart is reported by the
// head's own surface rather than turned into a sign-in failure the person cannot act on.
private const val RESTARTED = "the head restart after an added account is the head surface's to report"
