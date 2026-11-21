// gateway-no-heads: `splice status` lists a provider no head runs, served on the door, with its credential's state, and
// is not "not set up yet" for a splice.toml that declares providers and no head at all.
package splice.app.cli.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepth
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader

class HeadlessProviderStatusTest {
    private fun provider(env: String) = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "http://127.0.0.1:8099/v1",
        auth = AuthConfig(kind = "api-key", env = env),
    )

    private val topology =
        Topology(providers = mapOf("inkling" to provider("INKLING_KEY"), "bare" to provider("BARE_KEY")))

    private fun lines(vars: Map<String, String>): List<String> =
        StatusTable(CliPalette(ColorDepth.NONE)).lines(topology, EnvReader(vars::get))

    @Test
    fun `a provider with no head is a row served on the door, ready with its key and told what to do without one`() {
        val said = lines(mapOf("INKLING_KEY" to "k"))
        val inkling = said.single { "inkling" in it }
        assertTrue("the door" in inkling && "ready on the door" in inkling, inkling)
        val bare = said.single { "bare" in it }
        assertTrue("no credential" in bare && "splice key set" in bare, bare)
        assertFalse(said.any { "not set up yet" in it }, said.toString())
        assertEquals(1, said.count { "inkling" in it })
    }
}
