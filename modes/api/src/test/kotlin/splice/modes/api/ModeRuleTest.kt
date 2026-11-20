package splice.modes.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.mode.ModeSetting

/** The one on/off rule, in the order the README gives it. */
class ModeRuleTest {
    private val never = ModeState(false, ModeFrom.NEVER)

    private fun manifest(on: Boolean, only: List<String> = emptyList(), off: List<String> = emptyList()) = ModeManifest(
        "echo",
        "Says back.",
        "echo",
        "splice.modes.echo.EchoMode",
        listOf("say_back"),
        ModeKind.DOOR,
        ModeOffer(on, only, off, listOf(ModeCaller.DESKTOP)),
    )

    @Test
    fun `a head outside only or on off is never, whatever splice toml says`() {
        val setting = ModeSetting(on = true, heads = mapOf("kimi" to true, "astra" to true))
        assertEquals(never, ModeRule.forHead(manifest(true, off = listOf("kimi")), setting, "kimi"))
        assertEquals(never, ModeRule.forHead(manifest(true, only = listOf("grok")), setting, "astra"))
    }

    @Test
    fun `the head's own entry wins, then splice toml's on, then the manifest's`() {
        val m = manifest(false)
        val headOn = ModeSetting(on = false, heads = mapOf("a" to true))
        assertEquals(ModeState(true, ModeFrom.HEAD), ModeRule.forHead(m, headOn, "a"))
        assertEquals(ModeState(true, ModeFrom.SPLICE_TOML), ModeRule.forHead(m, ModeSetting(on = true), "a"))
        assertEquals(ModeState(false, ModeFrom.MANIFEST), ModeRule.forHead(m, null, "a"))
        val headOff = ModeSetting(on = true, heads = mapOf("a" to false))
        assertEquals(ModeState(true, ModeFrom.SPLICE_TOML), ModeRule.everyHead(m, headOff))
    }

    @Test
    fun `a caller on callers off is never offered the mode`() {
        assertEquals(false, ModeRule.offeredTo(manifest(true), ModeCaller.DESKTOP))
        assertEquals(true, ModeRule.offeredTo(manifest(true), ModeCaller.DOOR))
    }
}
