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
//
// A sign-in ends one of five ways and each is final: the account lands, it is refused by name, Claude Code stops, its
// ten minutes run out, or it is cancelled. Cancelled means by the person ([cancel]), by a newer sign-in to the same
// label ([prepare]) or by the daemon stopping, and in each the child is stopped and the pending folder goes. Until Nov
// 11, 2026 there was no cancel: the console's Cancel only forgot the sign-in in the page, Claude Code's child ran on
// for its ten minutes, and the next Sign in on that login was refused as already running (Marcos: "it said it
// canceled, then when I tried sign in again, it said it was already in progress, so it didnt really cancelled").
package splice.app.auth.claude

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import splice.accounts.signin.HeadRestart
import splice.accounts.signin.LoginFailure
import splice.accounts.signin.LoginState
import splice.accounts.signin.LoginStatus
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
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

private const val STOPPED_WITH_SPLICE = "splice stopped while the sign-in was open"

internal class ClaudeAccountSignIn(
    private val folders: ClaudeAccountFolders,
    private val auth: NativeClaudeAuth,
    private val scope: CoroutineScope,
    private val log: LogSink = LogSink { },
) {
    private val lock = Any()
    private val active = mutableMapOf<String, Attempt>()
    private val history = LinkedHashMap<String, AtomicReference<LoginStatus>>()

    /** One sign-in in flight: the folder Claude Code writes, the status the console polls, the child a code pasted
     *  on the card goes to, and the job a cancel stops. */
    private class Attempt(val pending: ClaudePendingAccount, val status: AtomicReference<LoginStatus>) {
        @Volatile
        var child: NativeClaudeAuthRun? = null

        @Volatile
        var job: Job? = null

        /** Completes once the attempt has let go of everything it held: its job is over, its label is free and its
         *  pending folder is gone ([release]). Whoever must find the folder gone waits on this and never on the job:
         *  a job's join answers as soon as the job is over, which is before what runs at its end has run. On Nov 11,
         *  2026 a Cancel answered while the folder it had ended was still on disk (CI, verify jvm-0). */
        val released = CompletableDeferred<Unit>()

        /** Ends it when it is still open: it reads cancelled for [said], and its child is stopped. False for one that
         *  already ended, which keeps its own end and whatever it is still finishing (the head's restart). */
        fun end(said: String): Boolean {
            val before = status.getAndUpdate { it.closed(LoginFailure.CANCELLED, said) }
            if (before.open) job?.cancel()
            return before.open
        }

        fun fail(kind: LoginFailure, reason: String): LoginStatus = status.updateAndGet { it.failed(kind, reason) }

        /** [fail], for an end that must not write over one already reached: a sign-in that landed and is restarting
         *  its head when the daemon stops is still signed in. */
        fun close(kind: LoginFailure, reason: String): LoginStatus = status.updateAndGet { it.closed(kind, reason) }

        /** A line for the daemon's log, written when a sign-in starts and when it ends. It names the sign-in's
         *  command and label and never its page's address or a credential. Until Nov 11, 2026 a sign-in left no
         *  line at all, and what one had done could only be read off the process list and its folder's times. */
        fun line(what: String): String {
            val where = "${pending.head} as ${pending.label}"
            return "[claude-accounts] sign-in ${status.get().shortId} on $where: ${LogSafe.str(what)}\n"
        }
    }

    /** Starts a sign-in that ADDS an account to [head] under [label], or mints one when none is asked for.
     *  [restart] brings the head up on its new pool once the account has landed, the same way an OAuth account
     *  joins: the pool is assembled at head start, so without it the new login would wait for the next one. */
    fun start(head: String, label: String?, restart: HeadRestart? = null): LoginStatus {
        val cell = AtomicReference(LoginStatus(UUID.randomUUID().toString(), head, LoginState.STARTING))
        val attempt = synchronized(lock) {
            remember(cell)
            prepare(head, label, cell, restart)
        } ?: return cell.get()
        log(attempt.line("started"))
        attempt.job?.start()
        return cell.get()
    }

    fun poll(id: String): LoginStatus? = synchronized(lock) { history[id]?.get() }

    /** Ends the sign-in [id] where it stands: Claude Code's child is stopped, its pending folder goes with whatever
     *  it wrote, and its label is free for the next sign-in. Answers how the sign-in ended, which is its own end
     *  when it had already finished, and null for an id this daemon never started. */
    suspend fun cancel(id: String): LoginStatus? {
        val stopped = synchronized(lock) {
            active.values.firstOrNull { it.status.get().id == id }?.takeIf { it.end("the sign-in was cancelled") }
        }
        stopped?.released?.await()
        return poll(id)
    }

    /** Ends the sign-in still open for [label] on [head], when there is one. An account being removed is not signed
     *  in again behind its removal: until Nov 11, 2026 a sign-in left open for a login that was then removed filed
     *  the login again if its page was finished. */
    suspend fun withdraw(head: String, label: String) {
        val stopped = synchronized(lock) {
            active[key(head, label)]?.takeIf { it.end("'$label' was removed from $head") }
        }
        stopped?.released?.await()
    }

    /** Every sign-in still starting or waiting on its browser page, oldest first. */
    fun open(): List<LoginStatus> =
        synchronized(lock) { active.values.map { it.status.get() }.filter(LoginStatus::open) }

    /** The code Claude Code's sign-in page shows, pasted on the card, for the sign-in [id] that is waiting for it.
     *  False when no sign-in by that id is running, or it would not take the code. */
    fun submit(id: String, code: String): Boolean {
        val child = synchronized(lock) { active.values.firstOrNull { it.status.get().id == id }?.child } ?: return false
        return Cancellables.runCatchingCancellable { child.submit(code) }
            .onFailure { /* Closed stdin refuses submission; neither code nor child text enters diagnostics. */ }
            .getOrDefault(false)
    }

    /** The attempt, its pending folder and its job (not yet started), or null with the refusal already on [cell].
     *
     *  A sign-in to the same label that is still open ends here, replaced. Pressing Sign in again is the person asking
     *  for a sign-in page now, and their own earlier press is never a reason to refuse them one. The new child is
     *  given the pending folder only once the earlier one has let go of it. */
    private fun prepare(
        head: String,
        label: String?,
        cell: AtomicReference<LoginStatus>,
        restart: HeadRestart?,
    ): Attempt? {
        val wanted = label?.takeIf { it.isNotBlank() } ?: mint(head)
        val pending = Cancellables.runCatchingCancellable { folders.pending(head, wanted) }
            .onFailure { why ->
                cell.updateAndGet { it.failed(LoginFailure.CONFIG, why.message ?: "that account label cannot be used") }
            }
            .getOrNull() ?: return null
        val earlier = active[key(head, wanted)]
        earlier?.end("a newer sign-in for '$wanted' on $head took its place")
        val attempt = Attempt(pending, cell)
        active[key(head, wanted)] = attempt
        cell.updateAndGet { it.copy(label = wanted) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            earlier?.released?.await()
            if (earlier != null) folders.discard(pending.directory)
            run(attempt, restart)
        }
        job.invokeOnCompletion { release(attempt) }
        attempt.job = job
        return attempt
    }

    /** The first `account-N` on [head] that no filed account and no sign-in still running holds, starting at 2
     *  because the caller's own sign-in is the first login. A sign-in still open keeps its label until it ends, so a
     *  second add started beside it takes the following number. */
    private fun mint(head: String): String {
        val running = active.values.filter { it.pending.head == head }.map { it.pending.label }
        val taken = folders.accounts(head).map(ClaudeAccount::label).toSet() + running
        var next = 2
        while ("$MINTED_LABEL_PREFIX$next" in taken) next++
        return "$MINTED_LABEL_PREFIX$next"
    }

    private suspend fun run(attempt: Attempt, restart: HeadRestart?) {
        try {
            val outcome = Cancellables.runCatchingCancellable {
                withTimeout(ADD_ACCOUNT_TIMEOUT_MS) { authenticate(attempt, restart) }
            }.onFailure { why ->
                val said = "Claude Code's sign-in stopped unexpectedly (${why::class.simpleName})"
                attempt.close(LoginFailure.STOPPED, said)
            }
            Cancellables.discard(outcome, "the attempt status records a classified sign-in failure")
        } catch (_: TimeoutCancellationException) {
            attempt.close(LoginFailure.EXPIRED, "the sign-in page was not finished within 10 minutes")
        } catch (cancelled: CancellationException) {
            // Whoever cancelled it has said why already; only the daemon stopping leaves it open until here.
            attempt.close(LoginFailure.CANCELLED, STOPPED_WITH_SPLICE)
            throw cancelled
        }
    }

    private suspend fun authenticate(attempt: Attempt, restart: HeadRestart?) {
        val cell = attempt.status
        val child = when (val begun = auth.begin(attempt.pending.directory)) {
            is NativeSignIn.Refused -> {
                attempt.fail(LoginFailure.CONFIG, begun.reason)
                return
            }
            is NativeSignIn.Running -> begun.run
        }
        attempt.child = child
        try {
            // The link Claude Code prints is the one whose page ends in a code to paste back ("Paste code here if
            // prompted"), so the status says so and every card that shows the link has the field for the code. Until
            // Nov 11, 2026 only a command's own login card drew that field: Sign in again on an added login, and
            // + Add account, opened the same page and left its code with nowhere to go, so the sign-in stayed open
            // (Marcos: "then trhe sign in in splice showed still in progress").
            val completed = child.await { url ->
                cell.updateAndGet {
                    it.copy(state = LoginState.WAITING, prompt = it.prompt.copy(browserUrl = url, pasteBack = true))
                }
            }
            if (completed) {
                land(attempt.pending, cell, restart)
            } else {
                attempt.fail(LoginFailure.NOT_COMPLETED, "Claude Code's sign-in ended without signing in an account")
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
                // The account is filed, so signed in is what happened, even when a cancel reached it a moment before.
                cell.updateAndGet { it.copy(state = LoginState.SIGNED_IN, failure = null, label = landed.label) }
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
                val said = "that account is already on this command as '${landed.label}', so nothing was added. " +
                    "If that login was blocked, its next turn asks the provider again"
                it.failed(LoginFailure.ALREADY_ADDED, said).copy(label = landed.label)
            }
            // The browser was signed in to another account than the card's. It read "Config error" with nothing to
            // press, as if the config were at fault; it is the person's to do again, with the right account.
            is ClaudeAccountLanding.LabelTaken -> cell.updateAndGet {
                val said = "that sign-in was for another account than '${landed.label}' holds, and a sign-in never " +
                    "replaces a login's account. Sign in again with this login's account, or add the other one"
                it.failed(LoginFailure.OTHER_ACCOUNT, said).copy(label = landed.label)
            }
            is ClaudeAccountLanding.Unreadable ->
                cell.updateAndGet { it.failed(LoginFailure.NOT_COMPLETED, landed.why) }
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
     *  already took this label owns the folder now, and keeps it. The label is freed and the folder removed in one
     *  step, so a sign-in that starts on this label next is never given a folder this one is about to remove. */
    private fun release(attempt: Attempt) {
        val pending = attempt.pending
        try {
            synchronized(lock) {
                attempt.close(LoginFailure.CANCELLED, STOPPED_WITH_SPLICE)
                if (active.remove(key(pending.head, pending.label), attempt)) folders.discard(pending.directory)
            }
            val ended = attempt.status.get()
            val how = ended.failure?.let { "${it.kind.wire} (${it.reason})" } ?: ended.state.wire
            log(attempt.line("ended: $how"))
        } finally {
            attempt.released.complete(Unit)
        }
    }

    private fun key(head: String, label: String): String = "$head/$label"
}

// why: the landed account is filed whether or not the head comes up again, so a refused restart is reported by the
// head's own surface rather than turned into a sign-in failure the person cannot act on.
private const val RESTARTED = "the head restart after an added account is the head surface's to report"
