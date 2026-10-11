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
    fun `reopening the FIRST table of a header-first config fails loud`() {
        val doctored = "[daemon]\ncontrol_port = 4400\n\n[providers.x]\ndialect = \"openai-chat\"\n\n" +
            "[daemon]\ncontrol_port = 4401\n"
        val thrown = assertThrows(IllegalArgumentException::class.java) { TomlStructurePreflight.check(doctored) }
        assertTrue(thrown.message!!.contains("defined twice"), thrown.message)
        assertTrue(thrown.message!!.contains("[daemon]"), thrown.message)
    }

    @Test
    fun `a legal header-first config still passes preflight`() {
        TomlStructurePreflight.check("[daemon]\ncontrol_port = 4400\n\n[providers.x]\ndialect = \"openai-chat\"\n")
    }

    @Test
    fun `a preamble config still rejects a reopened non-first table`() {
        val doctored = "# preamble comment\ntitle = \"x\"\n\n[daemon]\ncontrol_port = 4400\n\n" +
            "[providers.x]\ndialect = \"openai-chat\"\n\n[daemon]\ncontrol_port = 4401\n"
        val thrown = assertThrows(IllegalArgumentException::class.java) { TomlStructurePreflight.check(doctored) }
        assertTrue(thrown.message!!.contains("defined twice"), thrown.message)
    }

    // The finding reaches the refusal and the daemon log, so it names a table and a line and never a value. A
    // preamble line is a bare key and value the operator wrote, an unquoted API key among them.
    @Test
    fun `a duplicate models key in the preamble names the preamble and line and never the key written there`() {
        val secret = "sk-live-9f8e7d6c5b4a"
        val doctored = "api_key = $secret\nmodels = [{ id = \"a\" }]\nmodels = [{ id = \"b\" }]\n\n" +
            "[daemon]\ncontrol_port = 4400\n"

        val thrown = assertThrows(IllegalArgumentException::class.java) { TomlStructurePreflight.check(doctored) }

        assertTrue("the preamble (line 1)" in thrown.message!!, thrown.message)
        assertTrue(secret !in thrown.message!! && "api_key" !in thrown.message!!, thrown.message)
    }

    @Test
    fun `a reopened table is named with the line where it was reopened`() {
        val doctored = "[daemon]\ncontrol_port = 4400\n\n[daemon]\ncontrol_port = 4401\n"

        val thrown = assertThrows(IllegalArgumentException::class.java) { TomlStructurePreflight.check(doctored) }

        assertTrue("table [daemon] (line 4) is defined twice" in thrown.message!!, thrown.message)
    }
}
