// Moved from app/cli/auth/LoginCommandTest.kt with LoginMuse (LAYOUT-01): the Muse device spec, its
// login-end key mint, and the mint's never-fail-the-login contract.
package splice.oauth.muse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.oauth.LoginIo
import splice.oauth.kimi.LoginKimi
import splice.provider.muse.MuseMintAttempt
import splice.provider.muse.MuseMintMode
import splice.provider.muse.MuseSubscriptionKey
import java.nio.file.Files
import java.nio.file.Path

class LoginMuseTest {

    private val loginIo = LoginIo(TerminalOutput {})

    @Test
    fun `muse spec keeps access token and schema and mints after persist`(@TempDir tmp: Path) {
        val primary = tmp.resolve("muse.json")
        Files.writeString(primary, "{}")
        val granted = MuseMintAttempt.Granted(
            MuseSubscriptionKey(
                "minted-key-fake",
                buildJsonObject { put("user_email", JsonPrimitive("ops@example.invalid")) },
            ),
        )
        val muse = LoginMuse(TerminalOutput {}) { _, mode ->
            assertEquals(MuseMintMode.ONBOARD, mode)
            granted
        }
        val spec = muse.spec("claude-muse", primary)
        assertEquals("1031625952748946", spec.clientId)
        assertTrue(spec.identityHeaders.isEmpty())
        assertEquals("{}", spec.toAuthJson("{}"))
        val written = spec.toAuthJson(
            """{"access_token":"acct-token-fake","token_type":"Bearer","schema":"v1"}""",
        )
        assertTrue(written.contains("acct-token-fake"))
        assertTrue(written.contains("\"schema\""))
        assertTrue(loginIo.persistIfSignedIn(primary, written, spec.account))
        kotlinx.coroutines.runBlocking { spec.afterPersist(primary, spec.account) }
        val onDisk = Json.parseToJsonElement(Files.readString(primary)).jsonObject
        assertEquals("acct-token-fake", onDisk.getValue("access_token").jsonPrimitive.content)
        assertEquals("minted-key-fake", onDisk.getValue("api_key").jsonPrimitive.content)
    }

    @Test
    fun `muse failed mint leaves a valid-but-unminted credential`(@TempDir tmp: Path) {
        val path = tmp.resolve("muse-unminted.json")
        val spec = LoginMuse(TerminalOutput {}) { _, _ -> MuseMintAttempt.Denied("no-key") }.spec("claude-muse", path)
        assertTrue(loginIo.persistIfSignedIn(path, """{"access_token":"acct-token-fake"}""", spec.account))
        kotlinx.coroutines.runBlocking { spec.afterPersist(path, spec.account) }
        val unminted = Json.parseToJsonElement(Files.readString(path)).jsonObject
        assertEquals("acct-token-fake", unminted.getValue("access_token").jsonPrimitive.content)
        assertEquals(null, unminted["api_key"])
    }

    @Test
    fun `muse labels reserve like kimi`(@TempDir tmp: Path) {
        val primary = tmp.resolve("muse.json")
        Files.writeString(primary, "{}")
        val explicit = LoginMuse(TerminalOutput {}).spec("claude-muse", primary, "work")
        try {
            val automatic = LoginMuse(TerminalOutput {}).spec("claude-muse", primary, "auto")
            try {
                assertEquals("work", requireNotNull(explicit.account).resolvedLabel())
                assertEquals("muse-2", requireNotNull(automatic.account).resolvedLabel())
            } finally {
                automatic.account?.releaseReservation()
            }
        } finally {
            explicit.account?.releaseReservation()
        }
    }

    @Test
    fun `kimi and muse device forms are wire-identical aside from client id`(@TempDir tmp: Path) {
        val kimiPath = tmp.resolve("kimi.json")
        val musePath = tmp.resolve("muse.json")
        Files.writeString(kimiPath, "{}")
        Files.writeString(musePath, "{}")
        val kimi = LoginKimi().spec("kimi", kimiPath)
        val muse = LoginMuse(TerminalOutput {}).spec("claude-muse", musePath)
        try {
            val placeholder = "CLIENT"
            fun canon(form: String, id: String) = form.replace(id, placeholder)
            assertEquals(
                canon(kimi.deviceAuthForm(kimi.clientId), kimi.clientId),
                canon(muse.deviceAuthForm(muse.clientId), muse.clientId),
            )
            assertEquals(
                canon(kimi.tokenPollForm("DEV123", kimi.clientId), kimi.clientId),
                canon(muse.tokenPollForm("DEV123", muse.clientId), muse.clientId),
            )
            assertEquals(1800L, kimi.parseDeviceAuth("""{"user_code":"A","device_code":"B"}""").expiresInS)
            assertEquals(600L, muse.parseDeviceAuth("""{"user_code":"A","device_code":"B"}""").expiresInS)
        } finally {
            kimi.account?.releaseReservation()
            muse.account?.releaseReservation()
        }
    }

    @Test
    fun `muse login-end mint stays ONBOARD on relogin`(@TempDir tmp: Path) {
        val path = tmp.resolve("muse.json")
        Files.writeString(path, """{"access_token":"old","api_key":"held-key"}""")
        val modes = mutableListOf<MuseMintMode>()
        val spec = LoginMuse(TerminalOutput {}) { _, mode ->
            modes += mode
            MuseMintAttempt.Denied("held")
        }.spec("claude-muse", path)
        assertTrue(loginIo.persistIfSignedIn(path, """{"access_token":"acct-token-fake"}""", spec.account))
        kotlinx.coroutines.runBlocking { spec.afterPersist(path, spec.account) }
        assertEquals(listOf(MuseMintMode.ONBOARD), modes)
    }

    @Test
    fun `muse labeled pool mint writes the key beside the untouched primary`(@TempDir tmp: Path) {
        val primary = tmp.resolve("muse.json")
        Files.writeString(primary, """{"access_token":"primary-secret"}""")
        val granted = MuseMintAttempt.Granted(
            MuseSubscriptionKey("pool-key-fake", buildJsonObject { }),
        )
        val spec = LoginMuse(TerminalOutput {}) { _, mode ->
            assertEquals(MuseMintMode.ONBOARD, mode)
            granted
        }.spec("claude-muse", primary, "work")
        try {
            assertTrue(
                loginIo.persistIfSignedIn(primary, """{"access_token":"backup-acct"}""", spec.account),
            )
            kotlinx.coroutines.runBlocking { spec.afterPersist(primary, spec.account) }
            assertEquals("""{"access_token":"primary-secret"}""", Files.readString(primary))
            val pooled = tmp.resolve("muse-oauth/muse.json/work.json")
            val onDisk = Json.parseToJsonElement(Files.readString(pooled)).jsonObject
            assertEquals("backup-acct", onDisk.getValue("access_token").jsonPrimitive.content)
            assertEquals("pool-key-fake", onDisk.getValue("api_key").jsonPrimitive.content)
        } finally {
            spec.account?.releaseReservation()
        }
    }

    @Test
    fun `a throwing muse mint after persist does not fail the login`(@TempDir tmp: Path) {
        val path = tmp.resolve("muse.json")
        val spec = LoginMuse(TerminalOutput {}) { _, _ -> error("mint exploded") }.spec("claude-muse", path)
        assertTrue(loginIo.persistIfSignedIn(path, """{"access_token":"acct-token-fake"}""", spec.account))
        assertDoesNotThrow {
            kotlinx.coroutines.runBlocking { spec.afterPersist(path, spec.account) }
        }
        val onDisk = Json.parseToJsonElement(Files.readString(path)).jsonObject
        assertEquals("acct-token-fake", onDisk.getValue("access_token").jsonPrimitive.content)
        assertNull(onDisk["api_key"])
    }

    @Test
    fun `muse pre-mint bailouts print a reason`(@TempDir tmp: Path) {
        val missing = tmp.resolve("missing-muse.json")
        val out = StringBuilder()
        val output = TerminalOutput { out.appendLine(it) }
        val spec = LoginMuse(output) { _, _ -> error("must not mint") }.spec("claude-muse", missing)
        assertDoesNotThrow {
            kotlinx.coroutines.runBlocking { spec.afterPersist(missing, spec.account) }
        }
        assertTrue(out.contains("splice: muse key mint skipped: credential missing after login"), out.toString())
        out.clear()
        val empty = tmp.resolve("empty-muse.json")
        Files.writeString(empty, """{"token_type":"Bearer"}""")
        assertDoesNotThrow {
            kotlinx.coroutines.runBlocking { spec.afterPersist(empty, spec.account) }
        }
        assertTrue(out.contains("splice: muse key mint skipped: account token missing"), out.toString())
    }

    @Test
    fun `muse spec parse of a body without expires_in uses 600s`(@TempDir tmp: Path) {
        val spec = LoginMuse(TerminalOutput {}).spec("claude-muse", tmp.resolve("muse.json"))
        try {
            val auth = spec.parseDeviceAuth("""{"user_code":"A","device_code":"B"}""")
            assertEquals(600L, auth.expiresInS)
        } finally {
            spec.account?.releaseReservation()
        }
    }

    @Test
    fun `an IOException from muse mint after persist does not fail the login`(@TempDir tmp: Path) {
        val path = tmp.resolve("muse.json")
        val spec = LoginMuse(TerminalOutput {}) { _, _ -> throw java.io.IOException("disk") }.spec("claude-muse", path)
        assertTrue(loginIo.persistIfSignedIn(path, """{"access_token":"acct-token-fake"}""", spec.account))
        assertDoesNotThrow {
            kotlinx.coroutines.runBlocking { spec.afterPersist(path, spec.account) }
        }
        val onDisk = Json.parseToJsonElement(Files.readString(path)).jsonObject
        assertEquals("acct-token-fake", onDisk.getValue("access_token").jsonPrimitive.content)
        assertNull(onDisk["api_key"])
    }
}
