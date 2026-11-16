// `[[providers.<p>.upstreams]]` at boot (docs/specs/server-mode.md, F4): the list a provider that is several servers
// boots with, and what `check-config` refuses in one, each refusal at its key and all of them at once.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.TopologyFinding
import java.nio.file.Path

class UpstreamConfigFindingsTest {
    @TempDir lateinit var directory: Path

    /** A head on the provider `box`, whose table holds [provider] after its dialect and auth. */
    private fun file(provider: String, dialect: String = "openai-chat"): String =
        """
        [providers.box]
        dialect = "$dialect"
        auth = { kind = "api-key", env = "BOX_KEY" }
        """.trimIndent() + "\n" + provider.trimIndent() + "\n" +
            """
            [[providers.box.models]]
            id = "team-model"
            label = "team-model"
            context_window = 131072

            [heads.box]
            provider = "box"
            port = 3190
            discovery_prefix = "claude-box--"
            pinned_model = "team-model"
            [heads.box.claude]
            command = "claude-team"
            """.trimIndent() + "\n"

    private fun upstream(name: String, url: String): String =
        "[[providers.box.upstreams]]\nname = \"$name\"\nurl = \"$url\"\n"

    /** The findings under the provider's table, as `check-config` lists them. */
    private fun refused(toml: String): List<TopologyFinding> {
        val read = ConfigFindings.read(toml, directory, configDir = directory)
        return assertInstanceOf(ConfigRead.Refused::class.java, read, read.toString()).findings
            .filter { it.path.startsWith("providers.box") }
    }

    @Test
    fun `a provider that lists its servers boots with no base_url, checked every health_seconds`() {
        val two = upstream("gpu-1", "http://10.0.0.21:8080/v1") + upstream("gpu-2", "http://10.0.0.22:8080/v1")
        val read = ConfigFindings.read(file("health_seconds = 2\n$two"), directory, configDir = directory)
        val provider = assertInstanceOf(ConfigRead.Ready::class.java, read, read.toString())
            .topology.providers.getValue("box")
        assertEquals(listOf("gpu-1", "gpu-2"), provider.upstreams.map { it.name })
        assertEquals(listOf("http://10.0.0.21:8080/v1", "http://10.0.0.22:8080/v1"), provider.upstreams.map { it.url })
        assertEquals(2, provider.healthSeconds)

        val unset = ConfigFindings.read(file(two), directory, configDir = directory)
        val defaulted = assertInstanceOf(ConfigRead.Ready::class.java, unset, unset.toString())
            .topology.providers.getValue("box")
        assertEquals(5, defaulted.healthSeconds, "health_seconds left out is five seconds")
    }

    @Test
    fun `a provider with one base_url boots as before, and one with neither is asked for either`() {
        val one = ConfigFindings.read(file("base_url = \"http://10.0.0.21:8080/v1\""), directory, configDir = directory)
        assertInstanceOf(ConfigRead.Ready::class.java, one, one.toString())
        val neither = refused(file("")).single()
        assertEquals("providers.box.base_url", neither.path)
        assertTrue(listOf("base_url", "[[providers.box.upstreams]]").all(neither.message::contains), neither.message)
    }

    @Test
    fun `upstreams beside a base_url are refused, since they take its place`() {
        val both = "base_url = \"http://10.0.0.9:8080/v1\"\n" + upstream("gpu-1", "http://10.0.0.21:8080/v1")
        val finding = refused(file(both)).single()
        assertEquals("providers.box.upstreams", finding.path)
        assertTrue(finding.message.contains("base_url"), finding.message)
    }

    @Test
    fun `upstreams on a provider that does not speak openai-chat are refused`() {
        val listed = upstream("gpu-1", "http://10.0.0.21:8080/v1")
        val findings = refused(file(listed, dialect = "openai-responses"))
            .filter { it.path == "providers.box.upstreams" }
        assertTrue(findings.single().message.contains("openai-chat"), findings.toString())
    }

    @Test
    fun `a shared name, a name or address no turn could use and a health_seconds of zero are each refused at once`() {
        val listed = "health_seconds = 0\n" +
            upstream("gpu-1", "http://10.0.0.21:8080/v1") +
            upstream("gpu-1", "10.0.0.22:8080") +
            upstream("gpu 3", "ftp://10.0.0.23/v1")
        assertEquals(
            setOf(
                "providers.box.upstreams",
                "providers.box.upstreams[1].url",
                "providers.box.upstreams[2].name",
                "providers.box.upstreams[2].url",
                "providers.box.health_seconds",
            ),
            refused(file(listed)).map { it.path }.toSet(),
        )
    }
}
