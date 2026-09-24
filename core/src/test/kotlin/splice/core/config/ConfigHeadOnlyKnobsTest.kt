// NEW: v0.4.0 prompt-review — the trace and the wire tap keep a head's whole conversation, and each
// knob's contract was "set through [heads.KEY.overrides], never the global view": the switch
// `splice doctor` reads and names. Both still took the global env aliases, the global TOML layer,
// the state file and PATCH, which sit ABOVE the per-head layer, so `SPLICE_TRACE=1` (documented in
// the example config) or one console PATCH wrote full traces for every head while doctor named none.
// A head-only knob now comes from the per-head layer and nowhere else.
package splice.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class ConfigHeadOnlyKnobsTest {

    @TempDir
    lateinit var tmp: Path

    private fun service(
        env: Map<String, String> = emptyMap(),
        global: Map<String, String> = emptyMap(),
        perHead: Map<String, Map<String, String>> = emptyMap(),
    ) = ConfigService(
        StatePaths(baseOverride = tmp.resolve("state")),
        headOverrides = global,
        perHeadOverrides = perHead,
        envReader = { env[it] },
    )

    @Test
    fun `the environment cannot turn the trace or the wire tap on`() {
        val config = service(env = mapOf("SPLICE_TRACE" to "1", "SPLICE_WIRE_TAP" to "4")).getConfig("kimi")

        assertFalse(config.trace)
        assertEquals(0, config.wireTap)
    }

    @Test
    fun `the global TOML layer cannot turn them on`() {
        val config = service(global = mapOf("trace" to "true", "wireTap" to "4")).getConfig("kimi")

        assertFalse(config.trace)
        assertEquals(0, config.wireTap)
    }

    @Test
    fun `a PATCH is refused by name and never persisted`() {
        val svc = service()

        val result = svc.patch(mapOf("trace" to true, "wireTap" to 4))

        assertEquals(setOf("trace", "wireTap"), result.rejected.keys)
        assertTrue(result.rejected.values.all { it.contains("[heads.<key>.overrides]") }, "${result.rejected}")
        assertFalse(svc.getConfig("kimi").trace)
        val file = StatePaths(baseOverride = tmp.resolve("state")).configFile
        assertFalse(Files.exists(file) && file.readText().contains("trace"), "a refused key is never written")
    }

    @Test
    fun `a state file an older daemon wrote cannot turn them on`() {
        val file = StatePaths(baseOverride = tmp.resolve("state")).configFile
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"trace": true, "wireTap": 4}""")

        val config = service().getConfig("kimi")

        assertFalse(config.trace)
        assertEquals(0, config.wireTap)
    }

    @Test
    fun `the head's own overrides turn them on for that head only`() {
        val svc = service(perHead = mapOf("kimi" to mapOf("trace" to "true", "wireTap" to "5")))

        assertTrue(svc.getConfig("kimi").trace)
        assertEquals(5, svc.getConfig("kimi").wireTap)
        assertFalse(svc.getConfig("claudex").trace)
        assertEquals(0, svc.getConfig("claudex").wireTap)
    }
}
