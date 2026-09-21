// NEW (V4-109): the three ARCH-AUDIT config-honesty fixes, pinned where they live.
//
// Each cell is a claim the row had to make TRUE rather than merely assert, and each was RED before
// its fix — the mutation is named in the cell so the next reader can see the old code fail:
//   (1) usageWarnPct: 0 used to fall BACK to 80 (`if (warnPct > 0) warnPct else DEFAULT_WARN_PCT`),
//       so an operator asking for silence got warnings, and the shadowed constant it fell back to
//       was a second declaration of Knob.USAGE_WARN_PCT's own default.
//   (2) portCollisions(): the control plane was not a participant, so a head declaring the port the
//       daemon itself binds was reported clean and the two fought over the socket at start.
//   (3) coerceAll: an unknown key or an uncoercible value was DROPPED IN SILENCE — the knob kept
//       its default and nothing anywhere said the operator's line had not been read.
package splice.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.usage.RateLimitState
import splice.core.usage.UsageWarnPolicy
import java.nio.file.Path

class ConfigHonestyPinsTest {

    /** A plan window 90% consumed: above any sane warn threshold, below the 98% critical edge. */
    private val ninetyPct = RateLimitState(limitTokens = 1000, remainingTokens = 100, resetTokens = "6m0s")

    /** One head at [port], no `control_port` declared — so the daemon's own port is the knob
     *  default, which is the case the old check could not see. */
    private fun oneHeadOn(port: Int) = Topology(
        providers = mapOf(
            "openrouter" to ProviderConfig(
                dialect = Dialect.OPENAI_CHAT,
                baseUrl = "https://openrouter.example/api/v1",
                auth = AuthConfig("api-key", env = "OPENROUTER_API_KEY"),
                models = listOf(ModelEntry("m", contextWindow = 200_000)),
            ),
        ),
        heads = mapOf(
            "a" to HeadConfig(
                provider = "openrouter",
                port = port,
                discoveryPrefix = "claude-openrouter--",
                pinnedModel = "m",
                claude = ClaudeWrapperConfig(command = "claude-a"),
            ),
        ),
    )

    @Test
    fun `usageWarnPct 0 disables the warn tier instead of falling back to a default - V4-109`() {
        // MUTATION: restore `val pctThreshold = if (warnPct > 0) warnPct else DEFAULT_WARN_PCT` and
        // this goes red — 90% of the window is over 80 and the level comes back "warn".
        assertEquals("warn", UsageWarnPolicy.computeUsageWarn(ratelimit = ninetyPct, warnPct = 80).level)
        assertEquals(
            "ok",
            UsageWarnPolicy.computeUsageWarn(ratelimit = ninetyPct, warnPct = 0).level,
            "0 is an instruction to stay quiet, not a missing value to be defaulted",
        )
        // The 5h-token tier reads the same threshold, so it must honour the same 0.
        assertEquals("ok", UsageWarnPolicy.computeUsageWarn(outputTokens5h = 85, warnTokens5h = 100, warnPct = 0).level)
    }

    @Test
    fun `disabling the warn tier does not disable critical - V4-109 bound`() {
        // critical is the hard edge (remaining <= 0, or >=98% used), not a percentage preference —
        // an operator who silences warnings has not asked to be surprised by a spent window.
        assertEquals(
            "critical",
            UsageWarnPolicy.computeUsageWarn(
                ratelimit = RateLimitState(limitTokens = 1000, remainingTokens = 0, resetTokens = null),
                warnPct = 0,
            ).level,
        )
    }

    @Test
    fun `the default warn threshold is the knob's, not a local copy - V4-109`() {
        // Reads Knob.USAGE_WARN_PCT.default rather than restating 80, so const-single-source's
        // KNOB-SHADOW stays clear and the two cannot drift.
        val knobDefault = (Knob.USAGE_WARN_PCT.default as Long).toInt()
        assertEquals(
            "warn",
            UsageWarnPolicy.computeUsageWarn(ratelimit = ninetyPct).level,
            "the no-argument default must still warn at the knob's own threshold ($knobDefault)",
        )
    }

    @Test
    fun `portCollisions counts the control plane as a listener - V4-109`() {
        // MUTATION: drop the `controlPort to CONTROL_PLANE_OWNER` term and this returns empty.
        val topology = oneHeadOn(port = 3096)
        // The ORDER is pinned too: heads in declaration order, the control plane appended, because
        // the collision message renders this list and the operator reads it top to bottom.
        assertEquals(
            mapOf(3096 to listOf("a", "daemon.controlPort")),
            topology.portCollisions(),
            "a head on the daemon's own default control port is a collision the operator must see",
        )
    }

    @Test
    fun `a declared control port is the one folded in, and heads elsewhere stay clean - V4-109`() {
        val quiet = oneHeadOn(port = 3200)
        assertEquals(emptyMap<Int, List<String>>(), quiet.portCollisions())
    }

    @Test
    fun `coerceRejects names the key, where it was written, and why - V4-109`(@TempDir tmp: Path) {
        // MUTATION: revert coerceAll to `mapNotNull { ... }` and both entries vanish — the operators
        // line was discarded without a word.
        val service = ConfigService(
            statePaths = StatePaths(baseOverride = tmp.resolve("state")),
            headOverrides = mapOf("notAKnob" to "1", "port" to "not-a-number", "port2" to "3100"),
            perHeadOverrides = mapOf("claudex" to mapOf("alsoNotAKnob" to "x")),
            envReader = { null },
        )
        val rejects = service.coerceRejects()
        assertEquals("unknown key", rejects["notAKnob"])
        assertTrue(rejects["port"]?.isNotEmpty() == true, "a value that will not coerce must say so: $rejects")
        assertEquals("unknown key", rejects["heads.claudex.alsoNotAKnob"])
    }

    @Test
    fun `a layer with nothing wrong reports nothing - V4-109 guard`(@TempDir tmp: Path) {
        val service = ConfigService(
            statePaths = StatePaths(baseOverride = tmp.resolve("state")),
            headOverrides = mapOf("port" to "3100"),
            envReader = { null },
        )
        assertEquals(emptyMap<String, String>(), service.coerceRejects())
    }
}
