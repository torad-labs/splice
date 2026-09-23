package splice.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.app.auth.OAuthAccountFiles
import splice.app.auth.OAuthAccountLabels
import splice.app.head.HeadBoot
import splice.control.ManagedHead
import splice.core.config.StatePaths
import splice.core.topology.AuthKind
import splice.topology.TopologyLoader
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

        val restarted = OAuthAccountFiles().discover(AuthKind.ChatgptOAuth, primary)

        assertEquals(listOf("primary", "plus-12345678"), restarted.map { it.label })
        assertEquals(primary, restarted.single { it.primary }.credentialFile)
        assertEquals("plus-12345678-quota.json", restarted.last().quotaFile.fileName.toString())
        assertFalse(restarted.toString().contains("secret"))
    }

    @Test
    fun `a pooled credential labeled for another provider is refused`() {
        val store = OAuthAccountFiles()
        val primary = dir.resolve("codex.json")
        val pool = store.poolDir(AuthKind.ChatgptOAuth, primary)
        Files.createDirectories(pool)
        Files.writeString(
            pool.resolve("plus-12345678.json"),
            """{"splice_auth_kind":"grok-oauth","splice_account_label":"plus-12345678"}""",
        )

        val failure = assertThrows<IllegalArgumentException> {
            store.discover(AuthKind.ChatgptOAuth, primary)
        }

        assertEquals("pooled OAuth account file has the wrong auth kind", failure.message)
        assertFalse(failure.message.orEmpty().contains("grok-oauth"))
    }

    @Test
    fun `reserved automatic and primary labels are refused during discovery`() {
        for (label in listOf("auto", "primary")) {
            val store = OAuthAccountFiles()
            val root = dir.resolve(label)
            val primary = root.resolve("codex.json")
            val pool = store.poolDir(AuthKind.ChatgptOAuth, primary)
            Files.createDirectories(pool)
            Files.writeString(
                pool.resolve("$label.json"),
                """{"splice_auth_kind":"chatgpt-oauth","splice_account_label":"$label"}""",
            )

            val failure = assertThrows<IllegalArgumentException> {
                store.discover(AuthKind.ChatgptOAuth, primary)
            }

            assertTrue(failure.message.orEmpty().contains("reserved"))
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

        val restarted = store.discover(AuthKind.ChatgptOAuth, primary)
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
        val store = OAuthAccountFiles(log = logs::add)
        val primary = dir.resolve("codex.json")
        Files.writeString(primary, "{}")
        store.writeLabeled(AuthKind.ChatgptOAuth, primary, "work", JsonObject(emptyMap()))
        val pool = store.poolDir(AuthKind.ChatgptOAuth, primary)
        val stray = pool.resolve("notes.json")
        Files.writeString(stray, "{private-secret")
        Files.createSymbolicLink(pool.resolve("linked.json"), stray)

        val accounts = store.discover(AuthKind.ChatgptOAuth, primary)

        assertEquals(listOf("primary", "work"), accounts.map { it.label })
        assertEquals(listOf("splice: skipped OAuth pool file notes.json (not a credential)\n"), logs)
        assertEquals("{private-secret", Files.readString(stray))
        assertTrue(Files.isSymbolicLink(pool.resolve("linked.json")))
        assertFalse(logs.joinToString("").contains("private-secret"))
        assertFalse(logs.joinToString("").contains(dir.toString()))
    }

    @Test
    fun `JSON without a declared kind is skipped without printing its contents`() {
        val logs = mutableListOf<String>()
        val store = OAuthAccountFiles(log = logs::add)
        val primary = dir.resolve("codex.json")
        val pool = Files.createDirectories(store.poolDir(AuthKind.ChatgptOAuth, primary))
        val documents = mapOf(
            "notes.json" to """{"access_token":"private-secret"}""",
            "array.json" to "[]",
            "scalar.json" to "false",
            "null.json" to """{"splice_auth_kind":null}""",
        )
        documents.forEach { (name, content) -> Files.writeString(pool.resolve(name), content) }

        assertEquals(listOf("primary"), store.discover(AuthKind.ChatgptOAuth, primary).map { it.label })

        val expected = documents.keys.sorted().map { "splice: skipped OAuth pool file $it (not a credential)\n" }
        assertEquals(expected, logs)
        documents.forEach { (name, content) -> assertEquals(content, Files.readString(pool.resolve(name))) }
    }

    @Test
    fun `a stray file with an unsafe name is skipped without exposing the name`() {
        val logs = mutableListOf<String>()
        val store = OAuthAccountFiles(log = logs::add)
        val primary = dir.resolve("codex.json")
        val pool = Files.createDirectories(store.poolDir(AuthKind.ChatgptOAuth, primary))
        Files.writeString(pool.resolve("private@example.com.json"), "{}")

        assertEquals(listOf("primary"), store.discover(AuthKind.ChatgptOAuth, primary).map { it.label })

        assertEquals(listOf("splice: skipped OAuth pool file <unsafe filename> (not a credential)\n"), logs)
        assertFalse(logs.joinToString("").contains("private@example.com"))
    }

    @Test
    fun `a declared credential with mismatched filename remains refused`() {
        val logs = mutableListOf<String>()
        val store = OAuthAccountFiles(log = logs::add)
        val primary = dir.resolve("codex.json")
        val pool = Files.createDirectories(store.poolDir(AuthKind.ChatgptOAuth, primary))
        Files.writeString(
            pool.resolve("work.json"),
            """{"splice_auth_kind":"chatgpt-oauth","splice_account_label":"personal"}""",
        )

        val failure = assertThrows<IllegalArgumentException> { store.discover(AuthKind.ChatgptOAuth, primary) }

        assertEquals("pooled OAuth account file does not match its filename", failure.message)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `matching unsafe filename metadata is refused without echoing the label`() {
        val store = OAuthAccountFiles()
        val primary = dir.resolve("codex.json")
        val pool = store.poolDir(AuthKind.ChatgptOAuth, primary)
        Files.createDirectories(pool)
        Files.writeString(
            pool.resolve("private@example.com.json"),
            """{"splice_auth_kind":"chatgpt-oauth","splice_account_label":"private@example.com"}""",
        )

        val failure = assertThrows<IllegalArgumentException> {
            store.discover(AuthKind.ChatgptOAuth, primary)
        }

        assertEquals("invalid OAuth account label", failure.message)
        assertFalse(failure.message.orEmpty().contains("private@example.com"))
    }

    @Test
    fun `boot failure map and log do not echo an unsafe matching label`() {
        val store = OAuthAccountFiles()
        val primary = dir.resolve("codex.json")
        val pool = store.poolDir(AuthKind.ChatgptOAuth, primary)
        Files.createDirectories(pool)
        Files.writeString(
            pool.resolve("private@example.com.json"),
            """{"splice_auth_kind":"chatgpt-oauth","splice_account_label":"private@example.com"}""",
        )
        val topology = TopologyLoader.parse(HEAD_TOML)
        val logs = mutableListOf<String>()

        val failed = HeadBoot().assembleDaemonHeads(
            topology,
            StatePaths(baseOverride = dir.resolve("state")),
            mutableMapOf<String, ManagedHead>(),
            logs::add,
            HeadAssembly { _, _, _ ->
                store.discover(AuthKind.ChatgptOAuth, primary)
                error("unsafe account unexpectedly discovered")
            },
        )

        assertEquals("invalid OAuth account label", failed["codex"])
        assertTrue(logs.any { it.contains("invalid OAuth account label") })
        assertFalse(logs.joinToString("").contains("private@example.com"))
    }

    @Test
    fun `default labels are stable non PII and ordinals reuse the first gap`() {
        val first = OAuthAccountLabels.chatGpt("Plus", "private-account-id")
        val second = OAuthAccountLabels.chatGpt("Plus", "private-account-id")

        assertEquals(first, second)
        assertTrue(first.matches(Regex("plus-[0-9a-f]{8}")))
        assertFalse(first.contains("private-account-id"))
        assertEquals("grok-3", OAuthAccountLabels.ordinal(AuthKind.GrokOAuth, setOf("grok-2", "grok-4")))
    }
}

private const val HEAD_TOML = """
[providers.codex]
dialect = "openai-responses"
base_url = "https://example.invalid"
auth = { kind = "chatgpt-oauth" }
[[providers.codex.models]]
id = "gpt-test"
context_window = 200000

[heads.codex]
provider = "codex"
port = 3101
discovery_prefix = "claude-codex--"
pinned_model = "gpt-test"
"""
