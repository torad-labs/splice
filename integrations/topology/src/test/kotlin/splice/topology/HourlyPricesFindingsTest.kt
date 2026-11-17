// `hourly_usd` at boot (docs/specs/server-mode.md, F10): the providers a usage report meters by the hour, and what
// `check-config` refuses in a price, at its key.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.HourlyPrices
import java.nio.file.Path

class HourlyPricesFindingsTest {
    @TempDir lateinit var directory: Path

    /** Two providers with a head each, `box` holding [box] in its table and `api` holding [api]. */
    private fun file(box: String, api: String = ""): String =
        listOf("box" to box, "api" to api).withIndex().joinToString("\n") { (at, pair) ->
            val (key, lines) = pair
            """
            [providers.$key]
            dialect = "openai-chat"
            base_url = "http://10.0.0.2$at:8080/v1"
            auth = { kind = "api-key", env = "BOX_KEY" }
            $lines

            [[providers.$key.models]]
            id = "team-model"
            label = "team-model"
            context_window = 131072

            [heads.$key]
            provider = "$key"
            port = 319$at
            discovery_prefix = "claude-$key--"
            pinned_model = "team-model"
            [heads.$key.claude]
            command = "claude-$key"
            """.trimIndent() + "\n"
        }

    @Test
    fun `each provider that sets hourly_usd is priced by the hour, a whole number of dollars too`() {
        val read = ConfigFindings.read(file("hourly_usd = 2.5", "hourly_usd = 3"), directory, configDir = directory)
        val topology = assertInstanceOf(ConfigRead.Ready::class.java, read, read.toString()).topology
        assertEquals(mapOf("api" to 3.0, "box" to 2.5), HourlyPrices(topology.providers).prices())
    }

    @Test
    fun `an hourly_usd below zero or not a number is refused at its key, each at once`() {
        val read = ConfigFindings.read(file("hourly_usd = -1.0", "hourly_usd = nan"), directory, configDir = directory)
        val findings = assertInstanceOf(ConfigRead.Refused::class.java, read, read.toString()).findings
        assertEquals(
            setOf("providers.box.hourly_usd", "providers.api.hourly_usd"),
            findings.map { it.path }.filter { it.endsWith("hourly_usd") }.toSet(),
        )
    }
}
