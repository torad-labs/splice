import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.app.LoginIo
import splice.app.auth.OAuthAccountFiles
import splice.app.auth.OAuthAccountLabels
import splice.app.auth.OAuthAccountRefused
import splice.app.auth.OAuthLoginAccount
import splice.app.cli.LoginCodex
import splice.app.cli.LoginGrok
import splice.app.cli.LoginKimi
import splice.client.login.LoginOutcomeFile
import splice.core.auth.RefreshCall
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.StatePaths
import splice.core.topology.AuthKind
import splice.core.util.LogSink
import splice.provider.codex.CodexAuthProvider
import splice.provider.grok.GrokAuthProvider
import splice.provider.kimi.KimiAuthProvider
import splice.provider.kimi.KimiDeviceIdentity
import splice.upstream.credentials.AccountCredentialIdentitySource
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialPresence
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class AccountLoginTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `pooled OAuth providers expose conservative credential file evidence`() {
        val paths = listOf(dir.resolve("codex.json"), dir.resolve("grok.json"), dir.resolve("kimi.json"))
        val providers = listOf<RefreshableAuthProvider>(
            CodexAuthProvider(paths[0], 0L, refreshCall = RefreshCall { error("unused") }),
            GrokAuthProvider(paths[1], refreshCall = RefreshCall { error("unused") }, authCacheMs = 30_000L),
            KimiAuthProvider(paths[2], refreshCall = RefreshCall { error("unused") }, authCacheMs = 30_000L),
        )
        val sources = providers.map { it as AccountCredentialIdentitySource }

        assertTrue(sources.all { it.credentialPresence() == CredentialPresence.MISSING })
        paths.forEach { Files.writeString(it, "{}") }
        assertTrue(sources.all { it.credentialPresence() == CredentialPresence.PRESENT })
    }

    @Test
    fun `credential polling is quiet while auth operations retain missing and unknown diagnostics`() = runTest {
        val paths = listOf(
            dir.resolve("codex-missing.json"),
            dir.resolve("grok-missing.json"),
            dir.resolve("kimi-missing.json"),
        )
        val logs = mutableListOf<String>()
        val log = LogSink { logs.add(it) }
        val providers = listOf<RefreshableAuthProvider>(
            CodexAuthProvider(paths[0], 0L, refreshCall = RefreshCall { error("unused") }, log = log),
            GrokAuthProvider(paths[1], refreshCall = RefreshCall { error("unused") }, authCacheMs = 30_000L, log = log),
            KimiAuthProvider(paths[2], refreshCall = RefreshCall { error("unused") }, authCacheMs = 30_000L, log = log),
        )
        val sources = providers.map { it as AccountCredentialIdentitySource }

        repeat(3) {
            sources.forEach { source ->
                val evidence = source.credentialEvidence()
                assertNull(evidence.identity)
                assertEquals(CredentialPresence.MISSING, evidence.presence)
            }
        }
        assertTrue(logs.isEmpty(), "missing credential polling logged: $logs")
        providers.forEach { it.describe() }
        assertEquals(sources.size, logs.count { it.contains("invalid_grant latch check skipped") })
        logs.clear()

        paths.forEachIndexed { index, path ->
            Files.createSymbolicLink(path, dir.resolve("absent-target-$index"))
        }
        sources.forEach { source ->
            val evidence = source.credentialEvidence()
            assertNull(evidence.identity)
            assertEquals(CredentialPresence.UNKNOWN, evidence.presence)
        }
        assertTrue(logs.isEmpty(), "unknown credential polling logged: $logs")

        providers.forEach { it.describe() }
        assertEquals(sources.size, logs.count { it.contains("invalid_grant latch check skipped") })
        logs.clear()
        providers.forEach { assertNull(it.refresh()) }
        assertEquals(sources.size, logs.count { it.contains("credential file read failed") })
    }

    @Test
    fun `symlink target creation and replacement update credential identity without poll logs`() {
        val paths = listOf(
            dir.resolve("codex-symlink.json"),
            dir.resolve("grok-symlink.json"),
            dir.resolve("kimi-symlink.json"),
        )
        val targets = paths.indices.map { index -> dir.resolve("credential-target-$index") }
        val logs = mutableListOf<String>()
        val log = LogSink { logs.add(it) }
        val sources = listOf<AccountCredentialIdentitySource>(
            CodexAuthProvider(paths[0], 0L, refreshCall = RefreshCall { error("unused") }, log = log),
            GrokAuthProvider(paths[1], refreshCall = RefreshCall { error("unused") }, authCacheMs = 30_000L, log = log),
            KimiAuthProvider(paths[2], refreshCall = RefreshCall { error("unused") }, authCacheMs = 30_000L, log = log),
        )
        paths.indices.forEach { index -> Files.createSymbolicLink(paths[index], targets[index]) }
        assertTrue(
            sources.all { it.credentialEvidence().presence == CredentialPresence.UNKNOWN },
            "dangling symlinks must remain unknown",
        )

        targets.forEach { Files.writeString(it, "{}") }
        val initialIdentities = sources.map { source ->
            val evidence = source.credentialEvidence()
            assertNotNull(evidence.identity)
            assertEquals(CredentialPresence.PRESENT, evidence.presence)
            evidence.identity
        }

        targets.forEach { Files.writeString(it, "{\"replacement\":true}") }
        sources.zip(initialIdentities).forEach { (source, initialIdentity) ->
            val replacement = source.credentialEvidence()
            assertNotNull(replacement.identity)
            assertNotEquals(initialIdentity, replacement.identity)
            assertEquals(CredentialPresence.PRESENT, replacement.presence)
        }
        assertTrue(logs.isEmpty(), "symlink credential polling logged: $logs")
    }

    @Test
    fun `the labeled login receipt says saved-for-restart, never using`() {
        val labeled = LoginIo().outcomeText("claudex", ok = true, label = "work")
        assertTrue(labeled.contains("signed in as 'work'") && labeled.contains("splice restart"), labeled)
        assertFalse(labeled.contains("using"), "the labeled account is not what this session uses: $labeled")
        assertTrue(LoginIo().outcomeText("claudex", ok = true, label = null).contains("using the new credentials"))
        assertTrue(LoginIo().outcomeText("claudex", ok = false, label = "work").contains("claudex login"))
    }

    @Test
    fun `two same-kind primaries in one directory own separate pools`() {
        val codex = dir.resolve("codex.json")
        val work = dir.resolve("codex-work.json")
        Files.writeString(codex, "{}")
        Files.writeString(work, "{}")
        val store = OAuthAccountFiles()
        store.writeLabeled(AuthKind.ChatgptOAuth, codex, "backup", JsonObject(emptyMap()))
        assertEquals(listOf("primary", "backup"), store.discover(AuthKind.ChatgptOAuth, codex).map { it.label })
        assertEquals(listOf("primary"), store.discover(AuthKind.ChatgptOAuth, work).map { it.label })
        assertTrue(Files.exists(dir.resolve("chatgpt-oauth/codex.json/backup.json")))
        val bare = dir.resolve("codex")
        Files.writeString(bare, "{}")
        val bareLabels = store.discover(AuthKind.ChatgptOAuth, bare).map { it.label }
        assertEquals(listOf("primary"), bareLabels, "codex and codex.json are different primaries")
    }

    @Test
    fun `first login keeps the provider native legacy primary`() {
        val primary = dir.resolve("grok.json")
        val account = OAuthAccountFiles().loginAccount(AuthKind.GrokOAuth, primary, requestedLabel = null)
        val providerJson = """{"tokens":{"access_token":"primary-secret"},"expires":123}"""

        assertTrue(LoginIo().persistIfSignedIn(primary, providerJson, account))

        assertTrue(account.primary)
        assertEquals(providerJson, Files.readString(primary))
        val onDisk = Json.parseToJsonElement(Files.readString(primary)).jsonObject
        assertFalse("splice_auth_kind" in onDisk)
        assertFalse("splice_account_label" in onDisk)
    }

    @Test
    fun `labeled login writes metadata beside the untouched primary`() {
        val primary = dir.resolve("kimi.json")
        val original = """{"access_token":"primary-secret"}"""
        Files.writeString(primary, original)
        val account = OAuthAccountFiles().loginAccount(AuthKind.KimiOAuth, primary, "work")
        val providerJson = """{"access_token":"backup-secret","refresh_token":"rotating"}"""

        assertTrue(LoginIo().persistIfSignedIn(primary, providerJson, account))

        assertEquals(original, Files.readString(primary))
        val target = dir.resolve("kimi-oauth/kimi.json/work.json")
        val onDisk = Json.parseToJsonElement(Files.readString(target)).jsonObject
        assertEquals("backup-secret", onDisk["access_token"]?.jsonPrimitive?.content)
        assertEquals("kimi-oauth", onDisk["splice_auth_kind"]?.jsonPrimitive?.content)
        assertEquals("work", onDisk["splice_account_label"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a labeled login cannot create a pool without its primary`() {
        val failure = assertThrows<OAuthAccountRefused> {
            OAuthAccountFiles().loginAccount(AuthKind.GrokOAuth, dir.resolve("grok.json"), "work")
        }

        assertTrue(failure.reason.contains("without --label first"))
    }

    @Test
    fun `a second unlabeled login rewrites the primary instead of stranding it`() {
        val primary = dir.resolve("codex.json")
        Files.writeString(primary, """{"tokens":{"access_token":"revoked-primary"}}""")
        val account = OAuthAccountFiles().loginAccount(AuthKind.ChatgptOAuth, primary, requestedLabel = null)
        val replacement = """{"tokens":{"access_token":"replacement-primary"}}"""

        assertTrue(LoginIo().persistIfSignedIn(primary, replacement, account))

        assertTrue(account.primary)
        assertEquals(replacement, Files.readString(primary))
        assertFalse(Files.exists(dir.resolve("chatgpt-oauth")))
    }

    @Test
    fun `labels ending in the quota suffix are refused before login`() {
        val primary = dir.resolve("grok.json")
        Files.writeString(primary, "{}")

        val failure = assertThrows<OAuthAccountRefused> {
            OAuthAccountFiles().loginAccount(AuthKind.GrokOAuth, primary, "work-quota")
        }

        assertEquals("OAuth account labels must not end in -quota", failure.reason)
    }

    @Test
    fun `auto cannot be persisted as a literal account label`() {
        val primary = dir.resolve("grok.json")
        val store = OAuthAccountFiles()
        Files.writeString(primary, "{}")

        val failure = assertThrows<OAuthAccountRefused> {
            store.writeLabeled(AuthKind.GrokOAuth, primary, "auto", JsonObject(emptyMap()))
        }

        assertEquals("OAuth account label auto is reserved for derived labels", failure.reason)
        assertFalse(Files.exists(store.poolDir(AuthKind.GrokOAuth, primary).resolve("auto.json")))
    }

    @Test
    fun `retained quota cannot be inherited by a reused automatic or explicit label`() {
        val primary = dir.resolve("grok.json")
        val store = OAuthAccountFiles()
        val pool = store.poolDir(AuthKind.GrokOAuth, primary)
        Files.writeString(primary, "{}")
        Files.createDirectories(pool)
        Files.writeString(pool.resolve("grok-2-quota.json"), "{}")

        val automatic = requireNotNull(LoginGrok().spec("grok", primary, "auto").account)
        val explicit = assertThrows<OAuthAccountRefused> {
            store.loginAccount(AuthKind.GrokOAuth, primary, "grok-2")
        }

        assertEquals("grok-3", automatic.resolvedLabel())
        assertTrue(explicit.reason.contains("retained quota state"))
        assertFalse(explicit.reason.contains("grok-2"))
    }

    @Test
    fun `dangling quota reserves ordinals before login and preserves the kimi device identity`() {
        val store = OAuthAccountFiles()
        for (kind in listOf(AuthKind.GrokOAuth, AuthKind.KimiOAuth)) {
            val base = kind.wire.removeSuffix("-oauth")
            val primary = dir.resolve("$base.json")
            Files.writeString(primary, "{}")
            val pool = Files.createDirectories(store.poolDir(kind, primary))
            Files.createSymbolicLink(pool.resolve("$base-2-quota.json"), dir.resolve("$base-gone"))
            Files.createSymbolicLink(pool.resolve("$base-3.json"), primary)
            Files.createSymbolicLink(pool.resolve("$base-4.json"), dir.resolve("$base-gone-credential"))
            assertThrows<OAuthAccountRefused> { store.loginAccount(kind, primary, "$base-2") }
            val account = store.loginAccount(kind, primary, "auto")
            assertEquals("$base-5", account.resolvedLabel())
            assertFalse(account.tokenDerivedLabel)
        }
        val primary = dir.resolve("kimi.json")
        val spec = LoginKimi().spec("kimi", primary, "auto")
        val account = requireNotNull(spec.account)

        assertTrue(LoginIo().persistIfSignedIn(primary, """{"access_token":"kimi-secret"}""", account))

        val pool = store.poolDir(AuthKind.KimiOAuth, primary)
        val persistedIdentity = KimiDeviceIdentity(deviceIdPath = pool.resolve("kimi-5-device_id"))
        assertEquals(persistedIdentity.headers(), spec.identityHeaders)
        assertTrue(Files.exists(pool.resolve("kimi-5.json")))
        assertTrue(Files.isSymbolicLink(pool.resolve("kimi-2-quota.json")))
        assertTrue(Files.isSymbolicLink(pool.resolve("kimi-3.json")))
        assertTrue(Files.isSymbolicLink(pool.resolve("kimi-4.json")))
        assertEquals("{}", Files.readString(primary))
    }

    @Test
    fun `codex auto receipt names the persisted collision suffix after token shaping`() {
        val primary = dir.resolve("codex.json")
        Files.writeString(primary, "{}")
        val account = requireNotNull(LoginCodex().spec("codex", primary, "auto").account)
        val claims = """
            {"https://api.openai.com/auth":{
              "chatgpt_account_id":"private-account-id","chatgpt_plan_type":"Plus"
            }}
        """.trimIndent()
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())
        val token = "header.$payload.signature"
        val authJson = """{"tokens":{"access_token":"$token","account_id":"private-account-id"}}"""
        val expected = OAuthAccountLabels.chatGpt("Plus", "private-account-id")
        val persisted = "$expected-2"
        val pool = OAuthAccountFiles().poolDir(AuthKind.ChatgptOAuth, primary)
        Files.createDirectories(pool)
        Files.writeString(pool.resolve("$expected-quota.json"), "{}")

        assertTrue(LoginIo().persistIfSignedIn(primary, authJson, account))
        assertTrue(Files.exists(pool.resolve("$persisted.json")))
        assertFalse(Files.exists(pool.resolve("$expected.json")))
        assertFalse(Files.exists(pool.resolve("auto.json")))
        assertFalse(expected.contains("private-account-id"))

        val savedHome = System.getProperty("user.home")
        System.setProperty("user.home", dir.toString())
        try {
            LoginIo().writeLoginOutcome("codex", ok = true, account = account)
            val receipt = requireNotNull(LoginOutcomeFile.consume(StatePaths().stateDir, "codex"))
            assertTrue(receipt.contains("signed in as '$persisted'"), receipt)
            assertFalse(receipt.contains("'auto'"), receipt)
        } finally {
            System.setProperty("user.home", savedHome)
        }
    }

    @Test
    fun `Kimi explicit labels lease the same namespace as automatic ordinals`() {
        val primary = dir.resolve("kimi.json")
        Files.writeString(primary, "{}")
        val explicit = LoginKimi().spec("kimi", primary, "kimi-2")
        try {
            val automatic = LoginKimi().spec("kimi", primary, "auto")
            try {
                assertEquals("kimi-2", requireNotNull(explicit.account).resolvedLabel())
                assertEquals("kimi-3", requireNotNull(automatic.account).resolvedLabel())
            } finally {
                automatic.account?.releaseReservation()
            }
        } finally {
            explicit.account?.releaseReservation()
        }
    }

    /** V4-114 PIN (kt-no-atomic-in-data-class). OAuthLoginAccount carried two AtomicReferences
     *  inside a `data class`, so the generated members lied in both directions: `equals` ignores
     *  body properties, making two accounts with the same FIELDS interchangeable when their leases
     *  are not; and `copy()` does not carry a body property at all, so a copy silently dropped the
     *  lease its own `check(compareAndSet(null, lease))` uniqueness relies on. Both assertions FAIL
     *  on the old shape — the first was `true` (data equality) and the second did not throw. */
    @Test
    fun `an OAuth login account is an identity and cannot be re-planned once it holds a lease`() {
        val primary = dir.resolve("kimi.json")
        Files.writeString(primary, "{}")
        val spec = LoginKimi().spec("kimi", primary, "kimi-2")
        try {
            val account = requireNotNull(spec.account)
            assertNotEquals(
                account,
                OAuthLoginAccount(account.kind, account.primary, account.label),
                "the same fields are not the same login: one of these holds the lease",
            )
            val refused = assertThrows<IllegalStateException> { account.copy(label = "elsewhere") }
            assertEquals("re-plan an OAuth login account BEFORE it holds a reservation", refused.message)
        } finally {
            spec.account?.releaseReservation()
        }
    }

    @Test
    fun `Kimi explicit relogin replaces its credential while refusing a simultaneous owner`() {
        val primary = dir.resolve("kimi.json")
        Files.writeString(primary, "{}")
        val store = OAuthAccountFiles()
        store.writeLabeled(
            AuthKind.KimiOAuth,
            primary,
            "work",
            JsonObject(mapOf("access_token" to JsonPrimitive("old-secret"))),
        )
        val first = LoginKimi().spec("kimi", primary, "work")
        try {
            val refused = assertThrows<OAuthAccountRefused> {
                LoginKimi().spec("kimi", primary, "work")
            }
            assertEquals("OAuth account label already has a login in progress", refused.reason)
            assertTrue(
                LoginIo().persistIfSignedIn(
                    primary,
                    """{"access_token":"replacement-secret"}""",
                    requireNotNull(first.account),
                ),
            )
            val saved = Json.parseToJsonElement(
                Files.readString(store.poolDir(AuthKind.KimiOAuth, primary).resolve("work.json")),
            ).jsonObject
            assertEquals("replacement-secret", saved["access_token"]?.jsonPrimitive?.content)
        } finally {
            first.account?.releaseReservation()
        }
    }

    @Test
    fun `refused first automatic Kimi login creates no reservation directory`() {
        val primary = dir.resolve("kimi.json")
        val store = OAuthAccountFiles()

        val refused = assertThrows<OAuthAccountRefused> {
            LoginKimi().spec("kimi", primary, "auto")
        }

        assertTrue(refused.reason.contains("without --label first"))
        assertFalse(Files.exists(store.poolDir(AuthKind.KimiOAuth, primary).resolve(".login-locks")))
    }

    @Test
    fun `concurrent Kimi automatic logins reserve distinct ordinals before device consent`() {
        val primary = dir.resolve("kimi.json")
        Files.writeString(primary, "{}")
        val first = LoginKimi().spec("kimi", primary, "auto")
        val second = LoginKimi().spec("kimi", primary, "auto")
        val firstAccount = requireNotNull(first.account)
        val secondAccount = requireNotNull(second.account)

        assertEquals(listOf("kimi-2", "kimi-3"), listOf(firstAccount.resolvedLabel(), secondAccount.resolvedLabel()))
        assertTrue(LoginIo().persistIfSignedIn(primary, """{"access_token":"first-secret"}""", firstAccount))
        assertTrue(LoginIo().persistIfSignedIn(primary, """{"access_token":"second-secret"}""", secondAccount))

        val pool = OAuthAccountFiles().poolDir(AuthKind.KimiOAuth, primary)
        val firstSaved = Json.parseToJsonElement(Files.readString(pool.resolve("kimi-2.json"))).jsonObject
        val secondSaved = Json.parseToJsonElement(Files.readString(pool.resolve("kimi-3.json"))).jsonObject
        assertEquals("first-secret", firstSaved["access_token"]?.jsonPrimitive?.content)
        assertEquals("second-secret", secondSaved["access_token"]?.jsonPrimitive?.content)
    }

    @Test
    fun `grok and kimi auto labels use the next provider ordinal`() {
        val grokPrimary = dir.resolve("grok.json")
        Files.writeString(grokPrimary, "{}")
        val grok = requireNotNull(LoginGrok().spec("grok", grokPrimary, "auto").account)
        assertTrue(
            LoginIo().persistIfSignedIn(
                grokPrimary,
                """{"tokens":{"access_token":"grok-secret"}}""",
                grok,
            ),
        )
        assertTrue(Files.exists(dir.resolve("grok-oauth/grok.json/grok-2.json")))

        val kimiPrimary = dir.resolve("kimi.json")
        Files.writeString(kimiPrimary, "{}")
        val store = OAuthAccountFiles()
        store.writeLabeled(
            AuthKind.KimiOAuth,
            kimiPrimary,
            "kimi-2",
            JsonObject(mapOf("access_token" to JsonPrimitive("old-secret"))),
        )
        val kimi = requireNotNull(LoginKimi().spec("kimi", kimiPrimary, "auto").account)
        assertEquals("kimi-3", kimi.resolvedLabel())
        assertTrue(LoginIo().persistIfSignedIn(kimiPrimary, """{"access_token":"kimi-secret"}""", kimi))
        assertTrue(Files.exists(dir.resolve("kimi-oauth/kimi.json/kimi-3.json")))
        assertFalse(Files.exists(dir.resolve("kimi-oauth/kimi.json/auto.json")))
    }
}
