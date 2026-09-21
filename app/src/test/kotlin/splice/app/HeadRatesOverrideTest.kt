// V4-37 stage two — the per-head rate override, DECLARED IN TOML.
//
// This is the only part of the cost feature an operator cannot reach by editing the provider: two
// heads on ONE provider billed differently (an account tier, a reseller markup). Two failure shapes
// look perfectly correct in source — a field that parses into nothing, and a fold that REPLACES the
// roster instead of overriding named ids — so these tests parse real TOML and read the values back
// off the catalog the statusline actually receives, rather than asserting on the schema's shape.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.cli.add.AddProfiles
import splice.app.daemon.TopologyLoader
import splice.core.model.ModelRates

class HeadRatesOverrideTest {

    private val topology = TopologyLoader.parse(
        """
        [daemon]
        control_port = 3096

        [providers.ds]
        dialect = "openai-chat"
        base_url = "https://api.deepseek.com"
        auth = { kind = "api-key", env = "DEEPSEEK_API_KEY" }

        [[providers.ds.models]]
        id = "deepseek-flash"
        label = "DeepSeek V4.1 Flash"
        context_window = 1000000
        rates = { input = 0.08, cache_read = 0.008, output = 0.28 }

        [[providers.ds.models]]
        id = "deepseek-v4-pro"
        label = "DeepSeek V4 Pro"
        context_window = 1000000
        rates = { input = 0.14, cache_read = 0.014, output = 0.42 }

        [heads.resold]
        provider = "ds"
        port = 3101
        discovery_prefix = "claude-resold--"
        pinned_model = "deepseek-flash"

        [heads.resold.rates]
        deepseek-flash = { input = 0.16, cache_read = 0.016, output = 0.56 }

        [heads.resold.claude]
        command = "claude-resold"

        [heads.direct]
        provider = "ds"
        port = 3102
        discovery_prefix = "claude-direct--"
        pinned_model = "deepseek-flash"

        [heads.direct.claude]
        command = "claude-direct"
        """.trimIndent(),
    )

    private fun catalogFor(headKey: String) =
        topology.providers.getValue("ds").catalogFor(topology.heads.getValue(headKey))

    private fun ratesOf(headKey: String, modelId: String): ModelRates? =
        catalogFor(headKey).models.first { it.id == modelId }.rates

    private val offPeak = ModelRates(input = 0.08, cacheRead = 0.008, output = 0.28)
    private val reseller = ModelRates(input = 0.16, cacheRead = 0.016, output = 0.56)

    @Test
    fun `a head rate table parses into the head config`() {
        val parsed = topology.heads.getValue("resold").rates
        assertNotNull(parsed, "the table must reach HeadConfig.rates, not parse into nothing")
        assertEquals(reseller, parsed!!.getValue("deepseek-flash"))
        assertNull(topology.heads.getValue("direct").rates, "a head with no table stays absent, not empty")
    }

    @Test
    fun `the head card overrides the provider entry for the ids it names and only those`() {
        assertEquals(reseller, ratesOf("resold", "deepseek-flash"), "the named id takes the head's card")
        assertEquals(
            ModelRates(input = 0.14, cacheRead = 0.014, output = 0.42),
            ratesOf("resold", "deepseek-v4-pro"),
            "an id the head does not name keeps the provider's card — an override is not a roster",
        )
    }

    @Test
    fun `two heads on one provider price differently and never leak into each other`() {
        assertEquals(reseller, ratesOf("resold", "deepseek-flash"))
        assertEquals(offPeak, ratesOf("direct", "deepseek-flash"), "the un-overridden head keeps the provider card")
    }

    @Test
    fun `the sub-table spelling for model rates is rejected, so it is not reintroduced`() {
        // Found the hard way: `[providers.X.models.rates]` repeats its header once per model, and
        // TomlStructurePreflight.rejectDuplicateModelKeys refuses a repeated single-bracket header
        // (ktoml would silently merge both bodies). The INLINE form used in the fixture above is the
        // only spelling that survives — an operator writing the obvious sub-table spelling gets a
        // hard parse error, not a silently wrong card. This test is what keeps that true.
        val subTable = """
            [daemon]
            control_port = 3096

            [providers.ds]
            dialect = "openai-chat"
            base_url = "https://api.deepseek.com"
            auth = { kind = "api-key", env = "DEEPSEEK_API_KEY" }

            [[providers.ds.models]]
            id = "a"
            label = "A"
            context_window = 1000

            [providers.ds.models.rates]
            input = 0.1
            cache_read = 0.01
            output = 0.2

            [[providers.ds.models]]
            id = "b"
            label = "B"
            context_window = 1000

            [providers.ds.models.rates]
            input = 0.1
            cache_read = 0.01
            output = 0.2
        """.trimIndent()
        val failure = assertThrows(IllegalArgumentException::class.java) { TopologyLoader.parse(subTable) }
        assertTrue("defined twice" in (failure.message ?: ""), failure.message ?: "")
    }

    @Test
    fun `the deepseek profile's own emitted TOML carries a card through catalogFor`() {
        // The end the operator actually reaches: `splice add deepseek` emits TOML, and a head
        // BOOTS from it. catalogFor is the fold's home and it runs at head-build time — a topology
        // that merely PARSES proves nothing about it, so this drives the profile's real output
        // through load and then through catalogFor, which is where a broken fold would take a head
        // down while doctor's parse stayed green.
        val profile = requireNotNull(AddProfiles().find("deepseek")) { "deepseek profile missing" }
        val emitted = AddProfiles().toml(profile, "deepseek", 3101)
        val parsed = TopologyLoader.parse("[daemon]\ncontrol_port = 3096\n$emitted")
        val catalog = parsed.providers.getValue("deepseek").catalogFor(parsed.heads.getValue("deepseek"))
        assertEquals(
            ModelRates(input = 0.15, cacheRead = 0.003, output = 0.60),
            catalog.models.first { it.id == "deepseek-flash" }.rates,
            "the emitted card must survive emit -> parse -> catalogFor, or the head prices nothing",
        )
        assertEquals(
            ModelRates(input = 0.66, cacheRead = 0.022, output = 1.98),
            catalog.models.first { it.id == "deepseek-v4-pro" }.rates,
        )
    }

    @Test
    fun `a provider model entry with no rate table stays unrated`() {
        // The never-below-status-quo floor: absent rates must stay absent, so SessionCost answers
        // null and the statusline renders the client's own number exactly as it did before V4-37.
        val plain = TopologyLoader.parse(
            """
            [daemon]
            control_port = 3096

            [providers.plain]
            dialect = "openai-chat"
            base_url = "https://example.test"
            auth = { kind = "api-key", env = "PLAIN_API_KEY" }

            [[providers.plain.models]]
            id = "m"
            label = "M"
            context_window = 200000

            [heads.plain]
            provider = "plain"
            port = 3103
            discovery_prefix = "claude-plain--"
            pinned_model = "m"

            [heads.plain.claude]
            command = "claude-plain"
            """.trimIndent(),
        )
        val rates = plain.providers.getValue("plain").catalogFor(plain.heads.getValue("plain")).models.first().rates
        assertNull(rates, "no declared card means no computed cost, and the client's number stands")
    }
}
