// NEW: example head ports, including commented blocks, must be unique (V4-15 D1).
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExampleConfigPortTest {

    @Test
    fun `no two heads in the example including comments share a port`() {
        val toml = checkNotNull(javaClass.getResourceAsStream("/splice.example.toml")) {
            "example toml missing"
        }.bufferedReader().use { it.readText() }
        val ports = Regex("(?m)^#?\\s*port\\s*=\\s*(\\d+)").findAll(toml).map { it.groupValues[1] }.toList()
        assertTrue(ports.contains("3106"), "muse example head must sit on 3106")
        assertTrue(ports.contains("3105"), "commented ollama example keeps 3105")
        val dupes = ports.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertEquals(emptyMap<String, Int>(), dupes, "duplicate head ports including comments: $dupes")
    }
}
