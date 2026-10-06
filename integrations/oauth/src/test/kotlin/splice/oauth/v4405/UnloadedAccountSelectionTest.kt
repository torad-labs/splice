// NEW: V4-405 — discover() now lists an orphaned quota and a refused link on every OAuth head, so this drives
// the real AccountPool over a real discover and shows that neither is ever selected for a turn or read. The
// backups carry an empty quota beside a primary that is full: an account the pool could use would win the turn.
package splice.oauth.v4405

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.topology.AuthKind
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.oauth.OAuthAccountFile
import splice.oauth.OAuthAccountFiles
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.credentials.Selection
import splice.upstream.retry.RateLimitCooldown
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val NOW_MS = 1_000_000L
private const val TURNS = 5
private const val FULL = 100.0

class UnloadedAccountSelectionTest {
    private val kind = AuthKind.ChatgptOAuth

    /** Counts every way a credential can be read, so "never read" is a number rather than a claim. */
    private class Reads : RefreshableAuthProvider {
        val count = AtomicInteger()

        override suspend fun credentials(): Credentials? = refresh()

        override suspend fun refresh(): Credentials? {
            count.incrementAndGet()
            return Credentials.Bearer("synthetic")
        }

        override suspend fun describe(): AuthDescription {
            count.incrementAndGet()
            return AuthDescription(true, "test")
        }
    }

    /** The pool exactly as the app arms wire it: one account per listed file, seeded from credentialPresent. */
    private class Rig(files: List<OAuthAccountFile>) {
        val reads = files.associate { it.label to Reads() }
        val primaryQuota = AtomicReference(used(0.0))
        val pool = AccountPool(
            files.map { file ->
                PoolAccount(
                    label = file.label,
                    primary = file.primary,
                    auth = reads.getValue(file.label),
                    quota = AccountQuotaSource { if (file.primary) primaryQuota.get() else used(0.0) },
                    cooldown = RateLimitCooldown(ElapsedClock { 0L }),
                    credentialPresent = file.credentialPresent,
                )
            },
            WallClock { NOW_MS },
        )

        fun used(percent: Double) = QuotaSnapshot(
            fiveHour = QuotaWindow(percent, 2_000L, 18_000L),
            sevenDay = QuotaWindow(percent, 2_000L, 604_800L),
        )

        fun chosenLabel(session: String?): String? =
            (pool.select(session) as? Selection.Chosen)?.account?.account?.label

        fun readsOf(vararg labels: String): List<Int> = labels.map { reads.getValue(it).count.get() }
    }

    private fun populate(home: Path) {
        val primary = home.resolve("primary.json")
        Files.writeString(primary, "{}")
        val pool = Files.createDirectories(OAuthAccountFiles().poolDir(kind, primary))
        Files.writeString(pool.resolve("work-quota.json"), "{}")
        Files.createSymbolicLink(pool.resolve("linked.json"), home.resolve("someone-elses-credential"))
    }

    private fun listed(home: Path): List<OAuthAccountFile> =
        OAuthAccountFiles().discover(kind, home.resolve("primary.json"), LogSink {})

    @Test
    fun `an orphan and a refused link are listed and every turn still goes to the primary`(@TempDir home: Path) {
        populate(home)
        val files = listed(home)
        val rig = Rig(files)

        assertEquals(listOf("primary", "linked", "work"), files.map(OAuthAccountFile::label))
        repeat(TURNS) { turn ->
            assertEquals("primary", rig.chosenLabel("session-$turn"))
            assertEquals("primary", rig.chosenLabel(null))
        }
        assertEquals(listOf(0, 0), rig.readsOf("linked", "work"))
    }

    @Test
    fun `a full primary stays selectable without handing turns to an orphan or a refused link`(@TempDir home: Path) {
        populate(home)
        val rig = Rig(listed(home))
        rig.primaryQuota.set(rig.used(FULL))

        repeat(TURNS) { turn ->
            assertEquals("primary", rig.chosenLabel("session-$turn"), "session $turn")
            assertEquals("primary", rig.chosenLabel(null))
        }
        assertEquals(listOf(0, 0), rig.readsOf("linked", "work"))
    }

    @Test
    fun `a primary whose credential is gone is exhausted with no attempt on the orphan or the link`(
        @TempDir home: Path,
    ) {
        populate(home)
        Files.delete(home.resolve("primary.json"))
        val files = listed(home)
        val rig = Rig(files)

        assertFalse(files.single { it.primary }.credentialPresent)
        repeat(TURNS) { turn ->
            assertTrue(rig.pool.select("session-$turn") is Selection.Exhausted, "session $turn")
        }
        assertEquals(listOf(0, 0, 0), rig.readsOf("primary", "linked", "work"))
    }
}
