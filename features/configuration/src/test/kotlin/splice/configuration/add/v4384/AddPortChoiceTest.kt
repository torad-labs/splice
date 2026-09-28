package splice.configuration.add.v4384

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.configuration.add.AddArgs
import splice.configuration.add.AddChecks
import splice.configuration.add.AddPrepare
import splice.configuration.add.AddPrepared
import splice.configuration.add.AddRefusal
import splice.configuration.add.AddRefusalText
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

class AddPortChoiceTest {
    private val output = TerminalOutput { }
    private val env = EnvReader { null }

    private fun file(home: Path, control: Int, head: Int, provider: Int? = null): Path {
        val path = home.resolve(".config/splice/splice.toml")
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            """
            [daemon]
            control_port = $control
            [providers.old]
            dialect = "openai-chat"
            base_url = "http://127.0.0.1:${provider ?: 9}/v1"
            auth = { kind = "api-key", env = "OLD_API_KEY" }
            [[providers.old.models]]
            id = "m"
            context_window = 1000
            [heads.old]
            provider = "old"
            port = $head
            discovery_prefix = "claude-old--"
            pinned_model = "m"
            """.trimIndent() + "\n",
        )
        return path
    }

    private fun prepare(
        home: Path,
        control: Int,
        head: Int,
        provider: Int? = null,
        candidate: String,
        lastPort: Int = 65_535,
    ): AddPrepared = UserHome.within(home) {
        file(home, control, head, provider)
        val add = AddPrepare(output, AddChecks(output), { _, default -> default }, lastHeadPort = lastPort)
        add.prepare(
            AddArgs(
                profile = "local",
                name = "new",
                baseUrl = candidate,
                models = listOf("m:1000"),
                yes = true,
            ),
            env,
        )
    }

    @Test
    fun `an existing local provider port cannot become the next head`(@TempDir home: Path) {
        val control = TestPorts.reserve()
        val head = control + 1
        val taken = head + 1
        val result = prepare(home, control, head, taken, "http://127.0.0.1:32199/v1")
        val candidate = assertInstanceOf(AddPrepared.Ready::class.java, result).candidate
        assertEquals(taken + 1, candidate.topology.heads.getValue("new").port)
    }

    @Test
    fun `the candidate local runtime port cannot become its own new head`(@TempDir home: Path) {
        val control = TestPorts.reserve()
        val head = control + 1
        val taken = head + 1
        val result = prepare(home, control, head, candidate = "http://localhost:$taken/v1")
        val candidate = assertInstanceOf(AddPrepared.Ready::class.java, result).candidate
        assertEquals(taken + 1, candidate.topology.heads.getValue("new").port)
    }

    @Test
    fun `an exhausted port range refuses by name without appending a head`(@TempDir home: Path) {
        val control = TestPorts.reserve()
        val head = control + 1
        val onlyCandidate = head + 1
        ServerSocket(onlyCandidate).use { listener ->
            assertTrue(listener.isBound)
            val existing = file(home, control, head)
            val before = Files.readString(existing)
            val result = prepare(
                home,
                control,
                head,
                candidate = "http://127.0.0.1:32199/v1",
                lastPort = onlyCandidate,
            )
            val refusal = assertInstanceOf(AddPrepared.Refused::class.java, result)
            assertTrue(refusal.conflict, "a blocked local listener is a retryable port conflict")
            assertEquals(AddRefusal.PortUnavailable(onlyCandidate, onlyCandidate), refusal.refusal)
            assertTrue(AddRefusalText().console(refusal.refusal).contains("$onlyCandidate..$onlyCandidate"))
            assertEquals(before, Files.readString(existing), "a refused add must not edit the topology")
        }
    }

    @Test
    fun `a foreign listener on the next port is skipped and not given to the new head`(@TempDir home: Path) {
        val control = TestPorts.reserve()
        val head = control + 1
        ServerSocket(head + 1).use { listener ->
            assertTrue(listener.isBound)
            val result = prepare(home, control, head, candidate = "http://127.0.0.1:32199/v1")
            val candidate = assertInstanceOf(AddPrepared.Ready::class.java, result).candidate
            assertEquals(head + 2, candidate.topology.heads.getValue("new").port)
        }
    }
}
