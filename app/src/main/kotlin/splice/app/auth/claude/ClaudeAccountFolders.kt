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
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

// why: splice's own record beside the two Claude Code files — when this account was added, which is the pool's
// default order. Claude Code neither writes nor reads it, and a re-sign-in keeps the original time.
private const val RECORD_FILE = ".splice-account.json"

// why: the label names a directory, so it must carry no separator and no leading dot; the same shape the stored
// Claude Code logins already use (CLAUDE_LOGIN_LABEL_PATTERN), so one rule reads every Claude login label.
private val ACCOUNT_LABEL = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

// why: splice's own root for these folders, one subdirectory per head, so a head reads only its own accounts.
private const val ACCOUNTS_DIR = "claude-accounts"

// why: a landing folder sits under a sibling root, so a half-finished sign-in is never read as an account and the
// real folder is written only after the account is proven.
private const val PENDING_DIR = "claude-accounts-pending"

/** Where an added account's files live, and what a head or a label may be called. One place, because every read and
 *  every write below goes through it: a name that does not pass [names] never reaches the filesystem. */
internal class ClaudeAccountPaths(private val stateDir: Path) {
    /** True when [name] can be a head or a label: letters, digits, `-` or `_`, no separator and no leading dot. */
    fun names(name: String): Boolean = ACCOUNT_LABEL.matches(name)

    /** [head]'s own accounts root. Every label it holds is a directory directly under this. */
    fun accounts(head: String): Path = stateDir.resolve(ACCOUNTS_DIR).resolve(head)

    /** [label]'s own folder on [head]. */
    fun account(head: String, label: String): Path = accounts(head).resolve(label)

    /** Where a sign-in for [label] on [head] runs before it is proven: a sibling root, never read as an account. */
    fun pending(head: String, label: String): Path = stateDir.resolve(PENDING_DIR).resolve(head).resolve(label)
}

/** The pool label of the caller's own Claude Code sign-in, a Claude head's first login. Reserved so no added
 *  account can take it. */
internal const val OWN_SIGN_IN_LABEL = "claude-code"

/** One added account: its [label], the identity Claude Code recorded, when it was added, and the folder splice owns
 *  for it. Never its token — [ClaudeAccountFolders.token] is the one reader, at send and probe time.
 *
 *  A folder splice cannot read is STILL one of these: somebody added that account and its label is on the pool, so
 *  it keeps its row with no [identity] and a [refusal] in words (V4-410). Dropping it, or hanging its sentence on
 *  another account's row, is how a login disappears from Accounts or blames the wrong card. */
internal data class ClaudeAccount(
    val label: String,
    val identity: ClaudeAccountIdentity?,
    val addedAtEpochMillis: Long,
    val directory: Path,
    val refusal: String? = null,
    val displayName: String = label,
) {
    val credentials: Path get() = directory.resolve(CREDENTIALS_JSON)
}

/** A sign-in in flight: Claude Code writes [directory], then [ClaudeAccountFolders.land] decides. */
internal data class ClaudePendingAccount(val head: String, val label: String, val directory: Path)

/** What [ClaudeAccountFolders.remove] found when asked to take an account off a head. The facts only: the sentence
 *  a person reads belongs to the surface that asked, because the console and the CLI word it differently. */
internal sealed class ClaudeAccountRemoval {
    /** The folder is gone, so that credential cannot be read again even before the head restarts. */
    data object Removed : ClaudeAccountRemoval()

    /** This head has no added account under that label, including a label no folder could ever carry. */
    data object NotFound : ClaudeAccountRemoval()

    /** The label names the caller's own Claude Code sign-in, which splice forwards and has never held, so there is
     *  nothing here to delete. Removing it would mean deleting the person's real Claude Code login. */
    data object OwnSignIn : ClaudeAccountRemoval()

    /** The folder is still on disk. Its credential may already be gone, which [ClaudeAccountFolders.accounts]
     *  reports as a label that needs signing in again. */
    data object Failed : ClaudeAccountRemoval()
}

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
    stateDir: Path,
    private val now: WallClock = WallClock(System::currentTimeMillis),
    private val profileRefresh: ClaudeIdentityRefresh? = null,
) {
    private val facts = ClaudeLoginFactsReader(
        ClaudeCredentialProfiles(stateDir, splice.core.util.LogSink { }),
        profileRefresh,
    )
    private val paths = ClaudeAccountPaths(stateDir)

    /** Every account of [head], oldest first: the order they were added, which is the pool's default. A label whose
     *  folder splice cannot read is here too, carrying its own refusal and no identity, and sorts last. */
    fun accounts(head: String): List<ClaudeAccount> = read(head)
        .map { (label, account) -> account ?: refused(label, head) }
        .sortedWith(compareBy(ClaudeAccount::addedAtEpochMillis, ClaudeAccount::label))

    /** The row a label keeps when its folder is not a readable login. It sorts last because its own `added_at` is
     *  unreadable with everything else in the folder, and a working login is the one a turn should reach first. */
    private fun refused(label: String, head: String): ClaudeAccount = ClaudeAccount(
        label = label,
        identity = null,
        addedAtEpochMillis = Long.MAX_VALUE,
        directory = paths.account(head, label),
        refusal = "this sign-in is unreadable; sign in again",
    )

    /** [label]'s current access token, read at send or probe time, or null when nothing is filed under it. */
    fun token(head: String, label: String): String? =
        if (paths.names(label) && paths.names(head)) facts.token(paths.account(head, label)) else null

    /** Where Claude Code should write a sign-in for [label] on [head]. The caller creates it; this reads nothing of
     *  the existing folder, so a sign-in in flight cannot damage an account already added. */
    fun pending(head: String, label: String): ClaudePendingAccount {
        require(paths.names(label)) { "'$label' is not a valid account label (letters, digits, - or _)" }
        require(label != OWN_SIGN_IN_LABEL) { "'$label' names your own Claude Code sign-in; choose another label" }
        require(paths.names(head)) { "'$head' is not a valid command name" }
        return ClaudePendingAccount(head, label, paths.pending(head, label))
    }

    /** Proves a completed native sign-in with that pending folder's own captured credential. */
    suspend fun verify(pending: ClaudePendingAccount) = facts.refresh(pending.directory)

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

    /** Takes [label] off [head]: only that label's own folder is deleted, and only when it is one this store owns.
     *  The caller's own sign-in is refused BY NAME rather than treated as a missing account, because that one is the
     *  person's real Claude Code login and splice has never held it. */
    fun remove(head: String, label: String): ClaudeAccountRemoval {
        if (label == OWN_SIGN_IN_LABEL) return ClaudeAccountRemoval.OwnSignIn
        val owned = paths.names(label) && paths.names(head)
        val directory = if (owned) paths.account(head, label) else null
        if (directory == null || !Files.isDirectory(directory)) return ClaudeAccountRemoval.NotFound
        discard(directory)
        return if (Files.exists(directory)) ClaudeAccountRemoval.Failed else ClaudeAccountRemoval.Removed
    }

    /** Renames display metadata only. The stable id, credential, quota, pin and account order stay unchanged. */
    fun relabel(head: String, id: String, name: String): splice.accounts.signin.AccountMutation {
        val added = accounts(head)
        val account = added.singleOrNull { it.label == id }
        return when {
            !listOf(head, id, name).all(paths::names) ->
                splice.accounts.signin.AccountMutation.Refused("that is not a valid stored login name")
            account == null ->
                splice.accounts.signin.AccountMutation.Refused("this command has no stored login with that id")
            added.any { it.label != id && it.displayName == name } ->
                splice.accounts.signin.AccountMutation.Refused("another stored login already uses that name")
            else -> {
                val record = ClaudeAccountRecord(account.addedAtEpochMillis, name)
                SecureFile.writeAtomic0600(account.directory.resolve(RECORD_FILE), record.wire())
                splice.accounts.signin.AccountMutation.Ok
            }
        }
    }

    /** The proven half of [land]: this head's pool either already holds [identity] under another label, or the
     *  sign-in becomes [ClaudePendingAccount.label]'s own folder. A label signed in again keeps its original time. */
    private fun file(pending: ClaudePendingAccount, identity: ClaudeAccountIdentity): ClaudeAccountLanding {
        val added = accounts(pending.head)
        val held = added.firstOrNull { it.identity?.uuid == identity.uuid }
        if (held != null && held.label != pending.label) return ClaudeAccountLanding.AlreadyAdded(held.label)
        move(pending, addedAt = held?.addedAtEpochMillis?.takeIf { it != Long.MAX_VALUE } ?: filedAt(added))
        return ClaudeAccountLanding.Added(pending.label)
    }

    /** When splice files a new account: the clock, carried past the newest account this command already holds so two
     *  sign-ins in one millisecond keep the order they were made in. Without that, the add order of a fast pair fell
     *  back to the labels' own order, which is not the order anybody added them in. */
    private fun filedAt(added: List<ClaudeAccount>): Long {
        val newest = added.map(ClaudeAccount::addedAtEpochMillis).filter { it != Long.MAX_VALUE }.maxOrNull() ?: 0L
        return maxOf(now(), newest + 1L)
    }

    /** Puts the pending sign-in's two files in [label]'s own folder, owner-only. The account record lands first and
     *  the credential last, so an interrupted move leaves a folder [read] reports unreadable rather than an account
     *  with another account's token. [addedAt] is the original time when this label is being signed in again. */
    private fun move(pending: ClaudePendingAccount, addedAt: Long) {
        val directory = paths.account(pending.head, pending.label)
        Files.createDirectories(directory)
        val displayName = facts.displayName(directory.resolve(RECORD_FILE))
        SecureFile.writeAtomic0600(directory.resolve(RECORD_FILE), ClaudeAccountRecord(addedAt, displayName).wire())
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
        if (!paths.names(head)) return emptyList()
        val directory = paths.accounts(head)
        val labels = try {
            Files.list(directory).use { entries ->
                entries.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList()
            }
        } catch (_: NoSuchFileException) {
            return emptyList() // No directory yet is no account added yet.
        }
        return labels.filter(paths::names).sorted().map { label -> label to account(label, directory) }
    }

    private fun account(label: String, under: Path): ClaudeAccount? {
        val folder = under.resolve(label)
        if (facts.token(folder) == null) return null
        val identity = facts.identity(folder)
        return ClaudeAccount(
            label,
            identity,
            facts.addedAt(folder.resolve(RECORD_FILE)) ?: 0L,
            folder,
            displayName = facts.displayName(folder.resolve(RECORD_FILE)) ?: label,
        )
    }

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
