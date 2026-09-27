// V4-359: capture Off is absence, not the literal false. A no-override boot must become identical
// after On then Off; only the trace key is removed, not its retention or body-cap siblings.
package splice.head.wire.v4359

import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriter
import splice.core.topology.TopologyWriterSource
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.wire.CaptureRoutes
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

private const val BASE = """
[providers.local]
dialect = "openai-chat"
base_url = "http://127.0.0.1:3105/v1"
auth = { kind = "api-key", env = "LOCAL_API_KEY" }
[[providers.local.models]]
id = "m"
label = "M"
context_window = 200000
[heads.local]
provider = "local"
port = 3102
discovery_prefix = "claude-local--"
pinned_model = "m"
"""

class CaptureOffStaleTest {
    private data class Fixture(val route: CaptureRoutes, val file: Path, val boot: Topology) {
        fun current(): Topology = TopologyLoader.parse(Files.readString(file))
    }

    private fun fixture(root: Path, extra: String = ""): Fixture {
        val file = root.resolve("splice.toml")
        val initial = BASE.trimIndent() + "\n" + extra
        Files.writeString(file, initial)
        val boot = TopologyLoader.parse(initial)
        val writer = TopologyWriter(file, root.resolve("backups"), TopologyParse(TopologyLoader::parse))
        val head = TurnsHead(
            "local",
            object : HeadCompactSource {
                override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
            },
        )
        val lookup = TurnsHeadLookup { key -> if (key == "local") listOf(head) else emptyList() }
        val config = ConfigService(StatePaths(baseOverride = root.resolve("state")))
        return Fixture(CaptureRoutes(lookup, config, TopologyWriterSource { writer }), file, boot)
    }

    @Test
    fun `on then off returns to the boot topology with no overrides`(@TempDir root: Path) {
        val f = fixture(root)
        assertEquals(HttpStatusCode.OK, f.route.write("local", """{"enabled":true}""").status)
        assertEquals("true", f.current().heads.getValue("local").overrides[Knob.TRACE.key])

        assertEquals(HttpStatusCode.OK, f.route.write("local", """{"enabled":false}""").status)
        assertEquals(emptyMap<String, String>(), f.current().heads.getValue("local").overrides)
        assertEquals(f.boot.withoutWindows(), f.current().withoutWindows())
        assertFalse(Files.readString(f.file).contains("trace = \"false\""))
    }

    @Test
    fun `off removes a boot-time true and remains stale until restart`(@TempDir root: Path) {
        val f = fixture(root, "[heads.local.overrides]\ntrace = \"true\"\n")
        assertEquals(HttpStatusCode.OK, f.route.write("local", """{"enabled":false}""").status)
        assertFalse(Knob.TRACE.key in f.current().heads.getValue("local").overrides)
        assertNotEquals(f.boot.withoutWindows(), f.current().withoutWindows())
    }

    @Test
    fun `off keeps retention and body limits while removing trace`(@TempDir root: Path) {
        val siblings = mapOf(Knob.TRACE_RETENTION_DAYS.key to "5", Knob.TRACE_MAX_BODY_CHARS.key to "4096")
        val extra = "[heads.local.overrides]\n" +
            "${Knob.TRACE.key} = \"true\"\n" +
            siblings.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key = \"$value\"" }
        val f = fixture(root, extra)
        assertEquals(HttpStatusCode.OK, f.route.write("local", """{"enabled":false}""").status)
        assertEquals(siblings, f.current().heads.getValue("local").overrides)
    }

    @Test
    fun `an empty overrides table is the same topology as no table`() {
        val absent = TopologyLoader.parse(BASE.trimIndent() + "\n")
        val empty = TopologyLoader.parse(BASE.trimIndent() + "\n[heads.local.overrides]\n")
        assertEquals(absent.withoutWindows(), empty.withoutWindows())
    }
}
