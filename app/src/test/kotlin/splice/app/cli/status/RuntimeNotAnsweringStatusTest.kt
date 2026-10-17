package splice.app.cli.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepth
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.upstream.transport.LocalHttp
import splice.upstream.transport.LocalHttpReply
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch

/** Status says whether a turn would work, not whether the wrapper
 *  is installed: a local head whose runtime does not answer reads so, and the probe is bounded so
 *  status never hangs on a wedged one. */
class RuntimeNotAnsweringStatusTest {
    private fun local(port: Int) = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "http://127.0.0.1:$port/v1",
        auth = AuthConfig(kind = "api-key", env = "TEST_LOCAL_KEY"),
    )

    private fun head(provider: String, command: String, port: Int) = HeadConfig(
        provider = provider,
        port = port,
        discoveryPrefix = "$command--",
        pinnedModel = "m",
        claude = ClaudeWrapperConfig(command = command),
    )

    private val topology = Topology(
        providers = mapOf("bonsai-rt" to local(8099), "glml-rt" to local(8101)),
        heads = linkedMapOf(
            "bonsai" to head("bonsai-rt", "claude-bonsai", 3108),
            "glml53" to head("glml-rt", "claude-glml53", 3111),
        ),
    )

    private fun env(bin: Path): EnvReader {
        listOf("claude-bonsai", "claude-glml53").forEach { Files.createSymbolicLink(bin.resolve(it), bin.resolve("t")) }
        val vars = mapOf("SPLICE_BIN_DIR" to bin.toString(), "TEST_LOCAL_KEY" to "k")
        return EnvReader(vars::get)
    }

    @Test
    fun `a local head whose runtime does not answer reads so, and the one that answers reads ready`(
        @TempDir bin: Path,
    ) {
        val answering = LocalHttp { _, url, _ -> LocalHttpReply(200, "{}").takeIf { url.contains(":8101") } }
        val down = LocalRuntimeReach(answering).notAnswering(topology)

        assertEquals(mapOf("bonsai" to ":8099"), down)
        val lines = StatusTable(CliPalette(ColorDepth.NONE))
            .lines(topology, env(bin), StatusReadings(runtimeNotAnswering = down))
        val bonsai = lines.single { it.contains("claude-bonsai") }
        val glml = lines.single { it.contains("claude-glml53") }
        assertTrue(bonsai.contains("runtime not answering on :8099"), bonsai)
        assertFalse(bonsai.contains("ready"), bonsai)
        assertTrue(glml.trimEnd().endsWith("ready"), glml)
        assertTrue(bonsai.trimStart().first() != glml.trimStart().first(), "the glyph carries the state")
    }

    @Test
    fun `any reply at all counts as answering, a guarded runtime included`() {
        val guarded = LocalHttp { _, _, _ -> LocalHttpReply(401, """{"error":"unauthorized"}""") }

        assertEquals(emptyMap<String, String>(), LocalRuntimeReach(guarded).notAnswering(topology))
    }

    @Test
    fun `a wedged runtime cannot hold status past the wait`() {
        val never = CountDownLatch(1)
        val wedged = LocalHttp { _, _, _ ->
            never.await()
            LocalHttpReply(200, "{}")
        }

        val down = assertTimeoutPreemptively(
            Duration.ofSeconds(BOUND_S),
            ThrowingSupplier { LocalRuntimeReach(wedged, waitMs = 300L).notAnswering(topology) },
        )

        assertEquals(setOf("bonsai", "glml53"), down.keys)
    }

    @Test
    fun `heads that are not local runtimes are never probed`() {
        val remote = ProviderConfig(
            dialect = Dialect.OPENAI_CHAT,
            baseUrl = "https://api.example.invalid/v1",
            auth = AuthConfig(kind = "api-key", env = "TEST_LOCAL_KEY"),
        )
        val cloud = Topology(
            providers = mapOf("p" to remote),
            heads = mapOf("openrouter" to head("p", "claudeor", 3101)),
        )
        val asked = mutableListOf<String>()

        val down = LocalRuntimeReach(
            LocalHttp { _, url, _ ->
                asked += url
                null
            },
        ).notAnswering(cloud)

        assertEquals(emptyMap<String, String>(), down)
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `each distinct runtime is asked once, however many heads share it`() {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val shared = Topology(
            providers = mapOf("bonsai-rt" to local(8099)),
            heads = linkedMapOf(
                "bonsai" to head("bonsai-rt", "claude-bonsai", 3108),
                "bonsai-second" to head("bonsai-rt", "claude-bonsai-second", 3109),
            ),
        )

        val down = LocalRuntimeReach(
            LocalHttp { _, url, _ ->
                asked += url
                null
            },
        ).notAnswering(shared)

        assertEquals(listOf("http://127.0.0.1:8099/v1/models"), asked.toList())
        assertEquals(mapOf("bonsai" to ":8099", "bonsai-second" to ":8099"), down)
    }
}

// Far above the 300 ms wait, far below a hung build: a wedge that is not bounded fails here, not by hanging.
private const val BOUND_S = 10L
