package splice.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.AuthKind
import splice.core.util.LogSink
import splice.oauth.OAuthAccountFiles
import java.nio.file.Files
import java.nio.file.Path

class AccountRestartTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `legacy primary and labeled accounts are rediscovered without migration`() {
        val store = OAuthAccountFiles()
        val primary = dir.resolve("codex.json")
        Files.writeString(primary, "{\"tokens\":{\"access_token\":\"primary-secret\"}}")
        store.writeLabeled(
            AuthKind.ChatgptOAuth,
            primary,
            "plus-12345678",
            JsonObject(mapOf("tokens" to JsonObject(mapOf("access_token" to JsonPrimitive("pooled-secret"))))),
        )
        Files.writeString(store.poolDir(AuthKind.ChatgptOAuth, primary).resolve("ignored-quota.json"), "{}")

        val restarted = OAuthAccountFiles().discover(AuthKind.ChatgptOAuth, primary, LogSink {})

        // V4-405: a quota with no credential beside it lists, with no credential, so its sign-in can be renewed.
        assertEquals(listOf("primary", "ignored", "plus-12345678"), restarted.map { it.label })
        assertFalse(restarted.single { it.label == "ignored" }.credentialPresent)
        assertEquals(null, restarted.single { it.label == "ignored" }.refusal)
        assertEquals(primary, restarted.single { it.primary }.credentialFile)
        assertEquals("plus-12345678-quota.json", restarted.last().quotaFile.fileName.toString())
        assertFalse(restarted.toString().contains("secret"))
    }

    // One bad file refuses its own login, never the pool: the head's other logins keep serving.
    @Test
    fun `a pooled credential labeled for another provider is refused while the other logins keep serving`() {
        val store = OAuthAccountFiles()
        val primary = dir.resolve("codex.json")
        Files.writeString(primary, "{}")
        store.writeLabeled(AuthKind.ChatgptOAuth, primary, "work", JsonObject(emptyMap()))
        val pool = store.poolDir(AuthKind.ChatgptOAuth, primary)
        Files.writeString(
            pool.resolve("plus-12345678.json"),
            """{"splice_auth_kind":"grok-oauth","splice_account_label":"plus-12345678"}""",
        )

        val accounts = store.discover(AuthKind.ChatgptOAuth, primary, LogSink {})

        val refused = accounts.single { it.label == "plus-12345678" }
        assertFalse(refused.credentialPresent)
        assertTrue(refused.refusal.orEmpty().contains("another provider"), "${refused.refusal}")
        assertFalse(refused.refusal.orEmpty().contains("grok-oauth"))
        assertTrue(accounts.single { it.label == "work" }.credentialPresent)
        assertTrue(accounts.single { it.primary }.credentialPresent)
    }

    @Test
    fun `reserved automatic and primary labels are skipped during discovery`() {
        for (label in listOf("auto", "primary")) {
            val logs = mutableListOf<String>()
            val store = OAuthAccountFiles()
            val root = dir.resolve(label)
            val primary = root.resolve("codex.json")
            val pool = store.poolDir(AuthKind.ChatgptOAuth, primary)
            Files.createDirectories(pool)
            Files.writeString(
                pool.resolve("$label.json"),
                """{"splice_auth_kind":"chatgpt-oauth","splice_account_label":"$label"}""",
            )

            val accounts = store.discover(AuthKind.ChatgptOAuth, primary, logs::add)

            assertEquals(listOf("primary"), accounts.map { it.label })
            assertTrue(accounts.single().primary)
            assertTrue(logs.single().contains("reserved"), "$logs")
        }
    }

    @Test
    fun `a dangling primary is unavailable while a readable labeled credential survives restart`() {
        val store = OAuthAccountFiles()
        val primary = dir.resolve("codex.json")
        Files.createSymbolicLink(primary, dir.resolve("missing-codex.json"))
        store.writeLabeled(
            AuthKind.ChatgptOAuth,
            primary,
            "work",
            JsonObject(mapOf("tokens" to JsonObject(mapOf("access_token" to JsonPrimitive("backup"))))),
        )

        val restarted = store.discover(AuthKind.ChatgptOAuth, primary, LogSink {})
        val failure = assertThrows<IllegalArgumentException> {
            store.loginAccount(AuthKind.ChatgptOAuth, primary, "other")
        }

        assertFalse(restarted.single { it.primary }.credentialPresent)
        assertTrue(restarted.single { it.label == "work" }.credentialPresent)
        assertTrue(failure.message.orEmpty().contains("without --label first"))
    }

    @Test
    fun `a malformed pool file is skipped with one safe diagnostic and valid accounts survive`() {
        val logs = mutableListOf<String>()
        val store = OAuthAccountFiles()
        val primary = dir.resolve("codex.json")
        Files.writeString(primary, "{}")
        store.writeLabeled(AuthKind.ChatgptOAuth, primary, "work", JsonObject(emptyMap()))
        val pool = store.poolDir(AuthKind.ChatgptOAuth, primary)
        val stray = pool.resolve("notes.json")
        Files.writeString(stray, "{private-secret")
        Files.createSymbolicLink(pool.resolve("linked.json"), stray)

        val accounts = store.discover(AuthKind.ChatgptOAuth, primary, logs::add)

        // V4-405: the link lists as refused instead of vanishing; the one log line shows it was never read.
        assertEquals(listOf("primary", "linked", "work"), accounts.map { it.label })
        assertFalse(accounts.single { it.label == "linked" }.credentialPresent)
        assertTrue(accounts.single { it.label == "linked" }.refusal != null)
        assertEquals(listOf("splice: skipped OAuth pool file notes.json (not a credential)\n"), logs)
        assertEquals("{private-secret", Files.readString(stray))
        assertTrue(Files.isSymbolicLink(pool.resolve("linked.json")))
        assertFalse(logs.joinToString("").contains("private-secret"))
        assertFalse(logs.joinToString("").contains(dir.toString()))
    }

    @Test
    fun `JSON without a declared kind is skipped without printing its contents`() {
        val logs = mutableListOf<String>()
        val store = OAuthAccountFiles()
        val primary = dir.resolve("codex.json")
        val pool = Files.createDirectories(store.poolDir(AuthKind.ChatgptOAuth, primary))
        val documents = mapOf(
            "notes.json" to """{"access_token":"private-secret"}""",
            "array.json" to "[]",
            "scalar.json" to "false",
            "null.json" to """{"splice_auth_kind":null}""",
        )
        documents.forEach { (name, content) -> Files.writeString(pool.resolve(name), content) }

        assertEquals(listOf("primary"), store.discover(AuthKind.ChatgptOAuth, primary, logs::add).map { it.label })

        val expected = documents.keys.sorted().map { "splice: skipped OAuth pool file $it (not a credential)\n" }
        assertEquals(expected, logs)
        documents.forEach { (name, content) -> assertEquals(content, Files.readString(pool.resolve(name))) }
    }

    @Test
    fun `a stray file with an unsafe name is skipped without exposing the name`() {
        val logs = mutableListOf<String>()
        val store = OAuthAccountFiles()
        val primary = dir.resolve("codex.json")
        val pool = Files.createDirectories(store.poolDir(AuthKind.ChatgptOAuth, primary))
        Files.writeString(pool.resolve("private@example.com.json"), "{}")

        assertEquals(listOf("primary"), store.discover(AuthKind.ChatgptOAuth, primary, logs::add).map { it.label })

        assertEquals(listOf("splice: skipped OAuth pool file <unsafe filename> (not a credential)\n"), logs)
        assertFalse(logs.joinToString("").contains("private@example.com"))
    }

    @Test
    fun `a declared credential with mismatched filename is refused with its reason`() {
        val logs = mutableListOf<String>()
        val store = OAuthAccountFiles()
        val primary = dir.resolve("codex.json")
        val pool = Files.createDirectories(store.poolDir(AuthKind.ChatgptOAuth, primary))
        Files.writeString(
            pool.resolve("work.json"),
            """{"splice_auth_kind":"chatgpt-oauth","splice_account_label":"personal"}""",
        )

        val accounts = store.discover(AuthKind.ChatgptOAuth, primary, logs::add)

        val refused = accounts.single { it.label == "work" }
        assertFalse(refused.credentialPresent)
        assertTrue(refused.refusal.orEmpty().contains("another label"), "${refused.refusal}")
        assertEquals(1, logs.size, "$logs")
    }

    @Test
    fun `matching unsafe filename metadata is skipped without echoing the label`() {
        val logs = mutableListOf<String>()
        val store = OAuthAccountFiles()
        val primary = dir.resolve("codex.json")
        val pool = store.poolDir(AuthKind.ChatgptOAuth, primary)
        Files.createDirectories(pool)
        Files.writeString(
            pool.resolve("private@example.com.json"),
            """{"splice_auth_kind":"chatgpt-oauth","splice_account_label":"private@example.com"}""",
        )

        val accounts = store.discover(AuthKind.ChatgptOAuth, primary, logs::add)

        assertEquals(listOf("primary"), accounts.map { it.label })
        assertEquals(listOf("splice: skipped OAuth pool file <unsafe filename> (invalid OAuth account label)\n"), logs)
        assertFalse(accounts.toString().contains("private@example.com"))
    }
}
