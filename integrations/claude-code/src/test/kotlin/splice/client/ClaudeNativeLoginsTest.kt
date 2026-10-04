// NEW: browser authentication preserves outgoing bytes and never files a different account under a bound label.
package splice.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ClaudeNativeLoginsTest {
    @TempDir
    lateinit var home: Path

    private fun target(name: String, native: Boolean): ClaudeLoginTarget {
        val config = Files.createDirectories(home.resolve(name))
        return ClaudeLoginTarget(
            ClaudeHead("claude-splice", config),
            if (native) home.resolve(".claude.json") else config.resolve(".claude.json"),
        )
    }

    private fun live(target: ClaudeLoginTarget, account: String, bytes: String) {
        Files.writeString(target.head.configDir.resolve(".credentials.json"), bytes)
        val record = """{"oauthAccount":{"accountUuid":"$account","emailAddress":"$account@test"}}"""
        Files.writeString(target.accountFile, record)
    }

    private fun ready(value: ClaudeNativeLoginPreparation): ClaudeNativeLoginPreparation.Ready =
        value as? ClaudeNativeLoginPreparation.Ready ?: error("native preparation must be ready")

    @Test
    fun `default Claude uses the home account file and preserves both outgoing and incoming native bytes`() {
        val target = target(".claude", native = true)
        live(target, "old-account", "old rotated bytes")
        val wrongAccount = target.head.configDir.resolve(".claude.json")
        Files.writeString(wrongAccount, """{"oauthAccount":{"accountUuid":"wrong-account"}}""")
        val stored = home.resolve("default-store")
        val logins = ClaudeLogins(stored)
        val prepared = logins.prepareNative(target, null, HeadSessions.Read(emptyList()))
        assertTrue(prepared is ClaudeNativeLoginPreparation.Ready)
        assertEquals("old rotated bytes", Files.readString(stored.resolve("account-old-account.credentials.json")))
        assertEquals("old rotated bytes", Files.readString(target.head.configDir.resolve(".credentials.json")))
        live(target, "new-account", "new native bytes")
        val completed = logins.completeNative(target, ready(prepared), HeadSessions.Read(emptyList()))
        assertTrue(completed is ClaudeLoginResult.Done)
        assertEquals("new native bytes", Files.readString(stored.resolve("account-new-account.credentials.json")))
        assertTrue(Files.readString(wrongAccount).contains("wrong-account"))
    }

    @Test
    fun `live and unreadable session inventories refuse before any native save-back`() {
        val target = target("separate", native = false)
        live(target, "account", "live bytes")
        val stored = home.resolve("refused-store")
        val logins = ClaudeLogins(stored)
        for (sessions in listOf(HeadSessions.Read(listOf("live")), HeadSessions.Unreadable("unreadable"))) {
            assertTrue(logins.prepareNative(target, "next", sessions) is ClaudeNativeLoginPreparation.Refused)
            assertEquals("live bytes", Files.readString(target.head.configDir.resolve(".credentials.json")))
            assertFalse(Files.exists(stored))
        }
    }

    @Test
    fun `a requested stored label never receives a fresh login of another account`() {
        val target = target("separate", native = false)
        live(target, "first-account", "first bytes")
        val stored = home.resolve("bound-store")
        val logins = ClaudeLogins(stored)
        assertTrue(logins.login(target.head, "bound", HeadSessions.Read(emptyList())) is ClaudeLoginResult.Done)
        val prepared = logins.prepareNative(target, "bound", HeadSessions.Read(emptyList()))
        assertTrue(prepared is ClaudeNativeLoginPreparation.Ready)
        live(target, "other-account", "other bytes")
        val result = logins.completeNative(target, ready(prepared), HeadSessions.Read(emptyList()))
        assertTrue(result is ClaudeLoginResult.Refused)
        assertEquals("first bytes", Files.readString(stored.resolve("bound.credentials.json")))
        assertEquals("other bytes", Files.readString(target.head.configDir.resolve(".credentials.json")))
        assertEquals(null, logins.selected())
    }

    @Test
    fun `a prepared login cannot complete in another destination or store`() {
        val first = target(".claude", native = true)
        val second = target("separate", native = false)
        live(first, "first-account", "first bytes")
        live(second, "second-account", "second bytes")
        val firstLogins = ClaudeLogins(home.resolve("first-store"))
        val secondLogins = ClaudeLogins(home.resolve("second-store"))
        val prepared = ready(firstLogins.prepareNative(first, "first", HeadSessions.Read(emptyList())))
        val wrongTarget = firstLogins.completeNative(second, prepared, HeadSessions.Read(emptyList()))
        val wrongStore = secondLogins.completeNative(first, prepared, HeadSessions.Read(emptyList()))
        assertTrue(wrongTarget is ClaudeLoginResult.Refused)
        assertTrue(wrongStore is ClaudeLoginResult.Refused)
        assertEquals(null, firstLogins.selected())
        assertEquals(null, secondLogins.selected())
        assertEquals("first bytes", Files.readString(first.head.configDir.resolve(".credentials.json")))
        assertEquals("second bytes", Files.readString(second.head.configDir.resolve(".credentials.json")))
    }

    @Test
    fun `a new human label replaces only this preparations automatic save-back label`() {
        val target = target("separate", native = false)
        live(target, "same-account", "outgoing bytes")
        val stored = home.resolve("human-store")
        val logins = ClaudeLogins(stored)
        val prepared = ready(logins.prepareNative(target, "human", HeadSessions.Read(emptyList())))
        live(target, "same-account", "fresh bytes")
        assertTrue(logins.completeNative(target, prepared, HeadSessions.Read(emptyList())) is ClaudeLoginResult.Done)
        assertEquals("human", logins.selected())
        assertEquals("fresh bytes", Files.readString(stored.resolve("human.credentials.json")))
        assertFalse(Files.exists(stored.resolve("account-same-account.credentials.json")))
        val again = ready(logins.prepareNative(target, "different", HeadSessions.Read(emptyList())))
        assertTrue(logins.completeNative(target, again, HeadSessions.Read(emptyList())) is ClaudeLoginResult.Refused)
        assertEquals("fresh bytes", Files.readString(stored.resolve("human.credentials.json")))
        assertFalse(Files.exists(stored.resolve("different.credentials.json")))
    }

    @Test
    fun `save-back failure refuses before replacing live credentials`() {
        val target = target("separate", native = false)
        live(target, "account", "live bytes")
        val unavailable = home.resolve("not-a-directory")
        Files.writeString(unavailable, "occupied")
        val logins = ClaudeLogins(unavailable)
        val prepared = logins.prepareNative(target, null, HeadSessions.Read(emptyList()))
        assertTrue(prepared is ClaudeNativeLoginPreparation.Refused)
        assertEquals("live bytes", Files.readString(target.head.configDir.resolve(".credentials.json")))
    }

    @Test
    fun `independent command stores cannot demote each other's selected login`() {
        val first = target(".claude", native = true)
        val second = target("separate", native = false)
        live(first, "default-account", "default bytes")
        live(second, "separate-account", "separate bytes")
        val firstLogins = ClaudeLogins(home.resolve("first-store"))
        val secondLogins = ClaudeLogins(home.resolve("second-store"))
        val preparedFirst = firstLogins.prepareNative(first, "default", HeadSessions.Read(emptyList()))
        val preparedSecond = secondLogins.prepareNative(second, "separate", HeadSessions.Read(emptyList()))
        assertTrue(preparedFirst is ClaudeNativeLoginPreparation.Ready)
        assertTrue(preparedSecond is ClaudeNativeLoginPreparation.Ready)
        val firstDone = firstLogins.completeNative(first, ready(preparedFirst), HeadSessions.Read(emptyList()))
        val secondDone = secondLogins.completeNative(second, ready(preparedSecond), HeadSessions.Read(emptyList()))
        assertTrue(firstDone is ClaudeLoginResult.Done)
        assertTrue(secondDone is ClaudeLoginResult.Done)
        assertEquals("default", firstLogins.selected())
        assertEquals("separate", secondLogins.selected())
        val preparingAgain = secondLogins.prepareNative(second, null, HeadSessions.Read(emptyList()))
        assertTrue(preparingAgain is ClaudeNativeLoginPreparation.Ready)
        assertEquals("default", firstLogins.selected())
        assertEquals(null, secondLogins.selected())
        assertEquals("default bytes", Files.readString(first.head.configDir.resolve(".credentials.json")))
        assertEquals("separate bytes", Files.readString(second.head.configDir.resolve(".credentials.json")))
    }
}
