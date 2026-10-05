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

/** Secret-free facts for one command. Window figures are provider observations, not per-account dollar spend. */
public data class ClaudeLoginPlaceView(
    val id: ClaudeLoginPlaceId,
    val head: String,
    val credentialPath: String,
    val credentialPresent: Boolean,
    val account: ClaudeAccountIdentity?,
    val quota: QuotaSnapshot?,
    val standing: ClaudeLoginStanding,
    val refusal: String? = null,
    val management: ClaudeLoginManagement? = null,
)

/** The app owns native files and login processes; the feature owns HTTP presentation and validation. */
public interface ClaudeLoginPlaces {
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

    /** The place whose live credential carried [head]'s newest request that matched a place since the daemon
     *  started, or null before any did. Both commands send through one head, so this, not the command's own
     *  place, is the login whose windows that head is spending. */
    public fun carrying(head: String): ClaudeLoginPlaceId? = null

    /** The place whose live credential carried [session]'s own newest request on [head] that matched a place, or
     *  null before one did. Every session on a head forwards its own login, so this is never the head's answer. */
    public fun carrying(head: String, session: String): ClaudeLoginPlaceId? = null
}

/** Read after composition assigns the native login owner, never capture an unwired null at server construction. */
public fun interface ClaudeLoginPlacesSource {
    public operator fun invoke(): ClaudeLoginPlaces?
}
