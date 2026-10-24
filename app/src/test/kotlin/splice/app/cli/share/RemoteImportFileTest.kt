// NEW: LAN share, round 11 of PR #419's review. The goal: importing a sharing machine again never locks this machine
// out of it. A join replaces the token this machine holds over there, so whatever can stop an import after the join
// must be known before it: an import onto a splice.toml that does not load is refused before it joins, and the token
// this machine already holds keeps working. A machine with no splice.toml yet is a first run, and the import writes the
// starter file with the machine in it, as `splice remote` did in phase 1. A real sharing listener on loopback, and
// RemoteImport as `splice remote add` and the Sharing page run it, over a temp splice.toml and state folder. Since
// round 15 of PR #419's review, a second machine that goes by the same name is a machine of its own: it is imported
// beside the first, whose address, pin and token stay its own.
package splice.app.cli.share

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.configuration.add.DaemonRestart
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.share.ShareHostName
import splice.share.ShareState
import splice.share.remote.RemoteFound
import splice.share.remote.RemoteJoin
import splice.share.remote.RemoteJoined
import splice.share.remote.RemoteNames
import splice.share.remote.RemoteTrust
import splice.share.remote.RemotesStore
import splice.terminal.ConfirmPrompt
import splice.topology.TopologyLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

class RemoteImportFileTest {
    @TempDir
    lateinit var tmp: Path

    private val said = mutableListOf<String>()

    /** The machine importing: its splice.toml and state folder in the temp folder. */
    private val here by lazy {
        val vars = mapOf(
            "SPLICE_CONFIG" to tmp.resolve("here/splice.toml").toString(),
            "SPLICE_STATE_DIR" to tmp.resolve("here/state").toString(),
            "HOME" to tmp.resolve("here").toString(),
        )
        EnvReader { vars[it] }
    }

    private val config: Path get() = TopologyLoader.configPath(here)

    /** [test] with a machine sharing on loopback, found as `splice remote add 127.0.0.1:<port>` finds it. */
    private fun sharing(test: (RemoteFound) -> Unit) = LoopbackSharing(tmp, "there").use { test(it.found) }

    private val store by lazy { RemotesStore(ShareState(StatePaths(envReader = here)).remotesFile) }

    private fun import(machine: RemoteFound): Boolean = runBlocking {
        val lines = TerminalOutput { said += it }
        val keep = ConfirmPrompt { _, default -> default }
        RemoteImport(lines, lines, here, keep, DaemonRestart { true }).import(machine)
    }

    /** What the sharing machine answers a guarded read made with [token]: 200 while it is this machine's, 401 after. */
    private fun answered(machine: RemoteFound, token: String): Int {
        val pin = checkNotNull(machine.info.fingerprint)
        val http = HttpClient.newBuilder().sslContext(RemoteTrust(pin, machine.info.server).context).build()
        val asked = HttpRequest.newBuilder(URI.create("https://127.0.0.1:${machine.port}/share/v1/health"))
            .header("Authorization", "Bearer $token")
            .build()
        return http.send(asked, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `an import onto a splice toml that does not load is refused before it joins, and the old token still works`() {
        sharing { machine ->
            val held = RemoteJoin(ShareHostName().short()).join(machine) as RemoteJoined.Joined
            Files.createDirectories(config.parent)
            Files.writeString(config, "[heads\nthis is not toml\n")

            val imported = import(machine)

            assertFalse(imported, "nothing was imported: $said")
            assertTrue(said.any { "does not load" in it }, "it says why: $said")
            assertEquals(200, answered(machine, held.token), "the token this machine held still works")
        }
    }

    @Test
    fun `a first import on a machine with no splice toml writes one that names the machine, and keeps its token`() {
        sharing { machine ->
            assertFalse(Files.exists(config), "a first run: no splice.toml yet")

            import(machine)

            val name = RemoteNames.of(machine.info.server)
            val written = TopologyLoader.parse(Files.readString(config))
            assertTrue(name in written.remotes, "splice.toml names the machine: $said")
            val token = checkNotNull(store.token(name)) {
                "the token is kept in this machine's share store: $said"
            }
            assertEquals(200, answered(machine, token), "and it is the one the sharing machine took")
        }
    }

    @Test
    fun `a second machine of the same name is imported beside the first, which keeps its address, pin and token`() {
        LoopbackSharing(tmp, "there").use { first ->
            LoopbackSharing(tmp, "other").use { second ->
                assertEquals(first.found.info.server, second.found.info.server, "two machines of one name")

                import(first.found)
                import(second.found)

                val written = TopologyLoader.parse(Files.readString(config)).remotes
                val firsts = written.filterValues { it.fingerprint == first.found.info.fingerprint }
                val seconds = written.filterValues { it.fingerprint == second.found.info.fingerprint }
                assertEquals(1, firsts.size, "the first is still joined, pinned to its own certificate: $written $said")
                assertEquals(1, seconds.size, "the second is joined beside it: $written $said")
                val (firstName, firstEntry) = firsts.entries.single()
                assertTrue(firstEntry.url.endsWith(":${first.port}"), "the first keeps its address: ${firstEntry.url}")
                val firstToken = checkNotNull(store.token(firstName)) { "the first's token is kept: $said" }
                assertEquals(200, answered(first.found, firstToken), "and it is the one the first machine took")
                val secondToken = checkNotNull(store.token(seconds.keys.single())) { "the second's is kept: $said" }
                assertEquals(200, answered(second.found, secondToken), "the second has its own")
            }
        }
    }
}
