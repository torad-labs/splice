// NEW: LAN share, phase 2, milestone 6 (docs/specs/lan-share.md). The daemon's mirrors of another machine's sessions
// land where Claude Code itself keeps sessions, so its own ListAgents lists them: the record in the sessions registry
// under the Claude folder, and the socket in Claude Code 2.1.309's sockets folder, `$XDG_RUNTIME_DIR/cc-socks`, or
// `/tmp/cc-socks` where no runtime folder is set (a Mac; its live sessions' sockets sit there, Oct 23). A daemon stop
// withdraws them.
package splice.app.daemon

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import splice.sessions.mirror.MirrorLink
import splice.sessions.mirror.MirroredSession
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.listDirectoryEntries

// why: longer than any test here runs, so the mirror ends by the daemon's stop and never by its time.
private const val MIRRORS_LAST_MS = 3_600_000L

// why: a machine's time short enough that the daemon's sweep, once a second, ends it within the test.
private const val MIRRORS_BRIEF_MS = 500L

// why: past the brief time and several sweeps, however loaded the machine running the test is.
private const val MIRRORS_GONE_MS = 15_000L

// why: how often the test reads the registry while it waits.
private const val MIRRORS_POLL_MS = 100L

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
            val link = MirrorLink(MirrorCarrier { _, _, _ -> MirrorCarried(null) }, MIRRORS_LAST_MS)
            shared.mirrors.mirror("far", listOf(MirroredSession("id-1", "rig-lead")), link)
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

    // The round-4 review: the daemon's own mirrors end by time, as SessionMirrors' rule says, with no stop. A machine
    // that stopped looking leaves the registry once the time its last look gave its mirrors is past.
    @Test
    fun `the daemon withdraws a machine's mirrors once their time is past, with no stop`() {
        val vars = env("XDG_RUNTIME_DIR" to Files.createDirectories(tmp.resolve("run")).toString())
        val reader = EnvReader { vars[it] }
        val shared = SharedSessions(reader, StatePaths(envReader = reader)) { }
        val registry = Path.of(vars.getValue("HOME"), ".claude", "sessions")
        try {
            val link = MirrorLink(MirrorCarrier { _, _, _ -> MirrorCarried(null) }, MIRRORS_BRIEF_MS)
            shared.mirrors.mirror("far", listOf(MirroredSession("id-1", "rig-lead")), link)
            assertEquals(1, registry.listDirectoryEntries("*.json").size, "far's session is mirrored")
            val gone = runBlocking {
                val until = System.currentTimeMillis() + MIRRORS_GONE_MS
                while (registry.listDirectoryEntries("*.json").isNotEmpty() && System.currentTimeMillis() < until) {
                    delay(MIRRORS_POLL_MS)
                }
                registry.listDirectoryEntries("*.json").isEmpty()
            }
            assertTrue(gone, "and withdrawn once its time is past, while the daemon runs")
        } finally {
            shared.stop()
        }
    }
}
