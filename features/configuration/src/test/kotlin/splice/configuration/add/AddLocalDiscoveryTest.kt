package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class AddLocalDiscoveryTest {
    private val lines = mutableListOf<String>()
    private val output = TerminalOutput { lines += it }

    private fun prepare(home: Path, answering: Set<String>): AddPrepared = UserHome.within(home) {
        val config = home.resolve(".config/splice/splice.toml")
        Files.createDirectories(config.parent)
        Files.writeString(config, "[daemon]\ncontrol_port = 31400\n")
        val http = AddHttp { _, url, _, _ ->
            if (answering.any { url.startsWith(it) }) AddHttpReply(200, """{"data":[{"id":"m"}]}""") else null
        }
        AddPrepare(output, AddChecks(output, http), { _, default -> default }).prepare(
            AddArgs(profile = "local", name = "found", models = listOf("m:1000"), yes = true),
            EnvReader { null },
        )
    }

    @Test
    fun `a local add with no address uses the one runtime that answers`(@TempDir home: Path) {
        val ready = assertInstanceOf(AddPrepared.Ready::class.java, prepare(home, setOf("http://localhost:1234")))
        assertEquals("http://localhost:1234/v1", ready.candidate.provider.baseUrl)
        assertTrue(lines.any { it.contains("found a local runtime at http://localhost:1234/v1") })
    }

    @Test
    fun `a local add with several runtimes answering names them and asks for the address`(@TempDir home: Path) {
        val answering = setOf("http://localhost:11434", "http://localhost:8000")
        val refused = assertInstanceOf(AddPrepared.Refused::class.java, prepare(home, answering))
        assertEquals(AddRefusal.BaseUrlRequired("local"), refused.refusal)
        assertTrue(lines.any { it.contains("localhost:11434/v1") && it.contains("localhost:8000/v1") })
    }

    @Test
    fun `a local add with nothing answering still asks for the address`(@TempDir home: Path) {
        val refused = assertInstanceOf(AddPrepared.Refused::class.java, prepare(home, emptySet()))
        assertEquals(AddRefusal.BaseUrlRequired("local"), refused.refusal)
    }
}
