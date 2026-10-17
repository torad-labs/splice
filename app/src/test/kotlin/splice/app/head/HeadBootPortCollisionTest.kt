// NEW (restructure PR 6 §2.7, the daemon-side home of the jw_13 wall): two heads configured on one
// port must be named BEFORE either tries to bind.
//
// The three claims JW-13 makes live in three places and fail independently, so they are graded in
// three: Topology.portCollisions() is pinned in core (ConfigHonestyPinsTest), doctor's config FAIL
// naming both heads is pinned in DoctorCommandTest, and the daemon's own boot — this file — is the
// one that decides what an operator who never runs doctor sees. Without the pre-check the loser
// reaches assembly and dies on "Address already in use", which names a port and not the sibling
// that claimed it.
//
// Graded on the collaborator rather than through a booted daemon: the claim is that the colliding
// heads are failed with a naming message and never handed to assembly at all, and an assembly that
// throws when called is how that is stated without standing up two servers.
package splice.app.head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.HeadAssembly
import splice.app.control.ManagedHead
import splice.core.config.StatePaths
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import java.nio.file.Path

class HeadBootPortCollisionTest {

    @TempDir
    lateinit var tmp: Path

    private val assembled = mutableListOf<String>()

    /** Assembly must never be reached for a colliding head; if it is, the test says which one. */
    private val refusingAssembly = HeadAssembly { key, _, _ ->
        assembled += key
        error("assemble must not be called for $key — its port collision was supposed to fail it first")
    }

    private fun topology(vararg headPorts: Pair<String, Int>): Topology = Topology(
        providers = mapOf(
            "codex" to ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://chatgpt.com/backend-api/codex",
                auth = AuthConfig(kind = "chatgpt-oauth"),
            ),
        ),
        heads = headPorts.toMap().mapValues { (key, port) ->
            HeadConfig(
                provider = "codex",
                port = port,
                discoveryPrefix = "claude-codex--",
                pinnedModel = "gpt-5.6-sol",
                claude = ClaudeWrapperConfig(command = key),
            )
        },
    )

    private fun boot(
        topology: Topology,
        assembly: HeadAssembly = refusingAssembly,
    ): Pair<Map<String, String>, List<String>> {
        val logs = mutableListOf<String>()
        val heads = mutableMapOf<String, ManagedHead>()
        val failed = HeadBoot().assembleDaemonHeads(
            topology,
            StatePaths(baseOverride = tmp.resolve("state")),
            heads,
            logs::add,
            assembly,
        )
        return failed to logs
    }

    @Test
    fun `two heads on one port both fail, each naming the sibling - JW-13`() {
        val (failed, logs) = boot(topology("alpha" to 4501, "beta" to 4501))
        assertEquals(setOf("alpha", "beta"), failed.keys, "BOTH colliding heads fail, not just the loser: $failed")
        for ((key, message) in failed) {
            assertTrue(message.contains("port 4501"), "$key names the port: $message")
            assertTrue(message.contains("alpha") && message.contains("beta"), "$key names both heads: $message")
        }
        assertTrue(logs.any { it.contains("port 4501 is claimed by") }, "the boot log carries it too: $logs")
        assertEquals(emptyList<String>(), assembled, "a colliding head must never reach assembly")
    }

    @Test
    fun `a head on its own port is untouched by a collision elsewhere - JW-13 bound`() {
        // The pre-check must fail exactly the colliding heads. A filter that dropped the whole boot
        // would also satisfy "both colliding heads failed", and this is what tells them apart.
        val reached = mutableListOf<String>()
        val (failed, _) = boot(
            topology("alpha" to 4501, "beta" to 4501, "gamma" to 4502),
            HeadAssembly { key, _, _ ->
                reached += key
                error("stop after recording: assembling a real head needs a server this test does not want")
            },
        )
        assertEquals(setOf("alpha", "beta"), failed.keys - "gamma", "only the colliding pair collides: $failed")
        assertEquals(listOf("gamma"), reached, "the non-colliding head is still offered to assembly")
    }

    @Test
    fun `distinct ports assemble with no collision failures - JW-13 control`() {
        val reached = mutableListOf<String>()
        val (failed, logs) = boot(
            topology("alpha" to 4501, "beta" to 4502),
            HeadAssembly { key, _, _ ->
                reached += key
                error("stop after recording")
            },
        )
        assertTrue(logs.none { it.contains("is claimed by") }, "no collision line on a clean topology: $logs")
        assertEquals(listOf("alpha", "beta"), reached, "both heads are offered to assembly")
        assertTrue(
            failed.values.none { it.contains("is claimed by") },
            "no collision failure on a clean topology: $failed",
        )
    }
}
