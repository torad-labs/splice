// NEW: V4-10 timed credential recovery, evidence reconciliation, and stale-callback ownership.
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.spi.AccountCredentialIdentitySource
import splice.spi.AccountCredentialIdentitySource.CredentialEvidence
import splice.spi.AccountCredentialIdentitySource.CredentialFileEvidenceReader
import splice.spi.AccountCredentialIdentitySource.CredentialPresence
import splice.spi.AccountQuotaSource
import splice.spi.ElapsedNow
import splice.spi.PoolAccount
import splice.spi.RateLimitCooldown
import java.util.concurrent.atomic.AtomicInteger

class AccountCredentialEligibilityTest {
    @Test
    fun `one reconciliation requests one combined credential observation`() {
        val observations = AtomicInteger()
        val auth = object : RefreshableAuthProvider, AccountCredentialIdentitySource {
            override suspend fun credentials(): Credentials = Credentials.Bearer("secret", "account")
            override suspend fun refresh(): Credentials = credentials()
            override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
            override fun credentialIdentity(): CredentialFileIdentity? = error("split identity observation used")
            override fun credentialPresence(): CredentialPresence = error("split presence observation used")
            override fun credentialEvidence(): CredentialEvidence {
                observations.incrementAndGet()
                return CredentialEvidence(
                    CredentialFileIdentity(1L, 1L, "digest-of-the-observed-credential"),
                    CredentialPresence.PRESENT,
                )
            }
        }
        val account = PoolAccount(
            label = "primary",
            primary = true,
            auth = auth,
            quota = AccountQuotaSource { null },
            cooldown = RateLimitCooldown(ElapsedNow { 0L }),
        )
        observations.set(0)

        account.credentialStatus(1_000_000L)

        assertEquals(1, observations.get())
    }

    @Test
    fun `terminal 401 holds only future selections and reports auth state separately`() {
        val fixture = AccountPoolTest.Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)

        val inFlight = pool.select("session")
        inFlight.markCredentialUnavailable()
        val next = pool.select("session")
        val primaryView = pool.view("session").accounts.single { it.primary }

        assertSame(primary, inFlight.account)
        assertSame(backup, next.account)
        assertTrue(primaryView.credentialPresent, "a rejected credential file is still present")
        assertFalse(primaryView.available)
        assertEquals(1_300_000L, primaryView.authExcludedUntilEpochMillis)
        assertEquals("terminal_401", primaryView.authExclusionReason)
    }

    @Test
    fun `unchanged credentials get one recovery probe and cancellation releases it`() = runBlocking {
        val fixture = AccountPoolTest.Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        pool.select("failed").markCredentialUnavailable()
        fixture.advanceWall(300_000L)

        val selections = List(64) { index ->
            async(Dispatchers.Default) { pool.select("probe-$index") }
        }.awaitAll()
        val probes = selections.filter { it.account === primary }

        assertEquals(1, probes.size, "one expired auth hold admits exactly one recovery probe")
        assertTrue(selections.filterNot { it in probes }.all { it.account === backup })
        probes.single().releaseCredentialProbe()
        val replacement = pool.select("replacement-probe")
        assertSame(primary, replacement.account, "cancellation/failure release makes the probe reusable")
        replacement.releaseCredentialProbe()
    }

    @Test
    fun `only recovery-probe failures double the bounded auth hold`() {
        val fixture = AccountPoolTest.Fixture()
        val primary = fixture.account("primary", primary = true)
        val pool = fixture.pool(primary, fixture.account("plus-a"))
        val staleFollower = pool.select("stale-follower")
        pool.select("first-failure").markCredentialUnavailable()
        staleFollower.markCredentialUnavailable()

        val expectedDelays = listOf(300_000L, 600_000L, 1_200_000L, 2_400_000L, 3_600_000L, 3_600_000L)
        expectedDelays.forEachIndexed { index, expected ->
            val view = pool.view(null).accounts.single { it.primary }
            assertEquals(expected, checkNotNull(view.authExcludedUntilEpochMillis) - fixture.now.get())
            if (index != expectedDelays.lastIndex) {
                fixture.now.set(checkNotNull(view.authExcludedUntilEpochMillis))
                pool.select("failed-probe-$index").markCredentialUnavailable()
            }
        }
    }

    @Test
    fun `auth hold remains capped after seventy consecutive failed probes`() {
        val fixture = AccountPoolTest.Fixture()
        val primary = fixture.account("primary", primary = true)
        val pool = fixture.pool(primary)
        val ramp = listOf(300_000L, 600_000L, 1_200_000L, 2_400_000L)
        var selection = pool.select("initial")

        repeat(70) { index ->
            selection.markCredentialUnavailable()
            val view = pool.view(null).accounts.single()
            val expected = ramp.getOrElse(index) { 3_600_000L }
            assertEquals(expected, checkNotNull(view.authExcludedUntilEpochMillis) - fixture.now.get())
            if (index < 69) {
                fixture.now.set(checkNotNull(view.authExcludedUntilEpochMillis))
                selection = pool.select("probe-$index")
            }
        }
    }

    @Test
    fun `credential revision changes defeat stale failure races and restore before hold expiry`() {
        val fixture = AccountPoolTest.Fixture()
        val primary = fixture.account("primary", primary = true)
        val pool = fixture.pool(primary, fixture.account("plus-a"))
        val stale = pool.select("stale")

        fixture.rotateCredential(primary)
        stale.markCredentialUnavailable()

        assertSame(primary, pool.select("after-race").account)
        val held = pool.select("held")
        held.markCredentialUnavailable()
        fixture.rotateCredential(primary)
        assertSame(primary, pool.select("after-relogin").account)
        assertEquals(null, pool.view(null).accounts.single { it.primary }.authExclusionReason)
    }

    @Test
    fun `an unknown stat followed by the same revision preserves the hold and failure count`() {
        val fixture = AccountPoolTest.Fixture()
        val primary = fixture.account("primary", primary = true)
        val pool = fixture.pool(primary, fixture.account("plus-a"))
        pool.select("failed").markCredentialUnavailable()
        val original = fixture.hideCredentialIdentity(primary)

        pool.view(null)
        fixture.restoreCredentialIdentity(primary, original)

        var view = pool.view(null).accounts.single { it.primary }
        assertEquals("terminal_401", view.authExclusionReason)
        assertEquals(1_300_000L, view.authExcludedUntilEpochMillis)
        fixture.now.set(checkNotNull(view.authExcludedUntilEpochMillis))
        pool.select("failed-probe").markCredentialUnavailable()
        view = pool.view(null).accounts.single { it.primary }
        assertEquals(600_000L, checkNotNull(view.authExcludedUntilEpochMillis) - fixture.now.get())
    }

    @Test
    fun `unknown evidence preserves a hold until one later replacement observation recovers`() {
        val fixture = AccountPoolTest.Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        pool.select("failed").markCredentialUnavailable()
        fixture.hideCredentialIdentity(primary)

        val held = pool.view(null).accounts.single { it.primary }
        assertEquals("terminal_401", held.authExclusionReason)
        assertSame(backup, pool.select("while-unknown").account)

        fixture.rotateCredential(primary)

        assertSame(primary, pool.select("after-replacement").account)
        assertEquals(null, pool.view(null).accounts.single { it.primary }.authExclusionReason)
    }

    @Test
    fun `persistent unknown stat on a seeded credential still admits one timed probe`() {
        val fixture = AccountPoolTest.Fixture()
        val primary = fixture.account("primary", primary = true, credentialIdentityKnown = false)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        pool.select("failed").markCredentialUnavailable()
        fixture.advanceWall(300_000L)

        val probe = pool.select("probe")
        val follower = pool.select("follower")

        assertSame(primary, probe.account)
        assertSame(backup, follower.account)
        assertEquals("recovery_probe_in_flight", pool.view(null).accounts.single { it.primary }.authExclusionReason)
        probe.releaseCredentialProbe()
    }

    @Test
    fun `stale clean turn success cannot clear a newer credential hold`() {
        val fixture = AccountPoolTest.Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        val stale = pool.select("stale")
        fixture.rotateCredential(primary)
        pool.select("new-revision").markCredentialUnavailable()

        stale.markTurnSucceeded()

        val view = pool.view(null).accounts.single { it.primary }
        assertEquals("terminal_401", view.authExclusionReason)
        assertEquals(1_300_000L, view.authExcludedUntilEpochMillis)
        assertSame(backup, pool.select("after-stale-success").account)
    }

    @Test
    fun `stale refresh success cannot clear a newer credential hold`() {
        val fixture = AccountPoolTest.Fixture()
        val primary = fixture.account("primary", primary = true)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)
        val stale = pool.select("stale")
        fixture.rotateCredential(primary)
        pool.select("new-revision").markCredentialUnavailable()

        stale.markCredentialRefreshSucceeded()

        val view = pool.view(null).accounts.single { it.primary }
        assertEquals("terminal_401", view.authExclusionReason)
        assertEquals(1_300_000L, view.authExcludedUntilEpochMillis)
        assertSame(backup, pool.select("after-stale-refresh").account)
    }

    @Test
    fun `missing credentials stay unavailable until a recreated revision appears`() {
        val fixture = AccountPoolTest.Fixture()
        val primary = fixture.account("primary", primary = true, credentialPresent = false)
        val backup = fixture.account("plus-a")
        val pool = fixture.pool(primary, backup)

        assertSame(backup, pool.select("missing").account)
        val missingView = pool.view(null).accounts.single { it.primary }
        assertFalse(missingView.credentialPresent)
        assertEquals("credential_missing", missingView.authExclusionReason)
        assertEquals(null, missingView.authExcludedUntilEpochMillis)

        fixture.rotateCredential(primary)

        assertSame(primary, pool.select("recreated").account)
        assertTrue(pool.view(null).accounts.single { it.primary }.credentialPresent)
    }

    @Test
    fun `turn and refresh success reset the failure series to five minutes`() {
        val fixture = AccountPoolTest.Fixture()
        val primary = fixture.account("primary", primary = true)
        val pool = fixture.pool(primary, fixture.account("plus-a"))
        pool.select("first").markCredentialUnavailable()
        fixture.advanceWall(300_000L)
        pool.select("second").markCredentialUnavailable()
        fixture.advanceWall(600_000L)

        val successfulTurn = pool.select("turn-success")
        successfulTurn.markTurnSucceeded()
        successfulTurn.markCredentialUnavailable()
        var view = pool.view(null).accounts.single { it.primary }
        assertEquals(300_000L, checkNotNull(view.authExcludedUntilEpochMillis) - fixture.now.get())
        fixture.advanceWall(300_000L)

        val successfulRefresh = pool.select("refresh-success")
        fixture.rotateCredential(primary)
        successfulRefresh.markCredentialRefreshSucceeded()
        successfulRefresh.markCredentialUnavailable()
        view = pool.view(null).accounts.single { it.primary }
        assertEquals(300_000L, checkNotNull(view.authExcludedUntilEpochMillis) - fixture.now.get())
    }

    // V4-70: THE READ FAILURE MUST FAIL OPEN, and this is the one way the content digest could make
    // things WORSE than the bug it fixes — a transient read error turned into a lockout. The reader
    // takes the digest inside the same best-effort block as its stat, so an unreadable path yields
    // NO identity rather than a stale one, and a null identity is the latch's word for UNKNOWN
    // (never suppresses). Pinned here because this is where the identity is actually produced from
    // a real path; the latch-level half lives in InvalidGrantLatchTest.
    @Test
    fun `an unreadable credential file yields no identity, never a stale one - V4-70`() {
        val missing = java.nio.file.Path.of("/nonexistent/dir/credential-that-cannot-be-read.json")
        val evidence = CredentialFileEvidenceReader.read(missing)
        assertNull(evidence.identity, "an unreadable file must produce UNKNOWN, not an identity")
        assertNotEquals(CredentialPresence.PRESENT, evidence.presence)
    }
}
