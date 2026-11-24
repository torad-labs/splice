// Server mode (docs/specs/server-mode.md, F8 and F9, the laptop's half): what a launch of a command a team's server
// serves hands Claude Code. Every one names this laptop's splice in x-splice-client among splice's headers; on a
// server that relays telemetry, the session exports its metrics and logs to the relay over OTLP/HTTP, reads the key
// through an otelHeadersHelper, and logs no prompt; and a command whose managed settings set telemetry of their own gets
// none of splice's.
package splice.launch.recipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.ManagedSettings
import splice.client.resume.ResumeAcrossHeads
import splice.client.resume.TranscriptModelRewrite
import splice.client.resume.originals.TranscriptOriginals
import splice.core.GATEWAY_VERSION
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.launch.GatewayBearer
import splice.launch.HeadTrees
import splice.launch.LaunchGateway
import splice.launch.LaunchModels
import splice.launch.LaunchRecipe
import splice.launch.LaunchSignIn
import splice.launch.LaunchSpec
import splice.launch.LaunchTelemetry
import splice.launch.TeamServerLaunch
import splice.launch.launch
import java.nio.file.Files
import java.nio.file.Path

private const val CLIENT = "x-splice-client"
private const val RELAY = "https://gateway.team.example/server/otlp"

// why: the launch only writes the command; Claude Code is what runs it, so any command will do here
private const val OTEL_HELPER = "printf '{\"Authorization\":\"Bearer k\"}'"

class LaunchTeamServerTest(@param:TempDir private val tmp: Path) {
    private val shell = mapOf(
        "ANTHROPIC_CUSTOM_HEADERS" to "x-team-tag: blue",
        "OTEL_LOG_USER_PROMPTS" to "1",
        "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT" to "https://elsewhere.example",
    )

    private val service = LaunchService(
        ClaudeConfigMaterializer(tmp),
        envReader = EnvReader { shell[it] },
        resumeAcrossHeads = ResumeAcrossHeads(
            TranscriptModelRewrite(originals = TranscriptOriginals(StatePaths(baseOverride = tmp.resolve("state")))),
        ),
    ).apply {
        // why: a test never reads the machine's own managed settings (/etc/claude-code); this folder stands in for it.
        managed = ManagedSettings(platformDir = tmp.resolve("platform"))
    }

    @Test
    fun `a command on a server with a relay exports its metrics and logs there, and never a prompt`() {
        val recipe = launch(spec(TeamServerLaunch(LaunchTelemetry(RELAY, OTEL_HELPER))))
        assertEquals("1", recipe.env["CLAUDE_CODE_ENABLE_TELEMETRY"])
        assertEquals("otlp", recipe.env["OTEL_METRICS_EXPORTER"])
        assertEquals("otlp", recipe.env["OTEL_LOGS_EXPORTER"])
        assertEquals("http/protobuf", recipe.env["OTEL_EXPORTER_OTLP_PROTOCOL"])
        assertEquals(RELAY, recipe.env["OTEL_EXPORTER_OTLP_ENDPOINT"])
        assertEquals(OTEL_HELPER, settings()["otelHeadersHelper"]?.jsonPrimitive?.content)
        assertNull(recipe.env["OTEL_LOG_USER_PROMPTS"])
        assertTrue("OTEL_LOG_USER_PROMPTS" in recipe.unset, "the shell's own switch must not log prompts either")
        assertTrue(
            "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT" in recipe.unset,
            "an endpoint of the shell's own would get the key the helper prints",
        )
    }

    // #791: the recipe names splice's own headers alone; the launcher puts the shell's before them (ShimHeadersTest),
    // so the daemon's own environment's headers (x-team-tag here) are not the caller's and do not ride along.
    @Test
    fun `every launch of a server's command names this laptop's splice in its own headers`() {
        val lines = headerLines(launch(spec(TeamServerLaunch())))
        assertFalse("x-team-tag: blue" in lines)
        assertEquals(listOf("x-splice-client: $GATEWAY_VERSION"), lines.filter { it.substringBefore(':') == CLIENT })
    }

    @Test
    fun `a server with no relay gets the version and no telemetry`() {
        val recipe = launch(spec(TeamServerLaunch(telemetry = null)))
        assertEquals(1, clientLines(recipe).size)
        assertFalse(recipe.env.keys.any { it.startsWith("OTEL_") || it == "CLAUDE_CODE_ENABLE_TELEMETRY" })
        assertNull(settings()["otelHeadersHelper"])
        assertFalse("OTEL_LOG_USER_PROMPTS" in recipe.unset, "with no relay the shell's telemetry is its own")
    }

    @Test
    fun `a command on no team server sends no version and no telemetry`() {
        val recipe = launch(spec(teamServer = null))
        assertEquals(emptyList<String>(), clientLines(recipe))
        assertNull(recipe.env["OTEL_EXPORTER_OTLP_ENDPOINT"])
        assertNull(settings()["otelHeadersHelper"])
    }

    @Test
    fun `managed settings that set telemetry turn the relay off, and managed settings that set none leave it on`() {
        val relayed = spec(TeamServerLaunch(LaunchTelemetry(RELAY, OTEL_HELPER)))
        val managed = tree().resolve("remote-settings.json")
        Files.createDirectories(tree())
        Files.writeString(managed, """{"env":{"OTEL_EXPORTER_OTLP_ENDPOINT":"https://otel.corp.example"}}""")
        val off = launch(relayed)
        assertNull(off.env["OTEL_EXPORTER_OTLP_ENDPOINT"])
        assertNull(off.env["CLAUDE_CODE_ENABLE_TELEMETRY"])
        assertNull(settings()["otelHeadersHelper"])
        assertEquals(1, clientLines(off).size, "the version is no telemetry")
        Files.writeString(managed, """{"otelHeadersHelper":"corp-helper"}""")
        val ownHelper = launch(relayed)
        assertNull(ownHelper.env["OTEL_EXPORTER_OTLP_ENDPOINT"], "a helper of the organisation's own is its telemetry")
        Files.writeString(managed, """{"env":{"SOME_OTHER_SETTING":"1"}}""")
        assertEquals(RELAY, launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"])
        assertEquals(OTEL_HELPER, settings()["otelHeadersHelper"]?.jsonPrimitive?.content)
    }

    // #791: Claude Code also reads the machine's managed-settings.json and every managed-settings.d/*.json beside it as
    // managed settings, and telemetry set in any one of them is the organisation's.
    @Test
    fun `telemetry set in the machine's managed settings or in any of their drop-ins turns the relay off`() {
        val relayed = spec(TeamServerLaunch(LaunchTelemetry(RELAY, OTEL_HELPER)))
        val dropIns = platform().resolve("managed-settings.d")
        Files.createDirectories(dropIns)
        val platformFile = platform().resolve("managed-settings.json")
        Files.writeString(platformFile, """{"env":{"CLAUDE_CODE_ENABLE_TELEMETRY":"1"}}""")
        assertNull(launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"], "the platform file set telemetry")
        Files.writeString(platformFile, """{"permissions":{"deny":[]}}""")
        Files.writeString(dropIns.resolve("10-base.json"), """{"env":{"SOME_OTHER_SETTING":"1"}}""")
        Files.writeString(dropIns.resolve("20-otel.json"), """{"otelHeadersHelper":"corp-helper"}""")
        assertNull(launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"], "a later drop-in set a helper of its own")
        assertNull(settings()["otelHeadersHelper"])
        Files.delete(dropIns.resolve("20-otel.json"))
        Files.writeString(dropIns.resolve("20-otel.txt"), """{"env":{"OTEL_METRICS_EXPORTER":"otlp"}}""")
        Files.writeString(dropIns.resolve(".hidden.json"), """{"env":{"OTEL_METRICS_EXPORTER":"otlp"}}""")
        val endpoint = launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"]
        assertEquals(RELAY, endpoint, "only visible *.json files are drop-ins")
    }

    // #791: the files merge as Claude Code merges them, so a later drop-in that clears a telemetry key leaves it unset.
    @Test
    fun `a later drop-in that clears an earlier file's telemetry key leaves the relay on`() {
        val relayed = spec(TeamServerLaunch(LaunchTelemetry(RELAY, OTEL_HELPER)))
        val dropIns = platform().resolve("managed-settings.d")
        Files.createDirectories(dropIns)
        Files.writeString(platform().resolve("managed-settings.json"), """{"otelHeadersHelper":"corp-helper"}""")
        Files.writeString(dropIns.resolve("10-otel.json"), """{"env":{"OTEL_METRICS_EXPORTER":"otlp","KEEP":"1"}}""")
        Files.writeString(dropIns.resolve("20-off.json"), """{"otelHeadersHelper":null,"env":{"KEEP":"2"}}""")
        assertNull(launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"], "10-otel's variable still stands under 20-off")
        Files.writeString(dropIns.resolve("30-off.json"), """{"env":{"OTEL_METRICS_EXPORTER":null}}""")
        assertEquals(RELAY, launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"], "every telemetry key was cleared")
        Files.writeString(dropIns.resolve("40-on.json"), """{"env":{"CLAUDE_CODE_ENABLE_TELEMETRY":"1"}}""")
        assertNull(launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"], "the last drop-in set telemetry again")
    }

    // #791: a `null` or blank value is no setting, in the first file Claude Code reads as in any later one.
    @Test
    fun `a null or blank telemetry value sets no telemetry, in the first managed file as in a later one`() {
        val relayed = spec(TeamServerLaunch(LaunchTelemetry(RELAY, OTEL_HELPER)))
        val platformFile = platform().resolve("managed-settings.json")
        Files.createDirectories(platform())
        Files.writeString(platformFile, """{"env":{"OTEL_METRICS_EXPORTER":null,"CLAUDE_CODE_ENABLE_TELEMETRY":" "}}""")
        assertEquals(RELAY, launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"], "the first file's values are unset")
        Files.writeString(platformFile, """{"otelHeadersHelper":"","env":{}}""")
        assertEquals(RELAY, launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"], "a blank helper is no helper")
        Files.createDirectories(tree())
        Files.writeString(tree().resolve("remote-settings.json"), """{"env":{"OTEL_LOGS_EXPORTER":null}}""")
        assertEquals(RELAY, launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"], "nor in remote settings")
    }

    // #791: Claude Code reads one managed source, the remote settings when there are any, and never merges the
    // platform's files under them, so a helper the platform sets does not stand once remote settings replace it.
    @Test
    fun `remote settings replace the platform's managed settings rather than adding to them`() {
        val relayed = spec(TeamServerLaunch(LaunchTelemetry(RELAY, OTEL_HELPER)))
        val dropIns = platform().resolve("managed-settings.d")
        Files.createDirectories(dropIns)
        Files.writeString(dropIns.resolve("10-otel.json"), """{"otelHeadersHelper":"corp-helper"}""")
        assertNull(launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"], "the platform's helper stands alone")
        Files.createDirectories(tree())
        val remote = tree().resolve("remote-settings.json")
        Files.writeString(remote, """{"permissions":{"deny":[]}}""")
        assertEquals(RELAY, launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"], "remote settings omit the helper")
        assertEquals(OTEL_HELPER, settings()["otelHeadersHelper"]?.jsonPrimitive?.content)
        Files.writeString(remote, "{}")
        assertNull(launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"], "empty remote settings leave the platform's")
        Files.writeString(remote, """{"env":{"CLAUDE_CODE_ENABLE_TELEMETRY":"1"}}""")
        assertNull(launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"], "remote settings set telemetry of their own")
    }

    @Test
    fun `a managed settings file that is malformed, not an object or unreadable sets nothing`() {
        val relayed = spec(TeamServerLaunch(LaunchTelemetry(RELAY, OTEL_HELPER)))
        Files.createDirectories(platform().resolve("managed-settings.d"))
        Files.createDirectories(tree())
        Files.writeString(platform().resolve("managed-settings.json"), """{"env":{"OTEL_EXPORTER""")
        Files.writeString(platform().resolve("managed-settings.d/a.json"), """["CLAUDE_CODE_ENABLE_TELEMETRY"]""")
        // why: a folder where a file is expected cannot be read as one, as an unreadable file cannot.
        Files.createDirectories(platform().resolve("managed-settings.d/b.json"))
        Files.createDirectories(tree().resolve("remote-settings.json"))
        assertEquals(RELAY, launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"])
        assertEquals(OTEL_HELPER, settings()["otelHeadersHelper"]?.jsonPrimitive?.content)
    }

    // #791: Claude Code cannot read a remote-settings.json that is no JSON object, so the platform's files stay its one
    // managed source and their helper still stands; kotlinx alone would read an unquoted word as a value.
    @Test
    fun `a malformed or non-object remote settings file leaves the platform's managed settings the source`() {
        val relayed = spec(TeamServerLaunch(LaunchTelemetry(RELAY, OTEL_HELPER)))
        val dropIns = platform().resolve("managed-settings.d")
        Files.createDirectories(dropIns)
        Files.writeString(dropIns.resolve("10-otel.json"), """{"otelHeadersHelper":"corp-helper"}""")
        Files.createDirectories(tree())
        val remote = tree().resolve("remote-settings.json")
        val broken = listOf(
            """{"permissions":invalid}""",
            """{"permissions":{"deny":[tru]}}""",
            """{"permissions":01}""",
            """{"permissions":{"deny":[]}""",
            "{\"permissions\":{\"deny\":[\"Bash(rm\t-rf)\"]}}",
            """{"env":{},"env":invalid}""",
            """["permissions"]""",
            "\"permissions\"",
        )
        for (text in broken) {
            Files.writeString(remote, text)
            assertNull(launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"], "$text leaves the platform's helper")
        }
        Files.writeString(remote, """{"permissions":{"deny":[]},"cleanupPeriodDays":30,"x":null,"y":-1.5e3}""")
        assertEquals(RELAY, launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"], "valid remote settings replace it")
        // why: Claude Code's JSON.parse keeps the last of a repeated key, so these remote settings are its source.
        Files.writeString(remote, """{"env":123,"env":{}}""")
        assertEquals(RELAY, launch(relayed).env["OTEL_EXPORTER_OTLP_ENDPOINT"], "the last env stands, as Claude Code's")
    }

    private fun platform(): Path = tmp.resolve("platform")

    private fun launch(spec: LaunchSpec): LaunchRecipe =
        service.launch(spec, extraArgs = emptyList(), dangerouslySkipPermissions = false)

    private fun headerLines(recipe: LaunchRecipe): List<String> =
        recipe.env["ANTHROPIC_CUSTOM_HEADERS"].orEmpty().lines().map(String::trim).filter(String::isNotEmpty)

    private fun clientLines(recipe: LaunchRecipe): List<String> =
        headerLines(recipe).filter { it.substringBefore(':') == CLIENT }

    private fun tree(): Path = tmp.resolve(".claude-team")

    private fun settings() = Json.parseToJsonElement(Files.readString(tree().resolve("settings.json"))).jsonObject

    private fun spec(teamServer: TeamServerLaunch?) = LaunchSpec(
        trees = HeadTrees(tree()),
        models = LaunchModels(
            pinnedModel = "team-model",
            availableModelIds = listOf("team-model"),
            modelLabels = mapOf("team-model" to "team-model"),
            contextWindow = 200_000,
            modelOptionsCache = buildJsonObject { },
        ),
        signIn = LaunchSignIn(loginCommand = "splice login", signInLabel = "the team server"),
        gateway = LaunchGateway(
            statuslineCommand = "curl -s https://gateway.team.example/team/statusline",
            port = 0,
            bearer = GatewayBearer.Helper("cat /run/team.key"),
            apiTimeoutMs = 960_000,
            remote = "https://gateway.team.example/team",
        ),
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        teamServer = teamServer,
    )
}
