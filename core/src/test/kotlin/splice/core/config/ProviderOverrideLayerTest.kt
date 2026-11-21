// NEW: gateway-no-heads. A provider's own overrides are a layer of their own: the gateway reads them for the provider,
// a head on that provider reads them beneath its own, and the bounds a turn on the provider is held to (the daemon-wide
// timing knobs) are accepted there although a head's own overrides still refuse them. What the person sees is the
// effective config each reader gets, and the reason a refused line is given.
package splice.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ProviderOverrideLayerTest {
    @TempDir
    lateinit var tmp: Path

    private val provider = "providers.pebble"

    private fun service(
        own: Map<String, Map<String, String>>,
        inherited: Map<String, Map<String, String>> = emptyMap(),
    ) = ConfigService(
        StatePaths(baseOverride = tmp.resolve("state")),
        perHeadOverrides = own,
        envReader = { null },
        inheritedOverrides = inherited,
    )

    @Test
    fun `a provider's serving bound reaches the gateway's read and the heads on the provider`() {
        val bounds = mapOf("upstreamTimeoutMs" to "600000", "firstByteTimeoutMs" to "45000")
        val config = service(own = mapOf(provider to bounds, "peb" to emptyMap()), inherited = mapOf("peb" to bounds))

        assertEquals(600_000L, config.getConfig(provider).upstreamTimeoutMs)
        assertEquals(45_000L, config.getConfig(provider).firstByteTimeoutMs)
        assertEquals(600_000L, config.getConfig("peb").upstreamTimeoutMs, "a head reads its provider's bound")
        assertTrue(config.coerceRejects().isEmpty(), config.coerceRejects().toString())
    }

    @Test
    fun `a head's own serving bound is still refused, and the provider's bound under it still holds`() {
        val config = service(
            own = mapOf(
                provider to mapOf("upstreamTimeoutMs" to "600000"),
                "peb" to mapOf("upstreamTimeoutMs" to "5"),
            ),
            inherited = mapOf("peb" to mapOf("upstreamTimeoutMs" to "600000")),
        )

        assertEquals(600_000L, config.getConfig("peb").upstreamTimeoutMs)
        val why = config.coerceRejects()["heads.peb.upstreamTimeoutMs"]
        assertEquals("is daemon-wide; set it under [defaults] instead", why)
    }

    @Test
    fun `a daemon-wide knob that is not a serving bound is refused on a provider, at the provider's own path`() {
        val layer = mapOf("ingressReadIdleMs" to "5000", "upstreamTimeoutMs" to "600000")
        val config = service(own = mapOf(provider to layer))

        val rejects = config.coerceRejects()
        assertTrue("providers.pebble.overrides.ingressReadIdleMs" in rejects, rejects.toString())
        assertFalse("providers.pebble.overrides.upstreamTimeoutMs" in rejects, rejects.toString())
    }
}
