// NEW: LAN share, phase 2, milestone 6 (docs/specs/lan-share.md). The daemon's mirrors of another machine's sessions
// land where Claude Code itself keeps sessions, so its own ListAgents lists them: the record in the sessions registry
// under the Claude folder, and the socket in Claude Code 2.1.309's sockets folder, `$XDG_RUNTIME_DIR/cc-socks`, or
// `/tmp/cc-socks` where no runtime folder is set (a Mac; its live sessions' sockets sit there, Oct 23). A daemon stop
// withdraws them.
package splice.app.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.sessions.mirror.MirrorCarried
import splice.sessions.mirror.MirrorCarrier
import splice.sessions.mirror.MirroredSession
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.listDirectoryEntries

class SharedSessionsTest {

    @TempDir
    lateinit var tmp: Path

    /** The record a daemon in [env] writes for a session `rig-lead` of a machine `far`, read before its stop, and
     *  whether its stop left the registry empty. */
    private fun mirrored(env: Map<String, String>): Pair<JsonObject, Boolean> {
        val reader = EnvReader { env[it] }
        val shared = SharedSessions(reader, StatePaths(envReader = reader)) { }
        val registry = Path.of(env.getValue("HOME"), ".claude", "sessions")
        val record = try {
            val carrier = MirrorCarrier { _, _, _ -> MirrorCarried(null) }
            shared.mirrors.mirror("far", listOf(MirroredSession("id-1", "rig-lead")), carrier)
            Json.parseToJsonElement(Files.readString(registry.listDirectoryEntries("*.json").single())).jsonObject
        } finally {
            shared.stop()
        }
        return record to registry.listDirectoryEntries("*.json").isEmpty()
    }

    private fun env(vararg more: Pair<String, String>): Map<String, String> = mapOf(
        "HOME" to tmp.resolve("h").toString(),
        "SPLICE_STATE_DIR" to tmp.resolve("state").toString(),
        "SPLICE_CONFIG" to tmp.resolve("splice.toml").toString(),
    ) + more

    private fun socketFolder(record: JsonObject): Path =
        Paths.get(record.getValue("messagingSocketPath").jsonPrimitive.content).parent

    @Test
    fun `a mirror is registered in the Claude folder and listens in the runtime folder's cc-socks`() {
        val run = Files.createDirectories(tmp.resolve("run"))
        val (record, withdrawn) = mirrored(env("XDG_RUNTIME_DIR" to run.toString()))
        assertEquals("far/rig-lead", record.getValue("name").jsonPrimitive.content)
        assertEquals(run.resolve("cc-socks"), socketFolder(record))
        assertTrue(withdrawn, "a daemon stop withdraws its mirrors")
    }

    @Test
    fun `with no runtime folder a mirror listens in cc-socks under tmp, where Claude Code puts its own`() {
        val (record, _) = mirrored(env())
        assertEquals(Paths.get("/tmp", "cc-socks"), socketFolder(record))
    }
}
