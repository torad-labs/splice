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
package splice.app.auth.claude

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import splice.accounts.signin.HeadRestart
import splice.accounts.signin.LoginState
import splice.accounts.signin.LoginStatus
import splice.core.util.Cancellables
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

// why: the same browser budget one command's own sign-in gets (ClaudeLoginOwner); a browser cannot hold a daemon
// child open indefinitely, and a pending folder is discarded when the attempt ends either way.
private const val ADD_ACCOUNT_TIMEOUT_MS = 10L * 60L * 1000L

// why: completed attempts stay pollable for the console without an unbounded map, the same bound one command's own
// sign-in history uses.
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
    private val active = mutableMapOf<String, AtomicReference<LoginStatus>>()
    private val history = LinkedHashMap<String, AtomicReference<LoginStatus>>()

    /** Starts a sign-in that ADDS an account to [head] under [label], or mints one when none is asked for.
     *  [restart] brings the head up on its new pool once the account has landed, the same way an OAuth account
     *  joins: the pool is assembled at head start, so without it the new login would wait for the next one. */
    fun start(head: String, label: String?, restart: HeadRestart? = null): LoginStatus {
        val cell = AtomicReference(LoginStatus(UUID.randomUUID().toString(), head, LoginState.STARTING))
        val pending = synchronized(lock) {
            remember(cell)
            prepare(head, label, cell)
        } ?: return cell.get()
        val job = scope.launch { run(pending, cell, restart) }
        job.invokeOnCompletion { release(pending, cell) }
        return cell.get()
    }

    fun poll(id: String): LoginStatus? = synchronized(lock) { history[id]?.get() }

    /** The pending folder this attempt will write, or null with the refusal already on [cell]. */
    private fun prepare(head: String, label: String?, cell: AtomicReference<LoginStatus>): ClaudePendingAccount? {
        val wanted = label?.takeIf { it.isNotBlank() } ?: mint(head)
        if (busy(head, wanted)) {
            failed(cell, "a sign-in for '$wanted' on $head is already running")
            return null
        }
        val pending = Cancellables.runCatchingCancellable { folders.pending(head, wanted) }
            .onFailure { why -> failed(cell, why.message ?: "that account label cannot be used") }
            .getOrNull() ?: return null
        active[key(head, wanted)] = cell
        cell.updateAndGet { it.copy(label = wanted) }
        return pending
    }

    private fun busy(head: String, label: String): Boolean = active[key(head, label)]?.get()?.state?.let {
        it == LoginState.STARTING || it == LoginState.WAITING
    } == true

    /** The first free `account-N` on [head], starting at 2 because the caller's own sign-in is the first login. */
    private fun mint(head: String): String {
        val taken = folders.accounts(head).map(ClaudeAccount::label).toSet()
        var next = 2
        while ("$MINTED_LABEL_PREFIX$next" in taken) next++
        return "$MINTED_LABEL_PREFIX$next"
    }

    private suspend fun run(
        pending: ClaudePendingAccount,
        cell: AtomicReference<LoginStatus>,
        restart: HeadRestart?,
    ) {
        try {
            val outcome = Cancellables.runCatchingCancellable {
                withTimeout(ADD_ACCOUNT_TIMEOUT_MS) { authenticate(pending, cell, restart) }
            }.onFailure { why -> failed(cell, "the sign-in stopped (${why::class.simpleName})") }
            Cancellables.discard(outcome, "the attempt status records a classified sign-in failure")
        } catch (_: TimeoutCancellationException) {
            failed(cell, "the sign-in expired")
        } catch (cancelled: CancellationException) {
            failed(cell, "the sign-in was cancelled")
            throw cancelled
        }
    }

    private suspend fun authenticate(
        pending: ClaudePendingAccount,
        cell: AtomicReference<LoginStatus>,
        restart: HeadRestart?,
    ) {
        val child = when (val begun = auth.begin(pending.directory)) {
            is NativeSignIn.Refused -> {
                failed(cell, begun.reason)
                return
            }
            is NativeSignIn.Running -> begun.run
        }
        try {
            val completed = child.await { url ->
                cell.updateAndGet { it.copy(state = LoginState.WAITING, browserUrl = url) }
            }
            if (completed) land(pending, cell, restart) else failed(cell, "the sign-in did not complete")
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
            is ClaudeAccountLanding.AlreadyAdded ->
                failed(cell, "that account is already on this command as '${landed.label}'")
            is ClaudeAccountLanding.Unreadable -> failed(cell, landed.why)
        }
    }

    private fun remember(cell: AtomicReference<LoginStatus>) {
        while (history.size >= ADD_ACCOUNT_HISTORY) {
            val removable = history.entries.firstOrNull { entry -> active.values.none { it === entry.value } } ?: break
            history.remove(removable.key)
        }
        history[cell.get().id] = cell
    }

    private fun release(pending: ClaudePendingAccount, cell: AtomicReference<LoginStatus>) {
        synchronized(lock) {
            if (busy(pending.head, pending.label)) failed(cell, "the sign-in was cancelled")
            active.remove(key(pending.head, pending.label), cell)
        }
    }

    private fun failed(cell: AtomicReference<LoginStatus>, reason: String): LoginStatus =
        cell.updateAndGet { it.copy(state = LoginState.FAILED, failureReason = reason) }

    private fun key(head: String, label: String): String = "$head/$label"
}

// why: the landed account is filed whether or not the head comes up again, so a refused restart is reported by the
// head's own surface rather than turned into a sign-in failure the person cannot act on.
private const val RESTARTED = "the head restart after an added account is the head surface's to report"
