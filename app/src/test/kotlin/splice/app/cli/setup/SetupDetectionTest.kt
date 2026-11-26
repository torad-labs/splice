// SetupDetection: what the wizard finds on the machine, given the credentials and daemon present.
package splice.app.cli.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.capability.Capabilities
import splice.app.capability.DesktopFacts
import splice.app.capability.HarnessFacts
import splice.app.capability.HubFacts
import splice.app.capability.WrapperSettings
import splice.app.desktop.setup.DesktopMode
import splice.client.ManagedFile
import splice.client.ManagedReading
import splice.configuration.add.AddProfiles
import splice.configuration.add.DaemonUpProbe
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.util.EnvReader
import splice.terminal.WizardFrame
import java.nio.file.Path

class SetupDetectionTest {

    @Test
    fun `a fresh machine has nothing to find`() {
        val facts = detect(env = emptyEnv(), files = emptySet(), daemonUp = false)
        assertEquals(emptySet<String>(), facts.spliceOwned)
        assertEquals(emptySet<String>(), facts.vendorCli)
        assertFalse(facts.openRouterKey)
        assertFalse(facts.daemonUp)
    }

    @Test
    fun `an OPENROUTER_API_KEY alone is found as the key`() {
        val facts = detect(env = openRouterEnv(), files = emptySet(), daemonUp = false)
        assertTrue(facts.openRouterKey)
        assertEquals(emptySet<String>(), facts.spliceOwned)
    }

    @Test
    fun `one sign-in splice holds is found by its kind`() {
        val chatgpt = ownedPath("chatgpt-oauth")
        val facts = detect(env = emptyEnv(), files = setOf(chatgpt), daemonUp = false)
        assertEquals(setOf("chatgpt-oauth"), facts.spliceOwned)
    }

    @Test
    fun `every credential and a running daemon are all found`() {
        val files = allOwnedPaths() + allVendorPaths()
        val facts = detect(env = openRouterEnv(), files = files, daemonUp = true)
        assertTrue(facts.openRouterKey)
        assertTrue(facts.daemonUp)
        assertEquals(allOwnedWires(), facts.spliceOwned)
    }

    @Test
    fun `the optional programs found are part of what setup reports`() {
        val tools = Capabilities(
            DesktopFacts(Path.of("/usr/bin/claude-desktop"), DesktopMode.STANDARD, false, false, otherWrapper()),
            HarnessFacts(Path.of("/usr/bin/agy"), null, geminiShim = true, synced = emptySet()),
            HubFacts(linux = true, on = false, systemd = true),
            autoUpdate = null,
        )
        val facts = SetupDetection(
            env = emptyEnv(),
            credentials = CredentialPresenceProbe { false },
            daemon = DaemonUpProbe { false },
            controlPort = ControlPortResolver { 0 },
            look = CapabilityProbe { tools },
        ).detect()
        assertEquals(tools, facts.capabilities)
        val said = StringBuilder()
        SetupMenu(WizardFrame(out = said), AddProfiles()).printDetected(facts)
        assertTrue("Claude Desktop (on splice's older Code-tab door)" in said, said.toString())
        assertTrue("agy" in said, said.toString())
        assertTrue("a managed settings file naming another program as processWrapper" in said, said.toString())
        assertFalse("codex" in said, said.toString())
    }

    private fun otherWrapper(): WrapperSettings {
        val file = Path.of("/etc/claude-code/managed-settings.json")
        val settings = Json.parseToJsonElement("""{"processWrapper": "/usr/bin/other"}""").jsonObject
        val reading = ManagedReading(file, ManagedFile.Read(settings), emptyList())
        return WrapperSettings(reading, Path.of("/s/desktop-launch"))
    }

    private fun detect(env: EnvReader, files: Set<String>, daemonUp: Boolean) =
        SetupDetection(
            env = env,
            credentials = CredentialPresenceProbe { it in files },
            daemon = DaemonUpProbe { daemonUp },
            controlPort = ControlPortResolver { 0 },
            look = CapabilityProbe { null },
        ).detect()

    private fun emptyEnv(): EnvReader = EnvReader { null }

    private fun openRouterEnv(): EnvReader = EnvReader { name ->
        "sk-or-test-not-a-key".takeIf { name == "OPENROUTER_API_KEY" }
    }

    private fun ownedPath(wire: String): String =
        checkNotNull(AuthKindRegistry.defaultAuthFileFor(wire))

    private fun allOwnedPaths(): Set<String> =
        AuthKindRegistry.knownKinds().mapNotNull { it.defaultAuthFile }.toSet()

    private fun allVendorPaths(): Set<String> =
        AuthKindRegistry.knownKinds().mapNotNull { (it as? AuthKind.OAuth)?.nativeAppFile }.toSet()

    private fun allOwnedWires(): Set<String> =
        AuthKindRegistry.knownKinds().mapNotNull { kind ->
            kind.wire.takeIf { kind.defaultAuthFile != null }
        }.toSet()
}
