// NEW: V4-146 — the production wiring end to end: real McpGlobalRead + real McpSharing + the five
// real readers, over a temp home (never the operator's real files). Proves the app-side assembly
// agrees with the unit-level classification McpInventoryTest already covers.
package splice.app

import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpDisposition
import splice.client.mcp.McpSharing
import splice.client.mcp.McpSourceKind
import splice.core.util.LogSink
import splice.launch.HeadTrees
import splice.launch.LaunchSpec
import splice.launch.recipe.LaunchService
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class McpInventoryWiringTest {

    private fun sharing() = McpSharing(
        enabled = true,
        exclude = emptySet(),
        endpointPrefix = "http://127.0.0.1:1/mcp/",
        bearer = { "k" },
        isDirectory = DirectoryProbe { false },
    )

    @Test
    fun `production wiring migrates the canonical home's own server, real filesystem roots`(@TempDir home: Path) {
        home.resolve(".claude.json").writeText("""{"mcpServers":{"exa":{"command":"npx"}}}""")
        val report = McpInventoryWiring(home, sharing(), McpGlobalRead(home), emptyList()).inventory.census()
        val d = report.dispositioned.single { it.registration.name == "exa" }
        assertEquals(McpDisposition.MIGRATED, d.disposition)
        assertEquals(McpSourceKind.entries.toSet(), report.kinds.map { it.kind }.toSet())
    }

    @Test
    fun `production wiring also sees a plugin-declared server, excluded with a written reason`(@TempDir home: Path) {
        home.resolve(".claude.json").writeText("""{"mcpServers":{}}""")
        val plugin = home.resolve(".claude").resolve("plugins").resolve("cache").resolve("mp")
            .resolve("desktop-commander").resolve("1.0.0").resolve(".claude-plugin")
        plugin.createDirectories()
        plugin.resolve("plugin.json").writeText(
            """{"name":"desktop-commander","mcpServers":{"desktop-commander":{"command":"npx","args":["-y","x"]}}}""",
        )
        val report = McpInventoryWiring(home, sharing(), McpGlobalRead(home), emptyList()).inventory.census()
        val d = report.dispositioned.single { it.registration.name == "desktop-commander" }
        assertEquals(McpDisposition.EXCLUDED, d.disposition)
        assertTrue(d.reason.contains("plugin"), d.reason)
    }

    /** v0.4.0 mcp review: the census read every `~/.claude-<head>` as another identity's home, so each
     *  server the materializer copied into a head was reported "not this daemon's to rewrite" once per
     *  head. Both heads here are launched through the real materializer: `bonsai` shares mcps and gets
     *  the canonical servers rewritten into its own `.claude.json`; `quiet` isolates mcps and keeps
     *  its own server, which hosting never reads. */
    @Test
    fun `a head's materialized copy takes the canonical plan's answer, a head isolating mcps stays its own`(
        @TempDir home: Path,
    ) {
        home.resolve(".claude.json").writeText("""{"mcpServers":{"exa":{"command":"npx"}}}""")
        val quiet = home.resolve(".claude-quiet").createDirectories()
        quiet.resolve(".claude.json").writeText("""{"mcpServers":{"own":{"command":"npx"}}}""")
        val bonsai = home.resolve(".claude-bonsai")
        val sharing = sharing()
        val heads = listOf(
            spec(bonsai, "bonsai", ClaudePolicy(share = setOf("mcps"), isolate = emptySet())),
            spec(quiet, "quiet", ClaudePolicy(share = setOf("mcps"), isolate = setOf("mcp"))),
        )
        val materializer = ClaudeConfigMaterializer(home, log = LogSink { }, mcpRewrite = sharing.rewrite())
        val service = LaunchService(materializer)
        heads.forEach { service.launch(it, emptyList(), dangerouslySkipPermissions = false) }
        val copied = bonsai.resolve(".claude.json").readText()
        assertTrue(copied.contains("127.0.0.1:1/mcp/exa"), "setup: bonsai holds the materialized copy")

        val report = McpInventoryWiring(home, sharing, McpGlobalRead(home), heads).inventory.census()
        fun at(dir: Path, name: String) = report.dispositioned.single {
            it.registration.sourceFile == dir.resolve(".claude.json") && it.registration.name == name
        }
        assertEquals(McpDisposition.MIGRATED, at(home, "exa").disposition)
        val copy = at(bonsai, "exa")
        assertEquals(McpDisposition.MIGRATED, copy.disposition, copy.reason)
        assertTrue(copy.reason.contains("head's copy"), copy.reason)
        val own = at(quiet, "own")
        assertEquals(McpDisposition.EXCLUDED, own.disposition)
        assertTrue(own.reason.contains("different Claude Code identity"), own.reason)

        // The operator scopes exa to a directory after bonsai launched: hosting now leaves it as declared,
        // and bonsai still runs the http copy it was given until its next launch rewrites it.
        home.resolve(".claude.json").writeText("""{"mcpServers":{"exa":{"command":"npx","cwd":"/x"}}}""")
        val later = McpInventoryWiring(home, sharing, McpGlobalRead(home), heads).inventory.census()
        val stale = later.dispositioned.single {
            it.registration.sourceFile == bonsai.resolve(".claude.json") && it.registration.name == "exa"
        }
        assertEquals(McpDisposition.EXCLUDED, stale.disposition, stale.reason)
        assertTrue(stale.reason.contains("stale"), stale.reason)
    }

    // The wiring exists at ONE call site, so it gets a pin where the compiler cannot stand (the shape of
    // CompactionWiringPinTest): `materializedHomes` has no default, but ControlPlane passing an empty
    // list would compile, pass every test above, and report every head copy as another identity's.
    @Test
    fun `the control plane hands the census every head's launch spec`() {
        val source = source("app/src/main/kotlin/splice/app/ControlPlane.kt")
        assertTrue(
            source.contains("mcpHost(home, sharing, heads.values.mapNotNull { it.launchSpec })"),
            "ControlPlane must pass the heads' launch specs to mcpHost, or the census cannot tell a head's copy",
        )
        assertTrue(
            source.contains("McpInventoryWiring(home, sharing, globalRead, launchSpecs)"),
            "mcpHost must hand those launch specs to McpInventoryWiring",
        )
    }

    /** Found by walking up from the working directory: under Gradle the cwd is the module dir and from an
     *  IDE it is the repo root, so neither is assumed. */
    private fun source(relative: String): String {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(relative)
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent
        }
        error("$relative not found above ${Paths.get("").toAbsolutePath()}")
    }

    private fun spec(configDir: Path, head: String, policy: ClaudePolicy) = LaunchSpec(
        trees = HeadTrees(configDir),
        pinnedModel = "m",
        availableModelIds = listOf("m"),
        modelLabels = mapOf("m" to "m"),
        contextWindow = 272_000,
        apiTimeoutMs = 960_000,
        modelOptionsCache = buildJsonObject { },
        statuslineCommand = "true",
        loginCommand = "claude-$head login",
        signInLabel = "Head $head",
        headKey = head,
        policy = policy,
        port = 3099,
        inferenceToken = "test-inference-token",
    )
}
