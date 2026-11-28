// What splice changes in Claude Desktop's library, on fixture homes: what `on` puts in, `off` and `splice uninstall`
// take back out and nothing else, whatever the person did in Desktop meanwhile; and `splice desktop credential`, which
// prints the desktop key to the program that runs it and never to a terminal.
package splice.app.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.desktop.DesktopCredential
import splice.app.desktop.record.DesktopKey
import splice.app.desktop.record.DesktopRecords
import splice.app.desktop.setup.DesktopAppLibrary
import splice.app.desktop.setup.DesktopAppMode
import splice.app.desktop.setup.DesktopManagedPolicy
import splice.app.desktop.setup.DesktopMode
import splice.app.desktop.setup.DesktopPolicyDefaults
import splice.app.desktop.setup.DesktopPrograms
import splice.app.desktop.setup.DesktopRetire
import splice.client.desktop.DesktopAppConfig
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
    private val library by lazy { DesktopAppConfig(env).library }
    private val records by lazy { DesktopRecords(tmp.resolve("state/desktop.json")) {} }
    private val app by lazy {
        DesktopAppMode(records, DesktopAppLibrary(DesktopAppConfig(env)), DesktopPrograms(tmp.resolve("state")))
    }

    @Test
    fun `on in a Desktop that never had a library makes one, and off leaves no trace of it`() = managedHere {
        app.putIn("http://127.0.0.1:3096/door")
        records.update { it.copy(on = true) }
        assertEquals(DesktopMode.APP, app.mode())
        assertTrue(Files.isExecutable(tmp.resolve("state/desktop-credential")))
        val launch = tmp.resolve("state/desktop-launch")
        assertTrue(Files.isExecutable(launch), "a managed processWrapper can name the launcher")
        assertTrue(Files.isRegularFile(tmp.resolve("state/desktop-hooks.json")))
        app.takeOut()
        assertFalse(Files.exists(library), "the folder splice made is gone")
        assertFalse(Files.exists(library.resolve("_meta.json")))
        assertFalse(Files.exists(tmp.resolve("state/desktop-credential")))
        assertFalse(Files.exists(tmp.resolve("state/desktop-launch")))
        assertFalse(Files.exists(tmp.resolve("state/desktop-hooks.json")))
        assertFalse(Files.exists(tmp.resolve("state/door-login/splice-desktop-foreground.sh")))
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
    fun `publishing catalog models keeps discovery and only updates the applied gateway configuration`() = managedHere {
        val door = "http://127.0.0.1:3096/door"
        app.putIn(door)
        val desktop = DesktopAppLibrary(DesktopAppConfig(env))
        val models = buildJsonArray { add(buildJsonObject { put("name", "claude-oai--6.2-sol") }) }

        assertTrue(desktop.publish(door, models))
        val file = library.resolve("${records.get().appId}.json")
        val updated = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals("true", updated.getValue("modelDiscoveryEnabled").jsonPrimitive.content)
        assertEquals(models, updated.getValue("inferenceModels").jsonArray)
        assertFalse(desktop.publish(door, models))
        assertFalse(desktop.publish("http://127.0.0.1:3096/other", models))
    }

    @Test
    fun `on gives Desktop splice's default policy with what the person set up in Claude Code over it`() = managedHere {
        val door = "http://127.0.0.1:3096/door"
        val claude = Files.createDirectories(tmp.resolve("home/.claude/plugins")).parent
        Files.writeString(
            claude.resolve("settings.json"),
            """{"effortLevel":"medium","permissions":{"defaultMode":"acceptEdits","deny":["Bash"]},""" +
                """"promptSuggestionEnabled":true,""" +
                """"extraKnownMarketplaces":{"extra":{"source":{"source":"github","repo":"acme/extra"}}}}""",
        )
        Files.writeString(
            claude.resolve("settings.local.json"),
            """{"effortLevel":"xhigh","promptSuggestionEnabled":false,""" +
                """"extraKnownMarketplaces":{"local-extra":{"source":""" +
                """{"source":"git","url":"https://git.acme/p.git"}}}}""",
        )
        Files.writeString(
            claude.resolve("plugins/known_marketplaces.json"),
            """{"official":{"source":{"source":"github","repo":"Anthropics/claude-plugins-official"}},""" +
                """"team":{"source":{"source":"github","repo":"acme/tools","ref":"v2"}},""" +
                """"local":{"source":{"source":"directory","path":"/srv/mods"}},""" +
                """"big":{"source":{"source":"github","repo":"acme/mono","sparsePaths":[".claude-plugin"]}}}""",
        )

        app.putIn(door)
        val config = read(library.resolve("${records.get().appId}.json"))
        assertEquals("xhigh", config.getValue("defaultModelEffort").jsonPrimitive.content, "settings.local wins")
        assertEquals("acceptEdits", config.getValue("codeDefaultPermissionMode").jsonPrimitive.content)
        assertEquals("false", config.getValue("promptSuggestionEnabled").jsonPrimitive.content)
        assertEquals("true", config.getValue("projectsEnabled").jsonPrimitive.content)
        assertEquals("true", config.getValue("claudeCodeProcessWrapperEnabled").jsonPrimitive.content)
        assertEquals("true", config.getValue("mcpPersistentAlwaysAllowEnabled").jsonPrimitive.content)
        assertEquals(
            """[{"source":"github","repo":"anthropics/claude-plugins-official"},""" +
                """{"source":"github","repo":"acme/tools","ref":"v2"},""" +
                """{"source":"github","repo":"acme/extra"},""" +
                """{"source":"git","url":"https://git.acme/p.git"}]""",
            config.getValue("allowedPluginMarketplaces").toString(),
            "the default's marketplace once, then theirs from known_marketplaces and settings(.local)",
        )
        assertEquals("""["*"]""", config.getValue("coworkEgressAllowedHosts").toString(), "the default policy is in it")
        assertEquals(door, config.getValue("inferenceGatewayBaseUrl").jsonPrimitive.content)
        assertEquals("true", config.getValue("disableDeploymentModeChooser").jsonPrimitive.content)
        assertFalse("managedMcpServers" in config, "their MCP servers stay Claude Code's own")
        assertFalse("disabledBuiltinTools" in config, "and so do their permission rules")
    }

    @Test
    fun `a configuration the person made on the door is filled where it says nothing and kept where it speaks`() =
        managedHere {
            val door = "http://127.0.0.1:3096/door"
            val theirs = "5a5a5a5a-2222-4c2d-9e8f-123456789abc"
            Files.createDirectories(library)
            Files.writeString(
                library.resolve("$theirs.json"),
                """{"inferenceProvider":"gateway","inferenceGatewayBaseUrl":"$door","defaultModelEffort":"low",""" +
                    """"keepAwakeEnabled":false,"inferenceCustomHeaders":{"X-Team":"a"},""" +
                    """"claudeAiImport":{"enabled":true},""" +
                    """"allowedPluginMarketplaces":[{"source":"github","repo":"their/own"}]}""",
            )
            Files.writeString(
                library.resolve("_meta.json"),
                """{"appliedId":"$theirs","entries":[{"id":"$theirs","name":"Mine"}]}""",
            )
            val desktop = DesktopAppLibrary(DesktopAppConfig(env))
            val models = buildJsonArray { add(buildJsonObject { put("name", "claude-oai--6.2-sol") }) }

            assertTrue(desktop.publish(door, models))
            val config = read(library.resolve("$theirs.json"))
            assertEquals("low", config.getValue("defaultModelEffort").jsonPrimitive.content, "what they set stays")
            assertEquals("false", config.getValue("keepAwakeEnabled").jsonPrimitive.content, "off stays off")
            assertEquals("true", config.getValue("projectsEnabled").jsonPrimitive.content, "projects filled on")
            val imp = config.getValue("claudeAiImport").jsonObject
            assertEquals("true", imp.getValue("automatic3pImport").jsonPrimitive.content)
            assertEquals("show", imp.getValue("bannerBehavior").jsonPrimitive.content)
            assertEquals(
                """{"X-Team":"a","x-splice-desktop":"app"}""",
                config.getValue("inferenceCustomHeaders").toString(),
                "their header, and the one the door reads a Desktop request by",
            )
            assertEquals(
                """[{"source":"github","repo":"their/own"},""" +
                    """{"source":"github","repo":"anthropics/claude-plugins-official"}]""",
                config.getValue("allowedPluginMarketplaces").toString(),
            )
            assertEquals("""["*"]""", config.getValue("coworkEgressAllowedHosts").toString(), "what it left unset")
            assertEquals("3p", config.getValue("deploymentMode").jsonPrimitive.content, "no claude.ai sign-in here")
            assertFalse(desktop.publish(door, models), "nothing is left to give")
        }

    @Test
    fun `the default policy ships no address, credential, model list, managed server or sign-in organization`() {
        val shipped = DesktopPolicyDefaults().read()
        assertTrue(shipped.isNotEmpty(), "the build ships a default policy")
        val never = listOf(
            "inferenceProvider",
            "inferenceGatewayBaseUrl",
            "inferenceGatewayApiKey",
            "inferenceCredentialHelper",
            "inferenceModels",
            "managedMcpServers",
            "deploymentOrganizationUuid",
        )
        assertEquals(emptyList<String>(), never.filter { it in shipped })
        assertFalse("allowedOrganizationUuids" in shipped.getValue("claudeAiImport").jsonObject)
    }

    @Test
    fun `the default policy asks Desktop to offer the Claude Code sessions on this computer`() {
        val defaults = DesktopPolicyDefaults().read()
        assertEquals("true", defaults.getValue("projectsEnabled").jsonPrimitive.content, "projects enabled by default")
        val shipped = defaults.getValue("claudeAiImport").jsonObject
        assertEquals("true", shipped.getValue("enabled").jsonPrimitive.content, "the import is on")
        assertEquals("true", shipped.getValue("automatic3pImport").jsonPrimitive.content, "auto 3p import on")
        assertEquals(
            "show",
            shipped.getValue("bannerBehavior").jsonPrimitive.content,
            "gateway mode has no claude.ai account for detect to resolve, so only show raises Desktop's offer",
        )
        assertEquals(
            "10",
            shipped.getValue("bannerMaxDismissals").jsonPrimitive.content,
            "the offer returns until it is accepted, so sessions started later come in the same way",
        )
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

    private fun read(file: Path) = Json.parseToJsonElement(Files.readString(file)).jsonObject
}
