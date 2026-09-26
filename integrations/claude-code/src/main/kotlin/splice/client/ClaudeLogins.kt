// NEW: V4-129 (FEATURES.md 4.5 "Claude logins"), made safe by V4-276 (operator ruling 2026-09-26).
// Several of Claude Code's OWN `.credentials.json` files, stored by splice under a label, for the
// splice-owned Claude head (claude-splice), whose config dir holds the one LIVE login. Claude Code's
// refresh tokens are single-use and rotate (claude-code #27933, #54443, #78020), so a stored copy is
// safe to put back only while it is its account's newest. V4-129 copied the selected copy over the
// live file at every launch; on 2026-09-25 that restored a five-day-old login and upstream revoked it
// (V4-237, V4-250). Nothing is copied at launch now. [ClaudeLogins.login], the verb behind
// `splice login <claude-head> --label <name>`, is the one door, and it holds four invariants:
//  1. SAVE-BACK: before the live login is replaced, it is stored under the label of its own account.
//  2. Nothing changes while a session of the head is live, or while the caller cannot tell.
//  3. IDENTITY: each label records its account's oauthAccount.accountUuid (emailAddress for display)
//     from the head's .claude.json, never a token byte; the credential bytes are copied, never
//     parsed. A live login is never filed under a label of another account.
//  4. FAIL CLOSED: an error in the identity read or the save-back changes nothing live.
// A copy with no account record was stored before these rules, and one whose login left the head
// without a save-back (a /login to another account, a /logout) is marked stale: splice puts neither
// back until a live login re-saves it. Splice never calls Anthropic with the bytes it stores here:
// traffic flows through Claude Code's own login.
package splice.client

import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

// The verb's pieces (V4-276, split for concentration): its types in ClaudeLoginTypes.kt, the head's
// files and splice's store in ClaudeLoginFiles.kt, what it tells the operator in ClaudeLoginSentences.kt.
private const val LOGINS_DIR = "claude-logins"
private val LABEL_SHAPE = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

public class ClaudeLogins(
    storeDir: Path = StatePaths().stateDir.resolve(LOGINS_DIR),
) {
    private val store = LoginStore(storeDir)

    /** Every stored label, sorted, legacy copies included. An absent store dir reads as none. */
    public fun labels(): List<String> = store.labels()

    /** The label the head's live login was saved as or switched to, or null. */
    public fun selected(): String? = store.selected()

    public fun remove(label: String): ClaudeLoginResult {
        if (label !in labels()) return ClaudeLoginResult.Refused("no stored Claude login named '$label'")
        store.remove(label)
        return ClaudeLoginResult.Ok
    }

    /** `splice login <head> --label <label>`: save the head's live login as a new [label], refresh the
     *  copy of the label it already is, or switch the head to another saved label. [discard] lets a
     *  switch drop a live login that is saved under no label. See the header for the invariants. */
    public fun login(
        head: ClaudeHead,
        label: String,
        sessions: HeadSessions,
        discard: Boolean = false,
    ): ClaudeLoginResult {
        val refusal = refusal(head, label, sessions)
        if (refusal != null) return ClaudeLoginResult.Refused(refusal)
        val moved = Cancellables.runCatchingCancellable {
            when (val live = HeadLogin.read(head.configDir)) {
                is Live.Unreadable -> ClaudeLoginResult.Refused(Refusals.unreadable(head, live.why))
                else -> move(head, label, live, discard)
            }
        }
        return moved.getOrElse { failure ->
            ClaudeLoginResult.Refused(Refusals.stopped(head, label, SafeFailureText.render(failure)))
        }
    }

    private fun refusal(head: ClaudeHead, label: String, sessions: HeadSessions): String? = when {
        !LABEL_SHAPE.matches(label) -> "'$label' is not a valid login label (letters, digits, - or _ only)"
        sessions is HeadSessions.Unreadable -> Refusals.unknownSessions(head, sessions.why)
        sessions is HeadSessions.Read && sessions.live.isNotEmpty() -> Refusals.running(head, sessions.live)
        else -> null
    }

    private fun move(head: ClaudeHead, label: String, live: Live, discard: Boolean): ClaudeLoginResult {
        val records = store.records()
        val held = live as? Live.Held
        val owner = held?.let { ownerOf(records, it) }
        val replaced = demoteReplaced(owner)
        val target = targetOf(records, label, replaced)
        val result = when {
            held != null && owner == label -> refresh(head, label, held)
            target != null && !target.stale -> toSaved(head, label to target.account, live, owner, discard)
            else -> toFresh(head, label to target?.account, live, owner, discard)
        }
        if (replaced == null) return result
        return Said.withNote(result, Said.demoted(head, replaced, records[replaced]?.account))
    }

    /** The selected label's live login left the head without a save-back (a /login to another
     *  account, or a /logout): its copy may be older than its last refresh, so it goes stale. The
     *  record keeps its account, so no other account's login is ever filed under it. */
    private fun demoteReplaced(owner: String?): String? =
        store.selected()?.takeIf { it != owner }?.also { store.demote(it) }

    /** [label]'s record as this move sees it: the label just demoted is stale from here on. */
    private fun targetOf(records: Map<String, Record>, label: String, replaced: String?): Record? {
        val record = records[label] ?: return null
        return if (label == replaced) record.copy(stale = true) else record
    }

    /** The label already recording the live login's account: one label per account. */
    private fun ownerOf(records: Map<String, Record>, held: Live.Held): String? =
        records.entries.firstOrNull { it.value.account.uuid == held.account.uuid }?.key

    /** The live login is this label's account's newest: save it over the copy (clearing a stale mark). */
    private fun refresh(head: ClaudeHead, label: String, held: Live.Held): ClaudeLoginResult {
        store.save(label, held.bytes, held.account)
        store.select(label)
        return ClaudeLoginResult.Done(Said.refreshed(head, label, held.account.shown))
    }

    /** A switch to a saved label whose copy is its account's newest. */
    private fun toSaved(
        head: ClaudeHead,
        target: Pair<String, Account>,
        live: Live,
        owner: String?,
        discard: Boolean,
    ): ClaudeLoginResult = when {
        live is Live.Held && owner != null -> {
            store.save(owner, live.bytes, live.account)
            store.unselect()
            switchIn(head, target, live, savedAs = owner)
        }
        live is Live.Held && !discard -> ClaudeLoginResult.Refused(Refusals.unsaved(head, live.account.shown))
        else -> switchIn(head, target, live, savedAs = null)
    }

    /** A label that needs a login from the live head: a new one, a legacy copy, or a stale one (whose
     *  account is known, so only that account's login is filed under it). Nothing stored is put back. */
    private fun toFresh(
        head: ClaudeHead,
        target: Pair<String, Account?>,
        live: Live,
        owner: String?,
        discard: Boolean,
    ): ClaudeLoginResult {
        val (label, account) = target
        val legacy = account == null && label in store.labels()
        val held = live as? Live.Held
            ?: return ClaudeLoginResult.Refused(Refusals.nothingLive(head, label, account, legacy))
        val credentials = HeadLogin.credentials(head.configDir)
        return when {
            owner != null -> {
                store.save(owner, held.bytes, held.account)
                store.unselect()
                Files.delete(credentials)
                ClaudeLoginResult.Done(Said.signedOut(head, owner, label, account, legacy))
            }
            account == null -> {
                store.save(label, held.bytes, held.account)
                store.select(label)
                ClaudeLoginResult.Done(Said.saved(head, label, held.account.shown))
            }
            discard -> {
                Files.delete(credentials)
                ClaudeLoginResult.Done(Said.dropped(head, label, account))
            }
            else -> ClaudeLoginResult.Refused(Refusals.notFiled(head, label, account, held.account))
        }
    }

    /** Put [target]'s stored copy in the head. The copy and the head's .claude.json are both read
     *  before anything is written; if the account cannot be recorded after the credential landed,
     *  the live login that was there is put back. */
    private fun switchIn(
        head: ClaudeHead,
        target: Pair<String, Account>,
        live: Live,
        savedAs: String?,
    ): ClaudeLoginResult {
        val (label, account) = target
        val bytes = store.credentials(label)
        val config = HeadLogin.config(head.configDir)
        val credentials = HeadLogin.credentials(head.configDir)
        SecureFile.writeAtomic0600(credentials, bytes)
        val recorded = Cancellables.runCatchingCancellable { HeadLogin.writeAccount(head.configDir, config, account) }
        return recorded.fold(
            onSuccess = {
                store.select(label)
                ClaudeLoginResult.Done(Said.switched(head, savedAs, label, account.shown))
            },
            onFailure = { failure ->
                if (live is Live.Held) {
                    SecureFile.writeAtomic0600(credentials, live.bytes)
                } else {
                    Files.delete(credentials)
                }
                ClaudeLoginResult.Refused(Refusals.notRecorded(head, label, SafeFailureText.render(failure)))
            },
        )
    }
}
