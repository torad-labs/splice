// 2026-08-12: /health's `ok` must mean "a turn can complete", not "heads are configured". The 91h
// wedge served {ok:true, readyHeads:4} for its whole duration because ok was a hardcoded literal.
// Pins: ok flips false ONLY for a head that is supposed to be running (F4 — a deliberately-stopped
// head must not page); stays a plain true (no stall array) when everything is live.
package splice.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.control.api.ControlPayloads
import splice.core.head.Head
import splice.core.head.HeadHealth

private class FakeHead(override val key: String, private val up: Boolean) : Head {
    override val label = key
    override val port = 0
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun healthSnapshot() = HeadHealth(ok = up, running = up, port = 0, version = "kt-1")
}

class ControlHealthTest {

    private fun managed(key: String, running: Boolean): ManagedHead = ManagedHead(
        head = FakeHead(key, running),
        auth = object : splice.core.auth.AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = splice.core.auth.AuthDescription(false, "x", emptyMap())
        },
        usage = object : HeadUsageSource {
            override fun snapshot() = UsageView(0L, 0, RateLimitView(0, 0, "0s"))
        },
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int) = ""
            override fun path() = "/tmp/x.log"
        },
        warnPct = 80,
        warnTokens5h = 0,
        authKind = "x",
    )

    private fun payloads(
        heads: Map<String, ManagedHead>,
        stalled: List<String>,
        failed: Int = 0,
    ) = ControlPayloads(
        heads = heads,
        failedHeads = { failed },
        configuredHeads = heads.size,
        turnPathStalled = { stalled },
    )

    private fun ok(json: kotlinx.serialization.json.JsonObject) =
        json["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()

    @Test
    fun `ok stays a plain true when every turn path is live`() {
        val h = Json.parseToJsonElement(
            payloads(mapOf("codex" to managed("codex", running = true)), emptyList()).controlHealthJson(),
        ).jsonObject
        assertEquals(true, ok(h))
        assertNull(h["turnPathStalled"], "no stall array on the happy path — consumers see the old shape")
    }

    @Test
    fun `a stalled RUNNING head flips ok false and names itself`() {
        val h = Json.parseToJsonElement(
            payloads(mapOf("codex" to managed("codex", running = true)), listOf("codex")).controlHealthJson(),
        ).jsonObject
        assertEquals(false, ok(h))
        assertEquals(listOf("codex"), h["turnPathStalled"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a stalled STOPPED head does NOT page - deliberate stop is not an outage`() {
        // The probe marks a deliberately-stopped head stalled (its port refuses), but an operator
        // maintenance stop must never flip global ok:false. F4. Note the OTHER head is up: what
        // makes this benign is that the daemon can still serve, not merely that a head is down.
        val h = Json.parseToJsonElement(
            payloads(
                mapOf("codex" to managed("codex", running = false), "grok" to managed("grok", running = true)),
                listOf("codex"),
            ).controlHealthJson(),
        ).jsonObject
        assertEquals(true, ok(h), "a stopped head alongside a live one is intentional, not the wedge")
        assertNull(h["turnPathStalled"])
    }

    // Review 2026-08-12: the F4 intersection alone read a CRASHED head as "not supposed to be
    // running" and discarded its stall entry, so a daemon whose heads all died on EADDRINUSE served
    // {ok:true, readyHeads:0, failedHeads:4} — green through a total outage, the very shape the ok
    // rewrite exists to kill. "Not running" is not self-certifying; only failedHeads separates a
    // crash from a deliberate stop.
    @Test
    fun `heads that FAILED to start flip ok false even though none is running`() {
        val h = Json.parseToJsonElement(
            payloads(
                mapOf("codex" to managed("codex", running = false)),
                stalled = listOf("codex"),
                failed = 1,
            ).controlHealthJson(),
        ).jsonObject
        assertEquals(false, ok(h), "every head dead on boot is an outage, not a maintenance window")
    }

    @Test
    fun `a configured daemon with nothing running cannot claim ok`() {
        // No recorded failure (the heads were stopped one by one) but zero capacity remains: a turn
        // cannot complete, so ok must not say it can.
        val h = Json.parseToJsonElement(
            payloads(mapOf("codex" to managed("codex", running = false)), emptyList()).controlHealthJson(),
        ).jsonObject
        assertEquals(false, ok(h))
    }
}
