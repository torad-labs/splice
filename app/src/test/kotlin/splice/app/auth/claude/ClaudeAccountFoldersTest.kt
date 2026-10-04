// NEW: the Claude half of failover within one provider (operator ruling, Oct 3, 11:44 PM CT: "each provider gets a
// head, each head can have multiple subscriptions"; Oct 4, 12:00 AM CT: heads are templates, and the console probes
// each account's usage). An account added beyond the caller's own Claude Code sign-in lives in a splice-owned folder
// under its head, in Claude Code's own file format, so splice is that credential's only user and only refresher.
// The sign-in runs in a PENDING folder: an account already in this head's pool is refused there and nothing existing
// is written, which is what keeps one place's sign-in out of another's folder.
package splice.app.auth.claude

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

private const val HEAD = "claude-splice"
private const val OTHER_HEAD = "claude"

class ClaudeAccountFoldersTest {
    @TempDir
    lateinit var state: Path

    private fun folders(): ClaudeAccountFolders = ClaudeAccountFolders(state)

    private fun signIn(head: String, label: String, uuid: String, email: String?, token: String = "synthetic-$label") {
        val pending = folders().pending(head, label)
        Files.createDirectories(pending.directory)
        write(pending.directory, uuid, email, token)
        assertTrue(folders().land(pending) is ClaudeAccountLanding.Added, "$label landed")
    }

    private fun write(directory: Path, uuid: String, email: String?, token: String, expiresAt: Long = FAR_FUTURE) {
        Files.writeString(
            directory.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"$token","refreshToken":"synthetic-refresh","expiresAt":$expiresAt,""" +
                """"scopes":["user:inference","user:profile"]}}""",
        )
        val shown = email?.let { ""","emailAddress":"$it"""" } ?: ""
        Files.writeString(directory.resolve(".claude.json"), """{"oauthAccount":{"accountUuid":"$uuid"$shown}}""")
    }

    @Test
    fun `an added account is one owner-only folder under its head, listed oldest first`() {
        signIn(HEAD, "work", "uuid-work", "work@synthetic")
        signIn(HEAD, "home", "uuid-home", "home@synthetic")

        val accounts = folders().accounts(HEAD)
        assertEquals(listOf("work", "home"), accounts.map { it.label }, "added order, oldest first")
        assertEquals(listOf("uuid-work", "uuid-home"), accounts.map { it.identity?.uuid })
        assertEquals("work@synthetic", accounts.first().identity?.email)
        assertEquals("synthetic-work", folders().token(HEAD, "work"))
        val owner = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        assertEquals(owner, Files.getPosixFilePermissions(accounts.first().credentials))
    }

    @Test
    fun `a head reads only its own accounts`() {
        signIn(HEAD, "work", "uuid-work", null)
        signIn(OTHER_HEAD, "other", "uuid-other", null)

        assertEquals(listOf("work"), folders().accounts(HEAD).map { it.label })
        assertEquals(listOf("other"), folders().accounts(OTHER_HEAD).map { it.label })
        assertNull(folders().token(HEAD, "other"), "another head's account is not this head's")
    }

    @Test
    fun `an account already in this head's pool is refused, and no existing folder is written`() {
        signIn(HEAD, "work", "uuid-work", "work@synthetic")
        val before = Files.readString(folders().accounts(HEAD).single().credentials)

        val pending = folders().pending(HEAD, "again")
        Files.createDirectories(pending.directory)
        write(pending.directory, "uuid-work", "work@synthetic", "synthetic-again")
        val landing = folders().land(pending)

        assertTrue(landing is ClaudeAccountLanding.AlreadyAdded, "the same account twice is refused")
        assertEquals("work", (landing as ClaudeAccountLanding.AlreadyAdded).label)
        assertEquals(listOf("work"), folders().accounts(HEAD).map { it.label })
        assertEquals(before, Files.readString(folders().accounts(HEAD).single().credentials), "untouched")
        assertFalse(Files.exists(pending.directory), "the pending folder is gone")
    }

    @Test
    fun `the same account may be added to another head`() {
        signIn(HEAD, "work", "uuid-work", null)

        signIn(OTHER_HEAD, "work", "uuid-work", null)

        assertEquals("uuid-work", folders().accounts(OTHER_HEAD).single().identity?.uuid)
    }

    @Test
    fun `a sign-in that records no account is refused and leaves nothing behind`() {
        val pending = folders().pending(HEAD, "work")
        Files.createDirectories(pending.directory)
        Files.writeString(pending.directory.resolve(".credentials.json"), """{"claudeAiOauth":{"accessToken":"t"}}""")

        assertTrue(folders().land(pending) is ClaudeAccountLanding.Unreadable)
        assertEquals(emptyList<String>(), folders().accounts(HEAD).map { it.label })
        assertFalse(Files.exists(pending.directory))
    }

    @Test
    fun `a label outside the shape, or the caller's own sign-in label, is refused before any folder is made`() {
        assertThrows(IllegalArgumentException::class.java) { folders().pending(HEAD, "../escape") }
        assertThrows(IllegalArgumentException::class.java) { folders().pending(HEAD, OWN_SIGN_IN_LABEL) }
    }

    @Test
    fun `signing in again under an existing label replaces that account's own folder and keeps one entry`() {
        signIn(HEAD, "work", "uuid-work", "work@synthetic")

        signIn(HEAD, "work", "uuid-work", "work@synthetic", token = "synthetic-renewed")

        assertEquals(listOf("work"), folders().accounts(HEAD).map { it.label })
        assertEquals("synthetic-renewed", folders().token(HEAD, "work"))
    }

    @Test
    fun `a removed account is gone, and removing twice says so`() {
        signIn(HEAD, "work", "uuid-work", null)

        assertEquals(ClaudeAccountRemoval.Removed, folders().remove(HEAD, "work"))

        assertEquals(ClaudeAccountRemoval.NotFound, folders().remove(HEAD, "work"))
        assertEquals(emptyList<String>(), folders().accounts(HEAD).map { it.label })
        assertNull(folders().token(HEAD, "work"), "its credential cannot be read again")
    }

    @Test
    fun `a remove takes only the label it names, and never the caller's own sign-in`() {
        signIn(HEAD, "work", "uuid-work", null)
        signIn(HEAD, "personal", "uuid-personal", null)
        signIn(OTHER_HEAD, "work", "uuid-work", null)

        assertEquals(
            ClaudeAccountRemoval.OwnSignIn,
            folders().remove(HEAD, OWN_SIGN_IN_LABEL),
            "that label is the person's real Claude Code login, which splice has never held",
        )
        assertEquals(ClaudeAccountRemoval.NotFound, folders().remove(HEAD, "../escape"))
        assertEquals(ClaudeAccountRemoval.Removed, folders().remove(HEAD, "work"))

        assertEquals(listOf("personal"), folders().accounts(HEAD).map { it.label }, "the sibling label is untouched")
        assertEquals(listOf("work"), folders().accounts(OTHER_HEAD).map { it.label }, "and so is the other command's")
    }

    @Test
    fun `an unreadable folder is left out and named, never guessed at`() {
        signIn(HEAD, "work", "uuid-work", null)
        val broken = state.resolve("claude-accounts").resolve(HEAD).resolve("broken")
        Files.createDirectories(broken)
        Files.writeString(broken.resolve(".credentials.json"), "{not json")

        val accounts = folders().accounts(HEAD)
        assertEquals(listOf("work", "broken"), accounts.map { it.label }, "the broken label keeps its own row, last")
        assertNull(accounts.last().identity, "with no identity until it is signed in again")
        assertEquals("this sign-in is unreadable; sign in again", accounts.last().refusal)
        assertNull(accounts.first().refusal, "and the working login carries no refusal of someone else's")
    }

    @Test
    fun `an account never prints its token`() {
        signIn(HEAD, "work", "uuid-work", null, token = "synthetic-secret")

        assertFalse(folders().accounts(HEAD).single().toString().contains("synthetic-secret"))
    }
}

private const val FAR_FUTURE = 4_000_000_000_000L
