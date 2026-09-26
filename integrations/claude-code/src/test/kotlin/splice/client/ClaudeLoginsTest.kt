// NEW: V4-129 (FEATURES.md 4.5), rebuilt for V4-276 — `splice login <claude-head> --label` saves,
// refreshes and switches the head's login under four invariants: save-back before any switch, no
// change while a session of the head runs, identity from .claude.json's oauthAccount (never a token
// byte), and fail closed. Splice never parses the credential bytes; every assertion here is about
// which bytes land where. The head below is Claude Code's config dir: `signIn` is its own /login,
// `rotate` its in-place token refresh.
package splice.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ClaudeLoginsTest {

    @TempDir
    lateinit var home: Path

    private val logins by lazy { ClaudeLogins(storeDir = home.resolve("store")) }
    private val head by lazy { ClaudeHead("claude-splice", home.resolve("head").createDirectories()) }
    private val idle = HeadSessions.Read(emptyList())

    private fun login(label: String, sessions: HeadSessions = idle, discard: Boolean = false) =
        logins.login(head, label, sessions, discard)

    /** Claude Code's own /login: the credential bytes, and the account it records in .claude.json. */
    private fun signIn(bytes: String, uuid: String, email: String) {
        head.configDir.resolve(".credentials.json").writeText(bytes)
        head.configDir.resolve(".claude.json").writeText(
            """{"numStartups":3,"oauthAccount":{"accountUuid":"$uuid","emailAddress":"$email","billingType":"x"}}""",
        )
    }

    /** Claude Code refreshing its token in place: new bytes, the same account. */
    private fun rotate(bytes: String) = head.configDir.resolve(".credentials.json").writeText(bytes)

    private fun live(): String = head.configDir.resolve(".credentials.json").readText()
    private fun liveGone(): Boolean = !head.configDir.resolve(".credentials.json").exists()
    private fun clientConfig(): String = head.configDir.resolve(".claude.json").readText()
    private fun stored(label: String): String = home.resolve("store/$label.credentials.json").readText()

    private fun done(result: ClaudeLoginResult): String {
        assertTrue(result is ClaudeLoginResult.Done, "$result")
        return (result as ClaudeLoginResult.Done).said
    }

    private fun refused(result: ClaudeLoginResult): String {
        assertTrue(result is ClaudeLoginResult.Refused, "$result")
        return (result as ClaudeLoginResult.Refused).reason
    }

    /** 'work' (account A) and 'home' (account B) saved, the head left on 'home'. */
    private fun twoLabels() {
        signIn("A-gen1", "uuid-a", "a@example.com")
        done(login("work"))
        done(login("home")) // a new label: 'work' is saved and the head signed out for a fresh /login
        assertTrue(liveGone())
        signIn("B-gen1", "uuid-b", "b@example.com")
        done(login("home"))
        assertEquals("home", logins.selected())
    }

    @Test
    fun `an untouched store has no labels and no selection`() {
        assertEquals(emptyList<String>(), logins.labels())
        assertEquals(null, logins.selected())
    }

    @Test
    fun `the first save stores the live bytes verbatim, records the account without a token byte, and selects it`() {
        signIn("A-gen1", "uuid-a", "a@example.com")

        val said = done(login("work"))

        assertTrue(said.contains("a@example.com"), said)
        assertEquals("A-gen1", stored("work"))
        assertEquals("A-gen1", live(), "saving never touches the live file")
        assertEquals("work", logins.selected())
        val record = home.resolve("store/work.account.json").readText()
        assertTrue(record.contains("uuid-a") && !record.contains("A-gen1"), record)
        if (Files.getFileStore(home).supportsFileAttributeView("posix")) {
            for (file in listOf("work.credentials.json", "work.account.json", "selected")) {
                val mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("store/$file")))
                assertEquals("rw-------", mode, file)
            }
        }
    }

    // Invariant 1. Claude Code's refresh tokens are single-use and rotate: the copy stored at the first
    // save is stale after the first refresh, and putting it back is how 2026-09-25's revocations began.
    @Test
    fun `a switch away and back after a token rotation brings back the newest login - V4-276 inv1`() {
        twoLabels()
        assertTrue(done(login("work")).contains("saved claude-splice's login as 'home'"))
        assertEquals("A-gen1", live())
        assertTrue(clientConfig().contains("uuid-a"), "the head's .claude.json names the account it now holds")
        rotate("A-gen2")

        done(login("home"))
        assertEquals("A-gen2", stored("work"), "the save-back filed the rotated login under its label")
        assertEquals("B-gen1", live())
        done(login("work"))

        assertEquals("A-gen2", live(), "the switch back restores the account's newest login")
    }

    // Invariant 2. Every session of the head shares one credential file and refreshes it itself.
    @Test
    fun `a switch while a session of the head is running is refused naming it, and changes nothing - V4-276 inv2`() {
        twoLabels()

        val reason = refused(login("work", HeadSessions.Read(listOf("'fix the parser', pid 4242"))))

        assertTrue(reason.contains("'fix the parser', pid 4242"), reason)
        assertEquals("B-gen1", live())
        assertEquals("A-gen1", stored("work"))
        assertEquals("home", logins.selected())
        val unknown = refused(login("work", HeadSessions.Unreadable("permission denied")))
        assertTrue(unknown.contains("cannot tell whether a session of claude-splice is running"), unknown)
        assertEquals("B-gen1", live(), "a registry splice cannot read is refused like a running session")
    }

    // Invariant 3. A /login inside the head replaces the selected label's login with another account's.
    @Test
    fun `a login to another account inside the head is never filed under the selected label - V4-276 inv3`() {
        signIn("A-gen1", "uuid-a", "a@example.com")
        done(login("work"))
        signIn("C-gen1", "uuid-c", "c@example.com")

        val reason = refused(login("work"))

        assertTrue(reason.contains("c@example.com") && reason.contains("a@example.com"), "names both accounts: $reason")
        assertTrue(reason.contains("--label <new name>") && reason.contains("--discard"), "names the remedy: $reason")
        assertEquals("A-gen1", stored("work"))
        refused(login("work"))
        assertEquals("A-gen1", stored("work"), "asking again files nothing either: 'work' still knows its account")
    }

    // Invariant 3's remedy, and why 'work' is not put back after it: its login left the head without a
    // save-back, so its copy may be older than its last refresh.
    @Test
    fun `the remedy saves the other account under a new label, and the stale label waits for a fresh login`() {
        signIn("A-gen1", "uuid-a", "a@example.com")
        done(login("work"))
        signIn("C-gen1", "uuid-c", "c@example.com")

        done(login("other"))
        assertEquals("C-gen1", stored("other"))
        val back = done(login("work"))

        assertTrue(liveGone(), "the stale copy never reaches the head: the head is signed out instead")
        assertTrue(back.contains("never put back") && back.contains("a@example.com"), back)
        assertEquals("C-gen1", stored("other"), "the login it replaced was saved first")
        signIn("A-gen9", "uuid-a", "a@example.com")
        done(login("work"))
        assertEquals("A-gen9", stored("work"), "a fresh login of that account re-saves it")
        done(login("other"))
        assertEquals("C-gen1", live())
        done(login("work"))
        assertEquals("A-gen9", live())
    }

    // Invariant 4. The save-back is the only thing standing between the switch and a lost login.
    @Test
    fun `a switch whose save-back fails changes nothing live - V4-276 inv4`() {
        twoLabels()
        val storedHome = home.resolve("store/home.credentials.json")
        storedHome.deleteExisting()
        storedHome.createDirectories().resolve("occupied").writeText("x") // the save-back cannot land
        val before = clientConfig()

        val reason = refused(login("work"))

        assertTrue(reason.contains("stopped"), reason)
        assertEquals("B-gen1", live())
        assertEquals(before, clientConfig())
    }

    @Test
    fun `a live login whose account Claude Code has not recorded is refused, and nothing changes`() {
        twoLabels()
        head.configDir.resolve(".claude.json").writeText("""{"numStartups":3}""")

        val reason = refused(login("work"))

        assertTrue(reason.contains("cannot tell whose login"), reason)
        assertEquals("B-gen1", live())
        head.configDir.resolve(".claude.json").writeText("{not json")
        refused(login("work"))
        assertEquals("B-gen1", live(), "an unreadable .claude.json is refused too")
    }

    // splice-lead 2026-09-26: a copy stored before V4-276 has no record, so it is stale by construction,
    // like this box's max.credentials.json from 2026-09-20.
    @Test
    fun `a legacy copy is never put back, and the refusal names the remedy - V4-276`() {
        home.resolve("store").createDirectories().resolve("max.credentials.json").writeText("max-2026-09-20")

        val reason = refused(login("max"))

        assertTrue(liveGone(), "a legacy copy never reaches the head")
        assertTrue(reason.contains("never put back") && reason.contains("Re-save it from a live login"), reason)
        signIn("A-gen1", "uuid-a", "a@example.com")
        done(login("work"))
        done(login("max"))
        assertTrue(liveGone(), "from a saved head too: the head is signed out, the old copy stays unused")
        assertEquals("max-2026-09-20", stored("max"))
        signIn("M-gen1", "uuid-m", "m@example.com")
        done(login("max"))
        assertEquals("M-gen1", stored("max"), "a live login re-saves it")
    }

    @Test
    fun `a switch refuses to drop a login saved under no label unless --discard says so`() {
        twoLabels()
        signIn("D-gen1", "uuid-d", "d@example.com") // the head's 'home' left without a save-back

        val reason = refused(login("work"))
        assertTrue(reason.contains("d@example.com") && reason.contains("--discard"), reason)
        assertEquals("D-gen1", live())

        done(login("work", discard = true))
        assertEquals("A-gen1", live())
    }

    @Test
    fun `a new label while the head is signed out has nothing to save, and says how to get one`() {
        val reason = refused(login("work"))
        assertTrue(reason.contains("holds no login to save as 'work'") && reason.contains("/login"), reason)
        assertEquals(emptyList<String>(), logins.labels())
    }

    @Test
    fun `a malformed label is refused before anything is read or written`() {
        signIn("A-gen1", "uuid-a", "a@example.com")
        refused(login("../etc/passwd"))
        assertEquals(emptyList<String>(), logins.labels())
    }

    @Test
    fun `removing the selected login clears the selection, and an unknown label is refused`() {
        signIn("A-gen1", "uuid-a", "a@example.com")
        done(login("work"))

        assertEquals(ClaudeLoginResult.Ok, logins.remove("work"))

        assertEquals(null, logins.selected())
        assertEquals(emptyList<String>(), logins.labels())
        assertFalse(home.resolve("store/work.account.json").exists())
        refused(logins.remove("ghost"))
    }
}
