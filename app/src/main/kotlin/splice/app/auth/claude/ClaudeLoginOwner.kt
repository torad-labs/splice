// NEW: a command's own Claude Code login places, read for Accounts and edited in place. Signing in on a place's card
// adds an account to the place's command ([ClaudeAccountSignIn]) and never writes the place (Marcos, Oct 27, 2026:
// "SPlice console login ADDs an account, it doesn't replace it"), so a sign-in neither waits for the command's sessions
// nor holds its launches. Only an edit of a place does.
package splice.app.auth.claude

import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.claude.ClaudeCarrying
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginPlaces
import splice.accounts.signin.AccountMutation
import splice.accounts.signin.LoginFailure
import splice.accounts.signin.LoginState
import splice.accounts.signin.LoginStatus
import splice.client.ClaudeLogins
import splice.launch.recipe.LaunchLoginGuard
import java.nio.file.Path
import java.util.UUID

internal class ClaudeLoginOwner(
    private val locations: List<ClaudeLoginLocation>,
    private val reads: ClaudeLoginRead,
    private val signIn: ClaudeAccountSignIn,
    private val changes: ClaudePoolChanges? = null,
    private val carried: ClaudeCarryingPlaces = ClaudeCarryingPlaces(locations, reads),
) : ClaudeLoginPlaces, LaunchLoginGuard, ClaudeCarrying by carried {
    private val lock = Any()
    private val logins = locations.associate { it.id to ClaudeLogins(it.storeDir) }
    private val mutating = mutableSetOf<ClaudeLoginPlaceId>()
    private val edits = ClaudeLoginEdits(reads)
    private val dirs = ConfigDirs()

    private val names = ClaudeLoginNames { place, key -> logins[place]?.labelForCredential(key) }

    override fun places(): List<ClaudeLoginPlaceView> = reads.places(locations, names)

    internal fun accountForCredential(key: String): ClaudeAccountIdentity? = reads.accountForCredential(locations, key)

    /** The digest of a credential [head] just sent for [session], never the token: it decides which place carries
     *  that head, and which carries that session. */
    internal fun sent(head: String, session: String?, key: String) = carried.sent(head, session, key)

    override fun poll(id: String): LoginStatus? = signIn.poll(id)

    /** A launch waits only while one of its command's own logins is being edited. A sign-in writes no login a session
     *  reads, so it holds no launch. */
    override fun refusal(configDir: Path): String? = synchronized(lock) {
        "this native login is being edited; launch after it finishes".takeIf {
            locations.any { it.id in mutating && dirs.same(it.target.head.configDir, configDir) }
        }
    }

    /** Sign in and Sign in again on [place]'s card: one more account for the place's own command, under [label] or the
     *  next free one, exactly as + Add account. The place's login, and the account its sessions run on, stay as they
     *  are. The status names the place's OWN head, so the console draws it on that command's card. */
    override suspend fun login(place: ClaudeLoginPlaceId, label: String?): LoginStatus {
        val head = locations.firstOrNull { it.id == place }?.target?.head?.key
            ?: return LoginStatus(UUID.randomUUID().toString(), place.command, LoginState.FAILED)
                .failed(LoginFailure.CONFIG, "this command's Claude Code login is not configured on this machine")
        return signIn.start(head, label)
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
                edits.remove(location, store)
            } else {
                edits.relabel(location, store, label)
            }
        } finally {
            changes?.publish(location.target.head.key)
            synchronized(lock) { mutating -= place }
        }
    }

    override suspend fun submit(id: String, code: String): Boolean = signIn.submit(id, code)
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
