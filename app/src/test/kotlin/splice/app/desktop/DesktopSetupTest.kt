// What splice changes in Claude Desktop's library, on fixture homes: what `on` puts in, `off` and `splice uninstall`
// take back out and nothing else, whatever the person did in Desktop meanwhile; and `splice desktop credential`, which
// prints the desktop key to the program that runs it and never to a terminal.
package splice.app.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.desktop.DesktopCredential
import splice.client.desktop.DesktopAppConfig
import splice.client.desktop.DesktopManagedPolicy
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class DesktopSetupTest {
    @TempDir
    lateinit var tmp: Path

    private val env by lazy {
        val vars = mapOf(
            "XDG_CONFIG_HOME" to tmp.resolve("config").toString(),
            "HOME" to tmp.resolve("home").toString(),
            "SPLICE_STATE_DIR" to tmp.resolve("state").toString(),
        )
        EnvReader { vars[it] }
    }
    private val library by lazy { tmp.resolve("config/Claude-3p/configLibrary") }
    private val records by lazy { DesktopRecords(tmp.resolve("state/desktop.json")) {} }
    private val app by lazy {
        DesktopAppMode(records, DesktopAppLibrary(DesktopAppConfig(env)), DesktopCredentialHelper(tmp.resolve("state")))
    }

    @Test
    fun `on in a Desktop that never had a library makes one, and off leaves no trace of it`() = managedHere {
        app.putIn("http://127.0.0.1:3096/door")
        records.update { it.copy(on = true) }
        assertEquals(DesktopMode.APP, app.mode())
        assertTrue(Files.isExecutable(tmp.resolve("state/desktop-credential")))
        app.takeOut()
        assertFalse(Files.exists(library), "the folder splice made is gone")
        assertFalse(Files.exists(tmp.resolve("config/Claude-3p/configLibrary/_meta.json")))
        assertFalse(Files.exists(tmp.resolve("state/desktop-credential")))
    }

    @Test
    fun `a configuration the person applied meanwhile stays applied when splice's comes out`() = managedHere {
        app.putIn("http://127.0.0.1:3096/door")
        records.update { it.copy(on = true) }
        val theirs = "5a5a5a5a-2222-4c2d-9e8f-123456789abc"
        val meta = library.resolve("_meta.json")
        val ours = records.get().appId
        Files.writeString(library.resolve("$theirs.json"), """{"inferenceProvider":"vertex"}""")
        Files.writeString(
            meta,
            """{"appliedId":"$theirs","entries":[{"id":"$ours","name":"splice"},{"id":"$theirs","name":"Vertex"}]}""",
        )
        assertEquals(DesktopMode.OTHER, app.mode())
        app.takeOut()
        assertEquals("""{"appliedId":"$theirs","entries":[{"id":"$theirs","name":"Vertex"}]}""", compact(meta))
        assertTrue(Files.exists(library.resolve("$theirs.json")))
        assertFalse(Files.exists(library.resolve("$ours.json")))
    }

    @Test
    fun `a managed configuration refuses splice's, which then changes nothing`() = managedHere {
        Files.createDirectories(tmp.resolve("etc"))
        Files.writeString(tmp.resolve("etc/managed-settings.json"), "{}")
        assertTrue(app.refusal().orEmpty().contains("a managed configuration decides"), app.refusal())
        assertFalse(Files.exists(library))
    }

    @Test
    fun `splice uninstall takes splice's configuration out of Desktop with no daemon`() = managedHere {
        app.putIn("http://127.0.0.1:3096/door")
        records.update { it.copy(on = true) }
        val said = mutableListOf<String>()
        DesktopRetire(env, TerminalOutput { said += it }).retire()
        assertFalse(Files.exists(library))
        assertFalse(DesktopRecords(tmp.resolve("state/desktop.json")) {}.get().on)
        assertTrue(said.single().contains("took splice's configuration out of Claude Desktop"), said.toString())
    }

    @Test
    fun `the credential helper prints the desktop key to a program and nothing to a terminal`() {
        val keyFile = tmp.resolve("state/desktop.key")
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        assertEquals(1, DesktopCredential({ out += it }, { err += it }, { true }).run(keyFile))
        assertTrue(out.isEmpty())
        assertEquals(0, DesktopCredential({ out += it }, { err += it }, { false }).run(keyFile))
        assertEquals(listOf(DesktopKey(keyFile) {}.get()), out)
    }

    /** [block] with the managed configuration looked for in this test's fixture home, never the machine's. */
    private fun managedHere(block: () -> Unit) =
        DesktopManagedPolicy.within(tmp.resolve("etc/managed-settings.json"), block)

    private fun compact(file: Path): String = Files.readString(file).replace(Regex("\\s"), "")
}
