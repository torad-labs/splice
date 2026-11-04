// NEW: a Use now pin outlives the daemon (Nov 3, 2026). Marcos pressed Use now on account 3 that night ("clicking 'use
// now' also didnt work"); #459 made the press take, and the install that shipped it restarted the daemon, which dropped
// the pin: a pin lived in the pool's memory alone, and on the beta channel the daemon restarts through the day.
//
// The goal: a pin Use now sets, for the command or for one session, is still set after the daemon restarts, and the
// next turn takes that login as it did before; Unpin outlives the restart the same way. When the pinned login cannot
// serve for a limit it already read at the press, the pin stays and the pool says why and where the turns go instead: a
// login held for every model names its window and when it serves again, and one held for one model's week names that
// model and until when. A limit the login reads after the press lets the pin go (UseNowPinLetsGoTest, Nov 3, 2026). Driven through
// the switch route the console posts to, with each daemon's pool built from the same state folder, as a restart does,
// and read through the pool's one JSON projection (/api/auth's account_pool).
package splice.app.head

import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.AccountHead
import splice.accounts.AccountHeadResolver
import splice.accounts.order.ACCOUNT_ORDER_FILE
import splice.accounts.order.ACCOUNT_PINS_FILE
import splice.accounts.order.AccountOrderStore
import splice.accounts.order.AccountPinStore
import splice.accounts.pool.AccountPoolJson
import splice.accounts.pool.HeadAccountPinSource
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.pool.SwitchRoute
import splice.accounts.signin.HeadRestart
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.AccountBlock
import splice.core.failure.OwnerStep
import splice.core.usage.PlanLimit
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.ElapsedClock
import splice.core.util.LocalTimeText
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.upstream.credentials.AccountCredentialIdentitySource
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialEvidence
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialPresence
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.credentials.Selection
import splice.upstream.retry.FileProviderHoldStore
import splice.upstream.retry.ProviderHold
import splice.upstream.retry.RateLimitCooldown
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private const val HEAD = "claude-splice"
private const val FABLE = "claude-fable-5-1"
private const val OPUS = "claude-opus-5-5"
private const val SESSION = "one-command"

class UseNowPinRestartTest {
    @TempDir
    lateinit var state: Path

    private val now = System.currentTimeMillis() / 1_000
    private val fableReset = now + 5 * 86_400
    private val fiveHourReset = now + 3 * 3_600

    /** One daemon's run: its pool and the account source the control routes read, built from [state] as a daemon
     *  builds them at boot, so a second one over the same folder is the daemon after a restart. */
    private inner class Daemon(
        private val readings: Map<String, QuotaSnapshot> = emptyMap(),
        private val expired: Set<String> = emptySet(),
    ) {
        private val accounts = listOf("eight", "two", "three").map { label ->
            PoolAccount(
                label = label,
                primary = label == "eight",
                auth = if (label in expired) ExpiredLogin() else PinLogin(label),
                quota = AccountQuotaSource { readings[label] },
                cooldown = RateLimitCooldown(ElapsedClock { 0L }, store = holdFile(label)),
            )
        }
        val pool = AccountPool(accounts, WallClock { System.currentTimeMillis() })
        val source: HeadAccountPoolSource = requireNotNull(
            HeadAccountPools().source(
                pool,
                HEAD,
                AccountOrderStore(state.resolve(ACCOUNT_ORDER_FILE)),
                AccountPinStore(state.resolve(ACCOUNT_PINS_FILE)),
            ),
        )

        /** /api/auth's account_pool for this head, as the console reads it. */
        fun accountPool(session: String? = null): JsonObject =
            buildJsonObject { AccountPoolJson().write(this, source.view(session)) }.getValue("account_pool").jsonObject

        fun chosen(model: String, session: String = SESSION): String =
            when (val selection = pool.select(session, emptySet(), null, model)) {
                is Selection.Chosen -> selection.account.account.label.also {
                    selection.account.releaseCredentialProbe()
                }
                is Selection.Exhausted -> "none"
            }

        fun ApplicationTestBuilder.routes() {
            val head = AccountHead(
                key = HEAD,
                auth = object : AuthProvider {
                    override suspend fun credentials() = null
                    override suspend fun describe() = AuthDescription(true, "claude-oauth", emptyMap())
                },
                restart = HeadRestart {},
                pool = source,
            )
            val switch = SwitchRoute(AccountHeadResolver { _, _ -> head })
            application {
                routing {
                    post("/api/auth/{head}/switch") { switch.switchAccount(call) }
                    delete("/api/auth/{head}/switch") { switch.unpinAccount(call) }
                }
            }
        }
    }

    /** The command's saved order puts eight first, so the pool's own policy is known: eight serves unless a pin or
     *  a hold says otherwise. */
    @BeforeEach
    fun order() = AccountOrderStore(state.resolve(ACCOUNT_ORDER_FILE)).set(HEAD, listOf("eight", "two", "three"))

    private fun holdFile(label: String) =
        FileProviderHoldStore(state.resolve("$HEAD-$label-provider-hold.json"), LogSink {})

    /** What the first daemon left in [label]'s hold file when the provider refused it. */
    private fun providerHeld(label: String, hold: ProviderHold) = holdFile(label).save(hold)

    private fun pinned(body: String, readings: Map<String, QuotaSnapshot> = emptyMap()) = testApplication {
        val daemon = Daemon(readings)
        with(daemon) { routes() }
        val response = client.post("/api/auth/$HEAD/switch") { setBody(body) }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    /** A team seat's start pins its session through the pool (PoolAccountPins), past the console's 409 for a login
     *  held for every model: the one way a pin is set over such a hold. */
    private fun seatPinned(label: String) = assertTrue((Daemon().source as HeadAccountPinSource).pin(label, SESSION))

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Test
    fun `a Use now pin outlives a daemon restart and the next turn takes it`() {
        pinned("""{"label":"three"}""")

        val after = Daemon()

        assertEquals("three", after.accountPool().text("pinned_label"), "the console still shows the pin")
        assertEquals("three", after.accountPool().text("next_target_label"))
        assertEquals("three", after.chosen(OPUS), "the next turn takes the pinned login")
        assertNull(after.accountPool().text("pin_refusal"), "a pin that serves needs no reason")
    }

    @Test
    fun `Unpin outlives a daemon restart`() = testApplication {
        val before = Daemon()
        with(before) { routes() }
        client.post("/api/auth/$HEAD/switch") { setBody("""{"label":"three"}""") }
        assertEquals(HttpStatusCode.OK, client.delete("/api/auth/$HEAD/switch").status)

        val after = Daemon()

        assertNull(after.accountPool().text("pinned_label"))
        assertEquals("eight", after.chosen(OPUS), "the pool's own policy serves again")
    }

    @Test
    fun `one session's pin outlives a daemon restart and stays that session's alone`() {
        pinned("""{"label":"two","session":"$SESSION"}""")

        val after = Daemon()

        assertEquals("two", after.accountPool(SESSION).text("pinned_label"))
        assertEquals("two", after.chosen(OPUS, SESSION), "the pinned session takes its login")
        assertNull(after.accountPool().text("pinned_label"), "the command as a whole is not pinned")
        assertEquals("eight", after.chosen(OPUS, "another-session"), "another session keeps the pool's policy")
    }

    @Test
    fun `a seat pinned to a login its provider holds for every model keeps its pin and says why`() {
        providerHeld("three", ProviderHold(null, PlanLimit("five_hour", fiveHourReset)))
        seatPinned("three")

        val after = Daemon()
        val turn = after.chosen(OPUS)
        val pool = after.accountPool(SESSION)

        assertNotEquals("three", turn, "a login held for every model serves no turn")
        assertEquals("three", pool.text("pinned_label"), "the pin is kept, not dropped")
        val why = pool.text("pin_refusal").orEmpty()
        assertTrue("5-hour plan limit reached" in why, why)
        assertTrue(LocalTimeText().at(fiveHourReset) in why, "it says when the login serves again: $why")
        assertTrue("'$turn'" in why, "it names the login the turns use instead: $why")
    }

    @Test
    fun `a login pinned while held for Fable's week keeps its pin, Opus takes it, and the pool names the Fable hold`() {
        val fableWeek = PlanLimit("seven_day_overage_included", fableReset, model = FABLE)
        providerHeld("two", ProviderHold(null, null, listOf(fableWeek)))
        pinned("""{"label":"two"}""")

        val after = Daemon()
        val pool = after.accountPool()

        assertEquals("two", pool.text("pinned_label"))
        assertEquals("two", after.chosen(OPUS), "Opus still takes the pinned login")
        assertNotEquals("two", after.chosen(FABLE, "a-fable-session"), "a Fable turn goes where Fable is served")
        assertNull(pool.text("pin_refusal"), "the login serves turns, so the pin is not refused")
        val held = pool["pin_models_held"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        assertEquals(listOf(FABLE), held.map { it.text("model") })
        assertEquals(listOf(fableReset), held.map { it["until_epoch_seconds"]?.jsonPrimitive?.long })
    }

    @Test
    fun `a login pinned while its usage reads full and another has room keeps its pin and says why`() {
        val full = QuotaSnapshot(fiveHour = QuotaWindow(100.0, fiveHourReset, 18_000))
        pinned("""{"label":"three"}""", mapOf("three" to full))

        val after = Daemon(mapOf("three" to full))
        val turn = after.chosen(OPUS)
        val pool = after.accountPool()

        assertNotEquals("three", turn, "the pool spends a login with room before one that reads full")
        assertEquals("three", pool.text("pinned_label"), "the pin is kept, not dropped")
        val why = pool.text("pin_refusal").orEmpty()
        assertTrue("5-hour" in why && "100%" in why, "it names the full window: $why")
        assertTrue(LocalTimeText().at(fiveHourReset) in why, "it says when the window resets: $why")
        assertTrue("'$turn'" in why, "it names the login the turns use instead: $why")
    }

    @Test
    fun `a pinned login its provider refused says to sign in to it again, not when a retry comes`() {
        pinned("""{"label":"three"}""")
        val after = Daemon()
        refuse(after, block = null)

        val turn = after.chosen(OPUS)
        val why = after.accountPool().text("pin_refusal").orEmpty()

        assertNotEquals("three", turn)
        assertTrue("sign in to it again" in why, why)
        assertFalse("held until" in why, "a refused credential has no reset to wait for: $why")
        assertTrue("'$turn'" in why, why)
    }

    @Test
    fun `a pinned login blocked until its owner accepts new terms names that step`() {
        pinned("""{"label":"three"}""")
        val after = Daemon()
        refuse(after, AccountBlock(OwnerStep.ACCEPT_TERMS, "claude.ai"))

        val turn = after.chosen(OPUS)
        val why = after.accountPool().text("pin_refusal").orEmpty()

        assertNotEquals("three", turn)
        assertTrue("owner accepts the provider's updated terms" in why && "claude.ai" in why, why)
        assertFalse("held until" in why, "no wait clears a block: $why")
        assertFalse("sign in" in why, "no sign-in clears a block: $why")
    }

    @Test
    fun `a pinned login whose token expired, which its own client renews, says so and asks for no sign-in`() {
        pinned("""{"label":"three"}""")

        val after = Daemon(expired = setOf("three"))
        val turn = after.chosen(OPUS)
        val pool = after.accountPool()

        assertNotEquals("three", turn, "an expired token sends no turn")
        assertEquals("three", pool.text("pinned_label"), "the pin is kept: the login serves again once renewed")
        val why = pool.text("pin_refusal").orEmpty()
        assertTrue("token expired" in why, why)
        assertFalse("sign in" in why, "nobody needs to sign in again: $why")
        assertFalse("no credential file" in why, "the file is there: $why")
        assertTrue("'$turn'" in why, why)
    }

    @Test
    fun `a seat pinned to a login held like every other, whose hold ends first, still says why it serves no turn`() {
        val later = PlanLimit("five_hour", fiveHourReset + 3_600)
        providerHeld("eight", ProviderHold(null, later))
        providerHeld("two", ProviderHold(null, later))
        providerHeld("three", ProviderHold(null, PlanLimit("five_hour", fiveHourReset)))
        seatPinned("three")

        val pool = Daemon().accountPool(SESSION)

        assertEquals("three", pool.text("next_target_label"), "no login serves, and the pin's hold ends first")
        val why = pool.text("pin_refusal").orEmpty()
        assertTrue("5-hour plan limit reached" in why && LocalTimeText().at(fiveHourReset) in why, why)
        assertFalse("Turns use" in why, "no other login serves meanwhile: $why")
    }

    @Test
    fun `a pin the disk refuses to save is not shown as set`() {
        val daemon = Daemon()
        val pins = daemon.source as HeadAccountPinSource
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("r-x------"))
        try {
            assertThrows(IOException::class.java) { pins.pin("three", null) }
        } finally {
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"))
        }

        assertNull(daemon.accountPool().text("pinned_label"), "the console shows no pin the next restart would drop")
        assertEquals("eight", daemon.chosen(OPUS), "the pool's own policy serves")
    }

    /** The provider refuses the pinned login on its next turn: a 401, or a block until its owner acts. */
    private fun refuse(daemon: Daemon, block: AccountBlock?) {
        val first = daemon.pool.select(SESSION, emptySet(), null, OPUS) as Selection.Chosen
        assertEquals("three", first.account.account.label, "the pinned login takes the turn")
        first.account.markCredentialUnavailable(block)
    }
}

private class PinLogin(private val token: String) : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer(token)
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
}

/** A native login whose access token lapsed beside its refresh token: it cannot send, and its own client renews it the
 *  next time it runs, so nobody signs in again. */
private class ExpiredLogin : RefreshableAuthProvider, AccountCredentialIdentitySource {
    override suspend fun credentials(): Credentials? = null
    override suspend fun refresh(): Credentials? = null
    override suspend fun describe(): AuthDescription = AuthDescription(false, "synthetic")
    override fun credentialIdentity(): CredentialFileIdentity? = null
    override fun credentialEvidence(): CredentialEvidence =
        CredentialEvidence(null, CredentialPresence.MISSING, renewable = true)
}
