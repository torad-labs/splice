// NEW: the Claude half of failover within one provider (operator ruling, Oct 3, 11:44 PM CT: "each provider gets a
// head, each head can have multiple subscriptions"; Oct 4, 12:00 AM CT: heads are templates and the console probes
// each account's usage). A Claude head's first login is the caller's own Claude Code sign-in, which splice forwards
// and never holds. Every account added beyond it lives HERE: one splice-owned folder per label under its head, in
// Claude Code's own file format, written by Claude Code's own `auth login` with CLAUDE_CONFIG_DIR pointed at it.
//
// WHY A FOLDER AND NOT `claude setup-token`: a setup-token is scoped user:inference, and the usage endpoint the
// console reads needs user:profile — Anthropic answers a setup-token there with 403 "OAuth token does not meet scope
// requirement user:profile" (anthropics/claude-code#81015). A full login carries both scopes.
//
// WHY SPLICE MAY REFRESH THESE AND NOT THE CALLER'S: Claude Code's refresh tokens are single-use and rotate, so two
// users of one credential revoke each other — that is what took a login down on 2026-09-25 (V4-237, V4-250). Splice
// is the ONLY user of these folders, so it is the only refresher. The caller's own sign-in keeps its own client, and
// splice never writes or refreshes it.
//
// A sign-in runs in a PENDING folder and lands only once its account is read and proven new to this head's pool, so
// one sign-in can never write another account's folder, and adding an account never replaces a login.
package splice.app.auth.claude

import splice.accounts.claude.ClaudeAccountIdentity
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

// why: the label names a directory, so it must carry no separator and no leading dot; the same shape the stored
// Claude Code logins already use (CLAUDE_LOGIN_LABEL_PATTERN), so one rule reads every Claude login label.
private val ACCOUNT_LABEL = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

// why: splice's own root for these folders, one subdirectory per head, so a head reads only its own accounts.
private const val ACCOUNTS_DIR = "claude-accounts"

// why: a landing folder sits under a sibling root, so a half-finished sign-in is never read as an account and the
// real folder is written only after the account is proven.
private const val PENDING_DIR = "claude-accounts-pending"

// why: splice's own record beside the two Claude Code files — when this account was added, which is the pool's
// default order. Claude Code neither writes nor reads it, and a re-sign-in keeps the original time.
private const val RECORD_FILE = ".splice-account.json"

/** The pool label of the caller's own Claude Code sign-in, a Claude head's first login. Reserved so no added
 *  account can take it. */
internal const val OWN_SIGN_IN_LABEL = "claude-code"

/** One added account: its [label], the identity Claude Code recorded, when it was added, and the folder splice owns
 *  for it. Never its token — [ClaudeAccountFolders.token] is the one reader, at send and probe time. */
internal data class ClaudeAccount(
    val label: String,
    val identity: ClaudeAccountIdentity,
    val addedAtEpochMillis: Long,
    val directory: Path,
) {
    val credentials: Path get() = directory.resolve(CREDENTIALS_JSON)
}

/** A sign-in in flight: Claude Code writes [directory], then [ClaudeAccountFolders.land] decides. */
internal data class ClaudePendingAccount(val head: String, val label: String, val directory: Path)

/** What [ClaudeAccountFolders.land] did with a finished sign-in. */
internal sealed class ClaudeAccountLanding {
    /** The account is this head's now, under [label]. */
    data class Added(val label: String) : ClaudeAccountLanding()

    /** This head's pool already holds that account, under [label]; nothing was written. */
    data class AlreadyAdded(val label: String) : ClaudeAccountLanding()

    /** The sign-in left no readable account, so there is no identity to file it under. */
    data class Unreadable(val why: String) : ClaudeAccountLanding()
}

internal class ClaudeAccountFolders(
    private val stateDir: Path,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val facts = ClaudeLoginFactsReader()

    /** Every readable account of [head], oldest first: the order they were added, which is the pool's default. */
    fun accounts(head: String): List<ClaudeAccount> = read(head).mapNotNull { it.second }
        .sortedWith(compareBy(ClaudeAccount::addedAtEpochMillis, ClaudeAccount::label))

    /** The labels of [head] whose folder exists and holds no readable account: Accounts names them, never guesses. */
    fun unreadable(head: String): List<String> = read(head).filter { it.second == null }.map { it.first }

    /** [label]'s current access token, read at send or probe time, or null when nothing is filed under it. */
    fun token(head: String, label: String): String? =
        if (ACCOUNT_LABEL.matches(label) && ACCOUNT_LABEL.matches(head)) facts.token(directory(head, label)) else null

    /** Where Claude Code should write a sign-in for [label] on [head]. The caller creates it; this reads nothing of
     *  the existing folder, so a sign-in in flight cannot damage an account already added. */
    fun pending(head: String, label: String): ClaudePendingAccount {
        require(ACCOUNT_LABEL.matches(label)) { "'$label' is not a valid account label (letters, digits, - or _)" }
        require(label != OWN_SIGN_IN_LABEL) { "'$label' names your own Claude Code sign-in; choose another label" }
        require(ACCOUNT_LABEL.matches(head)) { "'$head' is not a valid command name" }
        return ClaudePendingAccount(head, label, root(PENDING_DIR, head).resolve(label))
    }

    /** Files a finished sign-in as [ClaudePendingAccount.label], or refuses it. The pending folder is gone either
     *  way; only this label's own folder is ever written, and only once the account is proven new to this head. */
    fun land(pending: ClaudePendingAccount): ClaudeAccountLanding = try {
        val identity = facts.identity(pending.directory)
        when {
            identity == null -> ClaudeAccountLanding.Unreadable("the sign-in recorded no account")
            facts.token(pending.directory) == null ->
                ClaudeAccountLanding.Unreadable("the sign-in recorded no credential")
            else -> file(pending, identity)
        }
    } finally {
        discard(pending.directory)
    }

    /** True when an account was filed under [label] on [head] and is now gone. */
    fun remove(head: String, label: String): Boolean {
        if (!ACCOUNT_LABEL.matches(label) || !ACCOUNT_LABEL.matches(head)) return false
        val directory = directory(head, label)
        if (!Files.isDirectory(directory)) return false
        discard(directory)
        return !Files.exists(directory)
    }

    /** The proven half of [land]: this head's pool either already holds [identity] under another label, or the
     *  sign-in becomes [ClaudePendingAccount.label]'s own folder. A label signed in again keeps its original time. */
    private fun file(pending: ClaudePendingAccount, identity: ClaudeAccountIdentity): ClaudeAccountLanding {
        val held = accounts(pending.head).firstOrNull { it.identity.uuid == identity.uuid }
        if (held != null && held.label != pending.label) return ClaudeAccountLanding.AlreadyAdded(held.label)
        move(pending, addedAt = held?.addedAtEpochMillis ?: now())
        return ClaudeAccountLanding.Added(pending.label)
    }

    /** Puts the pending sign-in's two files in [label]'s own folder, owner-only. The account record lands first and
     *  the credential last, so an interrupted move leaves a folder [read] reports unreadable rather than an account
     *  with another account's token. [addedAt] is the original time when this label is being signed in again. */
    private fun move(pending: ClaudePendingAccount, addedAt: Long) {
        val directory = directory(pending.head, pending.label)
        Files.createDirectories(directory)
        SecureFile.writeAtomic0600(directory.resolve(RECORD_FILE), ClaudeAccountRecord(addedAt).wire())
        SecureFile.writeAtomic0600(
            directory.resolve(CLAUDE_JSON),
            Files.readString(pending.directory.resolve(CLAUDE_JSON)),
        )
        SecureFile.writeAtomic0600(
            directory.resolve(CREDENTIALS_JSON),
            Files.readString(pending.directory.resolve(CREDENTIALS_JSON)),
        )
    }

    /** Every label of [head] that has a folder, each with its account or null when the folder is not readable as
     *  one: a missing record, a missing credential, or either file splice cannot parse. */
    private fun read(head: String): List<Pair<String, ClaudeAccount?>> {
        if (!ACCOUNT_LABEL.matches(head)) return emptyList()
        val directory = root(ACCOUNTS_DIR, head)
        val labels = try {
            Files.list(directory).use { entries ->
                entries.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList()
            }
        } catch (_: NoSuchFileException) {
            return emptyList() // No directory yet is no account added yet.
        }
        return labels.filter(ACCOUNT_LABEL::matches).sorted().map { label -> label to account(label, directory) }
    }

    private fun account(label: String, under: Path): ClaudeAccount? {
        val folder = under.resolve(label)
        val identity = facts.identity(folder) ?: return null
        if (facts.token(folder) == null) return null
        return ClaudeAccount(label, identity, addedAt(folder), folder)
    }

    /** When splice filed this folder. A folder from before its record, or one whose record cannot be read, sorts
     *  first: it was added before anything that carries a time. */
    private fun addedAt(folder: Path): Long = facts.addedAt(folder.resolve(RECORD_FILE)) ?: 0L

    private fun directory(head: String, label: String): Path = root(ACCOUNTS_DIR, head).resolve(label)

    private fun root(name: String, head: String): Path = stateDir.resolve(name).resolve(head)

    private fun discard(directory: Path) {
        Cancellables.discard(
            Cancellables.runCatchingBestEffort {
                if (!Files.isDirectory(directory)) return@runCatchingBestEffort
                Files.walk(directory).use { entries ->
                    entries.sorted(Comparator.reverseOrder()).forEach(Files::delete)
                }
            },
            "a pending or removed folder that cannot be deleted is left for the next sign-in to replace",
        )
    }
}
