// PORT-OF: app/src/test/kotlin/splice/app/ExampleConfigTest.kt (the three DR-96 arms) — direct check()
// calls that never read the shipped example, so they moved with the preflight to its own module.
package splice.topology

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TomlStructurePreflightTest {
    // DR-96 (sweep-2): the reopen guard never REGISTERED the first table of a header-first file —
    // bounds[0]==0 keeps sectionStart 0 through the first real section, and the preamble-skip
    // gate (sectionStart > 0) swallowed the registration, so ONE exact reopen of the first table
    // passed preflight and ktoml merged both bodies (the DR-44-redo scar, resurrected for the
    // offset-0 table). Direct check() calls: the loader call site is pinned by the DR-44 arm.
    @Test
    fun `reopening the FIRST table of a header-first config fails loud - DR-96`() {
        val doctored = "[daemon]\ncontrol_port = 4400\n\n[providers.x]\ndialect = \"openai-chat\"\n\n" +
            "[daemon]\ncontrol_port = 4401\n"
        val thrown = assertThrows(IllegalArgumentException::class.java) { TomlStructurePreflight.check(doctored) }
        assertTrue(thrown.message!!.contains("defined twice"), thrown.message)
        assertTrue(thrown.message!!.contains("[daemon]"), thrown.message)
    }

    @Test
    fun `a legal header-first config still passes preflight - DR-96 control`() {
        TomlStructurePreflight.check("[daemon]\ncontrol_port = 4400\n\n[providers.x]\ndialect = \"openai-chat\"\n")
    }

    @Test
    fun `a preamble config still rejects a reopened non-first table - DR-96 control`() {
        val doctored = "# preamble comment\ntitle = \"x\"\n\n[daemon]\ncontrol_port = 4400\n\n" +
            "[providers.x]\ndialect = \"openai-chat\"\n\n[daemon]\ncontrol_port = 4401\n"
        val thrown = assertThrows(IllegalArgumentException::class.java) { TomlStructurePreflight.check(doctored) }
        assertTrue(thrown.message!!.contains("defined twice"), thrown.message)
    }
}
