package splice.core.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.topology.ServerState
import java.time.Instant
class UpstreamAvailabilityTest {
    private val now = Instant.parse("2026-11-24T17:00:00Z")

    @Test
    fun `plain text trims and ignores case and normalizes the time offset`() {
        val expected = mapOf(
            " UP\n" to UpstreamState.Up,
            " StArTiNg " to UpstreamState.Starting,
            "oFF" to UpstreamState.Off(),
            " OFF until 2026-11-24t12:00:00-05:00 " to UpstreamState.Off(now),
        )
        expected.forEach { (text, state) -> assertEquals(UpstreamSignal(state), UpstreamSignalParser.parse(text)) }
    }

    @Test
    fun `JSON accepts the three states and an optional time and message`() {
        assertEquals(UpstreamSignal(UpstreamState.Up), UpstreamSignalParser.parse("""{"state":"up"}"""))
        assertEquals(
            UpstreamSignal(UpstreamState.Starting, "Warming."),
            UpstreamSignalParser.parse("""{"state":"starting","message":"Warming."}"""),
        )
        assertEquals(
            UpstreamSignal(UpstreamState.Off(now), "Come back soon."),
            UpstreamSignalParser.parse(
                """{"state":"off","until":"2026-11-24T17:00:00Z","message":"Come back soon."}""",
            ),
        )
        assertEquals(UpstreamSignal(UpstreamState.Off()), UpstreamSignalParser.parse("""{"state":"off"}"""))
    }

    @Test
    fun `unknown states and malformed timestamps are not new readings`() {
        val invalid = listOf(
            "", "up until tomorrow", "off until tomorrow", "off until 2026-11-24T17:00Z",
            "off until 2026-11-31T17:00:00Z", """{"state":"down"}""", """{"state":"up" """,
            """{"state":"off","until":"tomorrow"}""", """{"state":"starting","message":["warming"]}""",
            """{"state":123}""",
        )
        invalid.forEach { assertNull(UpstreamSignalParser.parse(it), it) }
    }

    @Test
    fun `refusals name the configured time and round a future retry up to at least one second`() {
        val policy = UpstreamStatePolicy(ServerState(messageOff = "Back at {until}", retryAfterStarting = 17))
        val timed = UpstreamSignal(UpstreamState.Off(now))
        assertEquals(UpstreamRefusal("Back at $now", 1), policy.refusal(timed, now.minusNanos(1)))
        assertEquals(UpstreamRefusal("Back at $now", 2), policy.refusal(timed, now.minusMillis(1_001)))
        assertEquals(UpstreamRefusal("Back at $now", 17), policy.refusal(timed, now))
        assertEquals(UpstreamRefusal("Back at $now", 17), policy.refusal(timed, now.plusSeconds(1)))
        val untimed = UpstreamSignal(UpstreamState.Off(), "Maintenance.")
        assertEquals(UpstreamRefusal("Maintenance.", 17), policy.refusal(untimed, now))
        assertEquals(503, policy.refusal(untimed, now)?.status)
    }

    @Test
    fun `a signal message replaces configured words and up admits without a retry delay`() {
        val policy = UpstreamStatePolicy(ServerState(messageStarting = "Booting the box.", retryAfterStarting = 0))
        val starting = UpstreamSignal(UpstreamState.Starting)
        assertEquals(UpstreamRefusal("Booting the box.", 0), policy.refusal(starting, now))
        assertEquals(UpstreamRefusal("Warming.", 0), policy.refusal(starting.copy(message = "Warming."), now))
        assertNull(policy.refusal(UpstreamSignal(UpstreamState.Up, "Ready"), now))
    }
}
