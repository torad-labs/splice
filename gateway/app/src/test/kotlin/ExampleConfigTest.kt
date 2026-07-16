// NEW: the committed config/splice.example.toml is a TESTED artifact, not aspirational docs — it
// must parse and yield the three documented heads with the right dialects/auth. If someone edits
// the example into an invalid shape, this fails.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.TopologyLoader
import splice.core.topology.Dialect
import java.nio.file.Files
import java.nio.file.Paths

class ExampleConfigTest {

    private fun exampleToml(): String {
        // walk up from the gateway module dir to the repo root
        var dir = Paths.get("").toAbsolutePath()
        repeat(4) {
            val candidate = dir.resolve("config").resolve("splice.example.toml")
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent ?: return@repeat
        }
        error("config/splice.example.toml not found from ${Paths.get("").toAbsolutePath()}")
    }

    @Test
    fun `example topology parses into the three documented heads`() {
        val topology = TopologyLoader.parse(exampleToml())
        assertEquals(setOf("claudex", "grok", "openrouter"), topology.heads.keys)
        assertEquals(3096, topology.daemon.controlPort)

        val codex = topology.providers[topology.heads["claudex"]!!.provider]!!
        assertEquals(Dialect.OPENAI_RESPONSES, codex.dialect)
        assertEquals("chatgpt-oauth", codex.auth.kind)

        val xai = topology.providers[topology.heads["grok"]!!.provider]!!
        assertEquals(Dialect.OPENAI_RESPONSES, xai.dialect)
        assertEquals("api-key", xai.auth.kind)
        assertEquals("session-id", xai.quirks.cacheKey)

        val openrouter = topology.providers[topology.heads["openrouter"]!!.provider]!!
        assertEquals(Dialect.OPENAI_CHAT, openrouter.dialect)
        assertEquals("api-key", openrouter.auth.kind)

        // the isolate override survives the round-trip
        assertTrue(topology.heads["grok"]!!.claude.isolate.contains("commands"))
        assertEquals("claudex", topology.heads["claudex"]!!.claude.command)
    }
}
