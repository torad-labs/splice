// NEW: a Use now pin lets go at its login's first limit reading (Marcos, Nov 3, 2026, through Simon: "when account-3
// hit its limit at 5:16 PM CT, the #463 Use-now pin pulled seats back to account-3 for ~6 minutes before they settled
// on account-8. Please make the pin let go at the first limit reading instead of pulling seats back").
//
// That evening Anthropic refused Fable on account-3 for the week (seven_day_overage_included, Fable alone) at 5:16 PM
// CT. The pin stayed and account-3 still served Opus, so at 5:22 PM CT a short 429 on account-8 sent a seat back to
// account-3 ("operator pinned this account") until a usage reading showed its week full at 5:28 PM CT.
//
// The goal: a pin lets go the moment the login it names reads a limit it did not read when Use now was pressed: the
// provider naming a plan window spent (the whole login's, or one model's week), the provider holding the account, or
// a usage reading at 100%. The console shows no pin, a restart does not bring it back, and turns go where the pool's
// own policy sends them. A limit the login already read at the press keeps the pin (Use now on a login refused Fable
// alone still serves Opus, Nov 3), and so does a short rate limit, which a turn waits out on the same login. Driven
// through the switch route the console posts to, over the real pool, the projection the daemon builds and the pin file
// a restart reads.
package splice.app.head

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.auth.SavedPins
import splice.core.usage.PlanLimit
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.credentials.Selection
import splice.upstream.retry.FileProviderHoldStore
import splice.upstream.retry.RateLimitCooldown
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private const val HEAD = "claude-splice"
private const val FABLE = "claude-fable-5-1"
private const val OPUS = "claude-opus-5-5"
private const val SEAT = "a-seat"
private val LABELS = listOf("eight", "three", "two")

/** Generated runs, and the events in each, for the property below. */
private const val RUNS = 200
private const val STEPS = 14

class UseNowPinLetsGoTest {
    @TempDir
    lateinit var state: Path

    private val now = System.currentTimeMillis() / 1_000
    private val fableReset = now + 5 * 86_400
    private val weekReset = now + 2 * 86_400
    private val fiveHourReset = now + 3 * 3_600

    /** What each login's usage reads and whether its provider holds the account: the world outside the daemon, which
     *  a restart does not change. */
    private val readings = ConcurrentHashMap<String, QuotaSnapshot>()
    private val heldAccounts = ConcurrentHashMap.newKeySet<String>()

    /** One daemon's run over [state]: a second one is the daemon after a restart. */
    private inner class Daemon {
        val accounts = LABELS.associateWith { label ->
            PoolAccount(
                label = label,
                primary = label == "eight",
                auth = LetGoLogin(label),
                quota = object : AccountQuotaSource {
                    override fun snapshot(): QuotaSnapshot? = readings[label]
                    override val held: Boolean get() = label in heldAccounts
                },
                cooldown = RateLimitCooldown(
                    ElapsedClock { 0L },
                    store = FileProviderHoldStore(state.resolve("$HEAD-$label-provider-hold.json"), LogSink {}),
                ),
            )
        }
        val pool = AccountPool(accounts.values.toList(), WallClock { System.currentTimeMillis() })
        val source: HeadAccountPoolSource = requireNotNull(
            HeadAccountPools().source(
                pool,
                HEAD,
                AccountOrderStore(state.resolve(ACCOUNT_ORDER_FILE)),
                AccountPinStore(state.resolve(ACCOUNT_PINS_FILE)),
            ),
        )

        /** The pin the console shows for the command, or for one [session] (/api/auth's account_pool). */
        fun shownPin(session: String? = null): String? {
            val pool = buildJsonObject { AccountPoolJson().write(this, source.view(session)) }
                .getValue("account_pool").jsonObject
            return (pool["pinned_label"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

        fun chosen(model: String, session: String = SEAT): String =
            when (val selection = pool.select(session, emptySet(), null, model)) {
                is Selection.Chosen -> selection.account.account.label.also {
                    selection.account.releaseCredentialProbe()
                }
                is Selection.Exhausted -> "none"
            }

        /** Use now, as the console presses it. */
        fun useNow(label: String) = testApplication {
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
            application { routing { post("/api/auth/{head}/switch") { switch.switchAccount(call) } } }
            val response = client.post("/api/auth/$HEAD/switch") { setBody("""{"label":"$label"}""") }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        fun refuseFableWeek(label: String) = accounts.getValue(label).cooldown.planHold
            .hold(PlanLimit("seven_day_overage_included", fableReset, model = FABLE)) {}
    }

    /** The pin a restart reads back. */
    private fun savedPins(): SavedPins = AccountPinStore(state.resolve(ACCOUNT_PINS_FILE)).pins(HEAD)

    /** The command's saved order puts eight first, so the pool's own policy is known: eight serves unless a pin or a
     *  hold says otherwise. */
    @BeforeEach
    fun order() = AccountOrderStore(state.resolve(ACCOUNT_ORDER_FILE)).set(HEAD, LABELS)

    @Test
    fun `a pin lets go at its login's first Fable-week refusal and the next turn follows the policy`() {
        val daemon = Daemon()
        daemon.useNow("three")
        assertEquals("three", daemon.chosen(OPUS), "the pinned login serves while it has room")

        daemon.refuseFableWeek("three")

        assertNull(daemon.shownPin(), "the console shows no pin once its login read a limit")
        assertEquals(SavedPins(), savedPins(), "a restart does not bring the pin back")
        assertEquals("eight", daemon.chosen(OPUS, "another-seat"), "a turn goes where the policy sends it")
        assertNull(Daemon().shownPin(), "after a restart there is still no pin")
    }

    @Test
    fun `a pin lets go at its login's first full usage reading`() {
        val daemon = Daemon()
        daemon.useNow("three")

        readings["three"] = QuotaSnapshot(sevenDay = QuotaWindow(100.0, weekReset, 604_800))

        assertNull(daemon.shownPin())
        assertEquals(SavedPins(), savedPins())
        assertEquals("eight", daemon.chosen(OPUS))
    }

    @Test
    fun `a pin lets go when its login's provider holds the account or names its five hours spent`() {
        val held = Daemon()
        held.useNow("three")
        heldAccounts += "three"
        assertNull(held.shownPin(), "a provider hold on the account is a limit reading")

        heldAccounts.clear()
        val spent = Daemon()
        spent.useNow("two")
        spent.accounts.getValue("two").cooldown.planHold.hold(PlanLimit("five_hour", fiveHourReset)) {}
        assertNull(spent.shownPin(), "a plan window spent for the whole login is a limit reading")
        assertEquals(SavedPins(), savedPins())
    }

    @Test
    fun `a pin set over a Fable-week refusal stays, and lets go at the login's next limit reading`() {
        val daemon = Daemon()
        daemon.refuseFableWeek("three")
        daemon.useNow("three")

        assertEquals("three", daemon.chosen(OPUS), "Opus takes the login pinned over its Fable week")
        assertEquals("three", daemon.shownPin(), "a limit the login read at the press keeps the pin")
        assertEquals("three", Daemon().shownPin(), "and so does a restart over it")

        readings["three"] = QuotaSnapshot(fiveHour = QuotaWindow(100.0, fiveHourReset, 18_000))

        assertNull(daemon.shownPin(), "a new limit reading lets the pin go")
        assertEquals(SavedPins(), savedPins())
    }

    @Test
    fun `a short rate limit on the pinned login keeps the pin`() {
        val daemon = Daemon()
        daemon.useNow("three")

        daemon.accounts.getValue("three").cooldown.markUnavailable(20_000)

        assertEquals("three", daemon.shownPin())
        assertEquals("three", savedPins().head)
    }

    @Test
    fun `one session's pin lets go at its login's first limit reading`() {
        val daemon = Daemon()
        (daemon.source as HeadAccountPinSource).pin("two", SEAT)
        assertEquals("two", daemon.chosen(OPUS))

        daemon.refuseFableWeek("two")

        assertNull(daemon.shownPin(SEAT), "the console shows the session no pin once its login read a limit")
        assertEquals(SavedPins(), savedPins(), "the session's saved pin lets go too")
    }

    /** The events of the property's runs: what each does to the daemon's world, and the limits it adds or clears in
     *  the model the goal is checked against. */
    private val events = listOf(
        Event("press", presses = true) { world, label, _ ->
            (world.daemon.source as HeadAccountPinSource).pin(label, null)
            world.pin = label
        },
        Event("Fable week") { world, label, _ ->
            world.daemon.refuseFableWeek(label)
            world.add(label, "fable")
        },
        Event("whole login spent") { world, label, _ ->
            world.daemon.accounts.getValue(label).cooldown.planHold.hold(PlanLimit("five_hour", fiveHourReset)) {}
            world.add(label, "login")
        },
        Event("full reading") { world, label, _ ->
            readings[label] = QuotaSnapshot(sevenDay = QuotaWindow(100.0, weekReset, 604_800))
            world.add(label, "full")
        },
        Event("reading with room") { world, label, _ ->
            readings[label] = QuotaSnapshot(sevenDay = QuotaWindow(40.0, weekReset, 604_800))
            world.remove(label, "full")
        },
        Event("provider holds the account") { world, label, _ ->
            heldAccounts += label
            world.add(label, "held")
        },
        Event("provider frees the account") { world, label, _ ->
            heldAccounts -= label
            world.remove(label, "held")
        },
        Event("short rate limit") { world, label, _ ->
            world.daemon.accounts.getValue(label).cooldown.markUnavailable(20_000)
        },
        Event("turn") { world, _, random ->
            world.daemon.chosen(if (random.nextBoolean()) OPUS else FABLE)
        },
        Event("restart") { world, _, _ ->
            world.daemon = Daemon()
        },
    )

    /** Over any run of presses, limit readings, readings that clear, short rate limits, turns and restarts, the pin
     *  lets go exactly at a reading its login did not read just before, and the console and the saved file agree. */
    @Test
    fun `over any run of events, a pin lets go exactly when its login reads a new limit`() {
        repeat(RUNS) { run ->
            val seed = run.toLong()
            val random = Random(seed)
            readings.clear()
            heldAccounts.clear()
            state.toFile().listFiles()?.filterNot { it.name == ACCOUNT_ORDER_FILE }?.forEach { it.delete() }
            val world = PinWorld()
            repeat(STEPS) { step ->
                val label = LABELS[random.nextInt(LABELS.size)]
                val event = events[random.nextInt(events.size)]
                world.happen(event, label, random)
                val where = "seed $seed, step $step, ${event.name} on $label"
                assertEquals(world.pin, world.daemon.shownPin(), "the console's pin: $where")
                assertEquals(world.pin, savedPins().head, "the saved pin: $where")
            }
        }
    }

    /** One event of the property's runs. [presses] marks Use now, which sets the pin rather than reading a limit. */
    private data class Event(val name: String, val presses: Boolean = false, val act: EventAct)

    private fun interface EventAct {
        fun on(world: PinWorld, label: String, random: Random)
    }

    /** The property's world: the daemon of the moment, the pin the goal says it holds, and the limits each login reads
     *  as the goal names them. */
    private inner class PinWorld {
        var daemon = Daemon()
        var pin: String? = null
        private val limits = mutableMapOf<String, MutableSet<String>>()

        /** Runs [event] on [label], and lets the expected pin go when its login now reads a limit it did not before. */
        fun happen(event: Event, label: String, random: Random) {
            val before = pin?.let(::limitsOf).orEmpty()
            event.act.on(this, label, random)
            val after = pin?.let(::limitsOf).orEmpty()
            if (!event.presses && (after - before).isNotEmpty()) pin = null
        }

        fun add(label: String, limit: String) {
            limits.getOrPut(label) { mutableSetOf() } += limit
        }

        fun remove(label: String, limit: String) {
            limits[label]?.remove(limit)
        }

        private fun limitsOf(label: String): Set<String> = limits[label].orEmpty().toSet()
    }
}

private class LetGoLogin(private val token: String) : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer(token)
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
}
