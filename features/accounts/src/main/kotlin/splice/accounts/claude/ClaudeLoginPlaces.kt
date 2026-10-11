// NEW: command-local Claude login identities and observations, never credential material.
package splice.accounts.claude

import splice.accounts.signin.AccountMutation
import splice.accounts.signin.LoginStatus
import splice.core.usage.QuotaSnapshot

/** Each command reads its own native login even when both send requests through the same head. */
public enum class ClaudeLoginPlaceId(public val wire: String, public val command: String) {
    NATIVE("claude", "claude"),
    SPLICE("claude-splice", "claude-splice"),
}

/** Claude Code's account record supplies identity; neither a head key nor a token is an account identity. */
public data class ClaudeAccountIdentity(val uuid: String, val email: String?)

/** A proved credential's own refusal. Missing evidence remains unknown rather than an invented clean standing. */
public data class ClaudeLoginStanding(val held: Boolean?, val untilEpochSeconds: Long?)

/** One display name and the explicitly addressed edits this live login supports. */
public data class ClaudeLoginManagement(val displayName: String, val canRemove: Boolean, val canRename: Boolean)

/** The credential file one place reads: where it lives, whether a usable token is there, and the credential's own
 *  durable refusal, if any. */
public data class ClaudeLoginCredential(
    val path: String,
    val present: Boolean,
    val refusal: String? = null,
)

/** Who a place proved it is: the verified account, and the profile read behind it. */
public data class ClaudeLoginIdentity(
    val account: ClaudeAccountIdentity?,
    /** Verified identity, an outstanding/transient profile read, or this credential's durable refusal. */
    val profileState: ClaudeProfileState =
        if (account != null) ClaudeProfileState.VERIFIED else ClaudeProfileState.PENDING,
)

/** Secret-free facts for one command. Window figures are provider observations, not per-account dollar spend. */
public data class ClaudeLoginPlaceView(
    val id: ClaudeLoginPlaceId,
    val head: String,
    val credential: ClaudeLoginCredential,
    val identity: ClaudeLoginIdentity,
    val quota: QuotaSnapshot?,
    val standing: ClaudeLoginStanding,
    val management: ClaudeLoginManagement? = null,
)

/** Which place or account carried a head's and a session's newest request: the read-only part of the roster,
 *  split from the login actions so the app can implement it apart from them. */
public interface ClaudeCarrying {
    /** The place whose live credential carried [head]'s newest request that matched a place since the daemon
     *  started, or null before any did. Both commands send through one head, so this, not the command's own
     *  place, is the login whose windows that head is spending. */
    public fun carrying(head: String): ClaudeLoginPlaceId? = null

    /** The place whose live credential carried [session]'s own newest request on [head] that matched a place, or
     *  null before one did. Every session on a head forwards its own login, so this is never the head's answer. */
    public fun carrying(head: String, session: String): ClaudeLoginPlaceId? = null

    /** The stable label proved by [session]'s newest sent credential on [head], or null when no login matches.
     *  Native sends name their place; added-account sends name their pool member, never a selection not used. */
    public fun carryingAccount(head: String, session: String): String? = carrying(head, session)?.wire

    /** Whether this session sent since boot, including an unmatched send that must defeat older history. */
    public fun hasCarryingProof(head: String, session: String): Boolean = carryingAccount(head, session) != null

    /** Resolve a persisted proved identity to its current roster label without changing its Requests spelling. */
    public fun accountLabel(head: String, account: String): String = account
}

/** The app owns native files and login processes; the feature owns HTTP presentation and validation. */
public interface ClaudeLoginPlaces : ClaudeCarrying {
    public fun places(): List<ClaudeLoginPlaceView>
    public suspend fun login(place: ClaudeLoginPlaceId, label: String?): LoginStatus
    public suspend fun refresh(place: ClaudeLoginPlaceId): ClaudeLoginPlaceView
    public fun poll(id: String): LoginStatus?

    /** Submit the native CLI's documented pasted-code fallback without logging or retaining the code. */
    public suspend fun submit(id: String, code: String): Boolean

    /** A native-place target can never fall through to a pooled login with the same label. */
    public suspend fun remove(place: ClaudeLoginPlaceId): AccountMutation =
        AccountMutation.Refused("this native login has no stored removal target")

    /** The native owner, not the pooled-login store, owns this place's display name. */
    public suspend fun relabel(place: ClaudeLoginPlaceId, label: String): AccountMutation =
        AccountMutation.Refused("this native login has no stored rename target")
}

/** Read after composition assigns the native login owner, never capture an unwired null at server construction. */
public fun interface ClaudeLoginPlacesSource {
    public operator fun invoke(): ClaudeLoginPlaces?
}
