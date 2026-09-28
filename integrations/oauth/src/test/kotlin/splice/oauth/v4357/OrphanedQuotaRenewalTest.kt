package splice.oauth.v4357

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthKind
import splice.oauth.LoginIo
import splice.oauth.OAuthAccountFiles
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class OrphanedQuotaRenewalTest {
    private val kind = AuthKind.ChatgptOAuth
    private val label = "work"
    private val token = """{"tokens":{"access_token":"synthetic-token","account_id":"synthetic-account"}}"""

    private fun setup(home: Path): Pair<Path, Path> {
        val primary = home.resolve("primary.json")
        Files.writeString(primary, "{}")
        val pool = Files.createDirectories(OAuthAccountFiles().poolDir(kind, primary))
        return primary to pool
    }

    @Test
    fun `labeled renewal sets orphaned usage aside before its credential lands`(@TempDir home: Path) {
        val (primary, pool) = setup(home)
        val quota = pool.resolve("$label-quota.json")
        Files.writeString(quota, "old-account-usage")
        val account = OAuthAccountFiles().loginAccount(kind, primary, label)
        val printed = StringBuilder()

        val ok = LoginIo(TerminalOutput { printed.appendLine(it) }).persistIfSignedIn(primary, token, account)

        assertTrue(ok, printed.toString())
        val archived = account.setAsideQuota()
        assertTrue(archived != null && Files.isRegularFile(archived, LinkOption.NOFOLLOW_LINKS))
        assertTrue(archived!!.fileName.toString().matches(Regex("work-quota\\.json\\.orphaned-\\d{8}-\\d{6}(?:-\\d+)?")))
        assertEquals("old-account-usage", Files.readString(archived))
        assertFalse(Files.exists(quota, LinkOption.NOFOLLOW_LINKS), "new account must never read the previous snapshot")
        val credential = Json.parseToJsonElement(Files.readString(pool.resolve("work.json"))).jsonObject
        assertEquals("work", credential["splice_account_label"]?.jsonPrimitive?.content)
        assertEquals("work", account.persistedLabel())
        Files.writeString(quota, "fresh-account-usage")
        assertEquals("old-account-usage", Files.readString(archived))
        assertEquals("fresh-account-usage", Files.readString(quota))
        Files.delete(pool.resolve("$label.json"))
        val second = OAuthAccountFiles().loginAccount(kind, primary, label)
        assertTrue(LoginIo(TerminalOutput {}).persistIfSignedIn(primary, token, second))
        val nextArchive = requireNotNull(second.setAsideQuota())
        assertTrue(nextArchive != archived)
        assertEquals("fresh-account-usage", Files.readString(nextArchive))
        assertEquals("old-account-usage", Files.readString(archived))
    }

    @Test
    fun `linked quota moves as a link and leaves its target untouched`(@TempDir home: Path) {
        val (primary, pool) = setup(home)
        val target = home.resolve("other-account-usage")
        Files.writeString(target, "other-account-window")
        val quota = pool.resolve("$label-quota.json")
        Files.createSymbolicLink(quota, target)
        val account = OAuthAccountFiles().loginAccount(kind, primary, label)
        val ok = LoginIo(TerminalOutput {}).persistIfSignedIn(primary, token, account)

        assertTrue(ok)
        assertTrue(Files.isSymbolicLink(account.setAsideQuota()))
        assertEquals(target, Files.readSymbolicLink(account.setAsideQuota()))
        assertEquals("other-account-window", Files.readString(target))
        assertFalse(Files.exists(quota, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a linked credential cannot claim retained usage and offers a free label`(@TempDir home: Path) {
        val (primary, pool) = setup(home)
        val target = home.resolve("someone-elses-credential")
        Files.writeString(target, "external credential")
        val linked = pool.resolve("$label.json")
        Files.createSymbolicLink(linked, target)
        val quota = pool.resolve("$label-quota.json")
        Files.writeString(quota, "other-account-window")
        val account = OAuthAccountFiles().loginAccount(kind, primary, label)
        val printed = StringBuilder()

        val ok = LoginIo(TerminalOutput { printed.appendLine(it) }).persistIfSignedIn(primary, token, account)

        assertFalse(ok)
        assertTrue(Files.isSymbolicLink(linked))
        assertEquals("external credential", Files.readString(target))
        assertEquals("other-account-window", Files.readString(quota))
        assertTrue(printed.contains("work-2"), printed.toString())
        assertTrue(account.refusal()?.contains("work-2") == true)
        assertEquals(null, account.setAsideQuota())
    }

    @Test
    fun `a label with an existing credential keeps its previous replacement behavior`(@TempDir home: Path) {
        val (primary, pool) = setup(home)
        Files.writeString(pool.resolve("$label.json"), "previous credential")
        Files.writeString(pool.resolve("$label-quota.json"), "current quota")
        val account = OAuthAccountFiles().loginAccount(kind, primary, label)

        assertTrue(LoginIo(TerminalOutput {}).persistIfSignedIn(primary, token, account))
        assertEquals(null, account.setAsideQuota())
        assertEquals("current quota", Files.readString(pool.resolve("$label-quota.json")))
    }
}
