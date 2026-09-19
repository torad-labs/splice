// NEW: V4-162 — TopologyWindows, the re-read that makes a context_window edit live.
//
// WHAT IT PINS, one test per outcome the watcher can reach:
//   · a window-only edit is live on the attached catalog and leaves nothing stale; the running
//     digest moves to the edited bytes, which is what /health publishes and doctor compares;
//   · an edit to anything else still reads stale, and its windows apply anyway;
//   · a comment-only edit is not stale and moves no window;
//   · a file that does not parse keeps the windows in force (NEVER-BELOW-STATUS-QUO) and says so;
//   · a local-runtime head's new window serves only after its runtime accepts it, and a refusal
//     keeps the old one. The runtime's answer is a gate the test opens, and the question runs on a
//     scope the test joins, so nothing here waits on the wall clock.
package campaign.v4162

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.HeadCatalogs
import splice.app.LocalWindowCheck
import splice.app.TopologyLoader
import splice.app.TopologyWindows
import splice.app.provider.ProviderBuild
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.turn.WatchdogBudget
import splice.spi.LifecycleScope
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.time.Duration.Companion.seconds

private const val BIG = "id = \"big\"\nlabel = \"Big\"\ncontext_window = 131072"
private const val BONSAI = "id = \"bonsai\"\nlabel = \"Bonsai\"\ncontext_window = 131072"

// The local provider is local by the loopback rule (LocalProviderRule), the remote one is not.
private const val BOOT = """
[providers.remote]
dialect = "openai-chat"
base_url = "https://api.example.test/v1"
auth = { kind = "api-key", env = "REMOTE_KEY" }

[[providers.remote.models]]
$BIG

[[providers.remote.models]]
id = "small"
label = "Small"
context_window = 65536

[providers.local]
dialect = "openai-chat"
base_url = "http://127.0.0.1:8099/v1"
auth = { kind = "api-key", env = "LOCAL_KEY" }

[[providers.local.models]]
$BONSAI

[heads.remote]
provider = "remote"
port = 3201
discovery_prefix = "claude-remote--"
pinned_model = "big"

[heads.local]
provider = "local"
port = 3202
discovery_prefix = "claude-local--"
pinned_model = "bonsai"
"""

class TopologyWindowsTest {

    @TempDir
    lateinit var tmp: Path

    private val lines = mutableListOf<String>()
    private val scope = LifecycleScope(Dispatchers.Default)
    private val answer = CompletableDeferred<List<String>>()
    private var stamp = 1_000L

    private val file by lazy { tmp.resolve("splice.toml").also { Files.writeString(it, BOOT) } }

    private val windows by lazy {
        TopologyWindows(
            file,
            TopologyLoader.parse(BOOT),
            TopologyLoader.sha256Hex(BOOT.toByteArray()),
            HeadCatalogs { _, head, provider, _ -> provider.catalogFor(head) },
            { lines += it },
            LocalWindowCheck { _, _, _ -> runBlocking { answer.await() } },
            scope,
        )
    }

    /** The two heads' catalogs as the daemon attaches them: provider, launch spec and statusline
     *  all hold these. */
    private val catalogs: Map<String, ModelCatalog> by lazy {
        val boot = TopologyLoader.parse(BOOT)
        val config = ConfigService(StatePaths(baseOverride = tmp.resolve("state")), envReader = { null })
        boot.heads.mapValues { (key, head) ->
            val provider = boot.providers.getValue(head.provider)
            val build = ProviderBuild(
                key = key,
                head = head,
                providerCfg = provider,
                catalog = provider.catalogFor(head),
                watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
                cfg = config.getConfig(key),
                loginCommand = "",
            )
            windows.attach(build, legacyKnobsGovern = false).catalog
        }
    }

    /** Save [text] the way an editor does: new bytes and a modification time that moved. */
    private fun save(text: String) {
        Files.writeString(file, text)
        stamp += 1_000
        Files.setLastModifiedTime(file, FileTime.fromMillis(stamp))
    }

    private fun settle() = runBlocking {
        scope.coroutineContext[Job]?.children?.toList().orEmpty().joinAll()
    }

    private fun logged(text: String) = assertTrue(lines.any { text in it }, lines.toString())

    @Test
    fun `a window-only edit is live and leaves nothing stale`() {
        val remote = catalogs.getValue("remote")
        assertEquals(131_072, remote.clientLaunchWindow)
        val edited = BOOT.replace(BIG, BIG.replace("131072", "245760"))
        save(edited)

        assertEquals(245_760, remote.clientLaunchWindow)
        assertEquals(245_760, remote.contextWindowFor("big"))
        assertEquals(65_536, remote.contextWindowFor("small"))
        assertEquals(131_072.0 / 245_760, remote.usageScale("big", sessionWindow = 131_072))
        assertEquals(245_760, remote.live().models.first().contextWindow)
        assertFalse(windows.stale())
        assertEquals(TopologyLoader.sha256Hex(edited.toByteArray()), windows.digest())
        logged("[remote] context windows re-read from splice.toml: big 131072 -> 245760")
    }

    @Test
    fun `an edit to anything else stays stale while its windows still apply`() {
        val remote = catalogs.getValue("remote")
        val bootDigest = windows.digest()
        save(BOOT.replace("port = 3201", "port = 3299").replace("= 65536", "= 98304"))

        assertEquals(98_304, remote.contextWindowFor("small"))
        assertTrue(windows.stale())
        assertEquals(bootDigest, windows.digest())
    }

    @Test
    fun `a comment-only edit is not stale and moves no window`() {
        val remote = catalogs.getValue("remote")
        val edited = "# the operator's note\n$BOOT"
        save(edited)

        assertEquals(131_072, remote.clientLaunchWindow)
        assertFalse(windows.stale())
        assertEquals(TopologyLoader.sha256Hex(edited.toByteArray()), windows.digest())
    }

    @Test
    fun `a file that does not parse keeps the windows in force`() {
        val remote = catalogs.getValue("remote")
        save(BOOT.replace(BIG, BIG.replace("131072", "[245760")))

        assertEquals(131_072, remote.clientLaunchWindow)
        assertTrue(windows.stale())
        logged("splice.toml does not parse")
    }

    @Test
    fun `a local head's new window serves once its runtime accepts it`() {
        val local = catalogs.getValue("local")
        save(BOOT.replace(BONSAI, BONSAI.replace("131072", "245760")))

        assertEquals(131_072, local.clientLaunchWindow)
        assertTrue(windows.stale())
        answer.complete(emptyList())
        settle()

        assertEquals(245_760, local.clientLaunchWindow)
        assertFalse(windows.stale())
        logged("[local] context windows re-read from splice.toml: bonsai 131072 -> 245760")
    }

    @Test
    fun `a local runtime that refuses the new window keeps the old one`() {
        val local = catalogs.getValue("local")
        save(BOOT.replace(BONSAI, BONSAI.replace("131072", "245760")))

        assertEquals(131_072, local.clientLaunchWindow)
        answer.complete(listOf("'bonsai': declares context_window 245760, the runtime serves 131072"))
        settle()

        assertEquals(131_072, local.clientLaunchWindow)
        assertTrue(windows.stale())
        logged("[local] local runtime refuses the edited context window ('bonsai': declares")
    }
}
