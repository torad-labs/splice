// NEW: V4-34 — splice add-model writes through SelectPrompt and MultiSelectPrompt.
// Cancel and a non-TTY empty selection leave the seeded file byte-identical.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TopologyLoader
import splice.app.cli.AddModelVerb
import splice.app.cli.prompt.KeyReader
import splice.app.cli.prompt.MultiSelectPrompt
import splice.app.cli.prompt.SelectPrompt
import splice.app.cli.prompt.SttyCommand
import splice.app.cli.prompt.SttyResult
import splice.app.cli.prompt.TerminalMode
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class AddModelsTest {

    @Test
    fun `cancel at the head picker leaves the file byte-identical`(@TempDir dir: Path) {
        val path = seed(dir)
        val before = Files.readString(path)
        val wrote = verb(selectKeys = byteArrayOf(ESC), selectTty = true).add(path)
        assertFalse(wrote)
        assertEquals(before, Files.readString(path))
    }

    @Test
    fun `non-TTY empty selection writes nothing`(@TempDir dir: Path) {
        val path = seed(dir)
        val before = Files.readString(path)
        val wrote = verb(selectTty = false, multiTty = false).add(path)
        assertFalse(wrote)
        assertEquals(before, Files.readString(path))
    }

    @Test
    fun `choosing a remaining model appends a parseable row`(@TempDir dir: Path) {
        val path = seed(dir)
        val wrote = verb(
            selectTty = false,
            multiTty = true,
            multiKeys = byteArrayOf(SPACE, ENTER),
        ).add(path)
        assertTrue(wrote)
        val topology = TopologyLoader.loadOrMaterialize(path)
        val head = requireNotNull(topology.heads["openrouter"]) { "head missing after add-model" }
        val provider = requireNotNull(topology.providers["openrouter"]) { "provider missing after add-model" }
        val ids = provider.models.map { it.id }
        assertTrue(OPUS in ids, "appended id missing from provider models: $ids")
        val catalog = provider.catalogFor(head)
        assertEquals(SONNET, catalog.pinnedModel)
        assertTrue(catalog.models.any { it.id == OPUS }, "appended id missing from catalog")
    }

    private fun seed(dir: Path): Path {
        val path = dir.resolve("splice.toml")
        Files.writeString(path, SEEDED)
        return path
    }

    private fun verb(
        selectKeys: ByteArray = byteArrayOf(),
        multiKeys: ByteArray = byteArrayOf(),
        selectTty: Boolean = false,
        multiTty: Boolean = false,
    ): AddModelVerb = AddModelVerb(
        select = SelectPrompt(
            keys = KeyReader(ByteArrayInputStream(selectKeys)),
            terminal = idle(selectTty),
            out = StringBuilder(),
            hasConsole = { selectTty },
        ),
        multi = MultiSelectPrompt(
            keys = KeyReader(ByteArrayInputStream(multiKeys)),
            terminal = idle(multiTty),
            out = StringBuilder(),
            hasConsole = { multiTty },
        ),
    )

    private fun idle(tty: Boolean): TerminalMode = TerminalMode(
        stty = SttyCommand { args ->
            if (args.getOrNull(1) == "-g") SttyResult(0, "SAVED") else SttyResult(0, "")
        },
        hasConsole = { tty },
        addHook = {},
        removeHook = {},
    )
}

private const val ESC: Byte = 27
private const val SPACE: Byte = 32
private const val ENTER: Byte = 13
private const val SONNET = "anthropic/claude-sonnet-5"
private const val OPUS = "anthropic/claude-opus-5"
private val SEEDED = """
    [daemon]
    control_port = 3096
    [providers.openrouter]
    dialect = "openai-chat"
    base_url = "https://openrouter.ai/api/v1"
    auth = { kind = "api-key", env = "OPENROUTER_API_KEY" }
    [[providers.openrouter.models]]
    id = "anthropic/claude-sonnet-5"
    label = "Claude Sonnet 5"
    context_window = 1000000
    [heads.openrouter]
    provider = "openrouter"
    port = 3101
    discovery_prefix = "claude-openrouter--"
    pinned_model = "anthropic/claude-sonnet-5"
    [heads.openrouter.claude]
    command = "claude-openrouter"
""".trimIndent() + "\n"
