// NEW: example head ports, including commented blocks, must be unique (V4-15 D1).
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

class ExampleConfigPortTest {

    @Test
    fun `no two heads in the example including comments share a port`() {
        var dir = Paths.get("").toAbsolutePath()
        var text: String? = null
        repeat(4) {
            val candidate = dir.resolve("config").resolve("splice.example.toml")
            if (text == null && Files.exists(candidate)) text = Files.readString(candidate)
            dir = dir.parent ?: dir
        }
        val toml = requireNotNull(text) { "example toml missing" }
        val ports = Regex("(?m)^#?\\s*port\\s*=\\s*(\\d+)").findAll(toml).map { it.groupValues[1] }.toList()
        assertTrue(ports.contains("3106"), "muse example head must sit on 3106")
        assertTrue(ports.contains("3105"), "commented ollama example keeps 3105")
        val dupes = ports.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertEquals(emptyMap<String, Int>(), dupes, "duplicate head ports including comments: $dupes")
    }
}
