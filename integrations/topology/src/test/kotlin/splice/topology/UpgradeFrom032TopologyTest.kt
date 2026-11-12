// NEW: the upgrade a real 0.3.2 user takes. The parser refuses a key the schema does not declare
// (TopologyUnknownKeyTest), so every key dropped or renamed since 0.3.2 would refuse a file that was
// valid then — on first boot, before the operator can edit anything. The published 0.3.2 example is
// the broadest file a user could be running (every OAuth head, quirks, extra windows, window rules,
// the Claude share list, defaults and per-head overrides), so it is vendored byte-for-byte from the
// v0.3.2 tag and parsed here. The upgrade rehearsal (tools/e2e/docker/upgrade.sh) writes its own
// three-head file instead, so nothing else in the tree reads a 0.3.2-shaped config.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpgradeFrom032TopologyTest {

    private val text: String = requireNotNull(
        javaClass.getResourceAsStream("/splice-0.3.2.example.toml"),
    ) { "the vendored 0.3.2 example config is missing" }.use { it.readBytes().decodeToString() }

    @Test
    fun `the published 0-3-2 example still parses, so no key it uses was dropped or renamed`() {
        val topology = TopologyLoader.parse(text)

        assertTrue(topology.providers.isNotEmpty(), "the 0.3.2 example declares providers")
        assertTrue(topology.heads.isNotEmpty(), "the 0.3.2 example declares heads")
    }

    /** A 0.3.2 file names no head on the control port, and 0.4.0 now counts that collision
     *  (Topology.portCollisions): a file that booted then must not fail a head now. */
    @Test
    fun `no head in the 0-3-2 example collides with another head or with the control port`() {
        val topology = TopologyLoader.parse(text)

        assertEquals(emptyMap<Int, List<String>>(), topology.portCollisions(), "a collision fails the head at boot")
        assertEquals(emptyMap<String, Int>(), topology.invalidPortHeads())
    }

    /** 0.4.0 refuses an empty pinned_model and an empty discovery_prefix, neither checked at 0.3.2. */
    @Test
    fun `every head in the 0-3-2 example carries the non-empty names 0-4-0 now requires`() {
        val topology = TopologyLoader.parse(text)

        topology.heads.forEach { (key, head) ->
            assertFalse(head.discoveryPrefix.isBlank(), "head '$key' has a blank discovery_prefix")
        }
    }
}
