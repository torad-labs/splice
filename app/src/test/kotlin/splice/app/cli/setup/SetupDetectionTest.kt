// SetupDetection: what the wizard suggests starting from, given the credentials and daemon present.
package splice.app.cli.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.configuration.add.DaemonUpProbe
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.util.EnvReader

class SetupDetectionTest {

    @Test
    fun `fresh machine with nothing present suggests OpenRouter`() {
        val facts = detect(env = emptyEnv(), files = emptySet(), daemonUp = false)
        assertEquals(emptySet<String>(), facts.spliceOwned)
        assertEquals(emptySet<String>(), facts.vendorCli)
        assertFalse(facts.openRouterKey)
        assertFalse(facts.daemonUp)
        assertEquals(SetupStart.OpenRouter, facts.suggested)
    }

    @Test
    fun `OPENROUTER_API_KEY only suggests OpenRouter`() {
        val facts = detect(env = openRouterEnv(), files = emptySet(), daemonUp = false)
        assertTrue(facts.openRouterKey)
        assertEquals(emptySet<String>(), facts.spliceOwned)
        assertEquals(SetupStart.OpenRouter, facts.suggested)
    }

    @Test
    fun `one OAuth credential present suggests that kind`() {
        val chatgpt = ownedPath("chatgpt-oauth")
        val facts = detect(env = emptyEnv(), files = setOf(chatgpt), daemonUp = false)
        assertEquals(setOf("chatgpt-oauth"), facts.spliceOwned)
        assertEquals(SetupStart.OAuth("chatgpt-oauth"), facts.suggested)
    }

    @Test
    fun `all credentials present with a daemon running suggests Existing`() {
        val files = allOwnedPaths() + allVendorPaths()
        val facts = detect(env = openRouterEnv(), files = files, daemonUp = true)
        assertTrue(facts.openRouterKey)
        assertTrue(facts.daemonUp)
        assertEquals(allOwnedWires(), facts.spliceOwned)
        assertEquals(SetupStart.Existing, facts.suggested)
    }

    @Test
    fun `a daemon that does not answer is never Existing`() {
        val facts = detect(env = openRouterEnv(), files = allOwnedPaths(), daemonUp = false)
        assertFalse(facts.daemonUp)
        assertTrue(facts.suggested !is SetupStart.Existing)
        assertEquals(SetupStart.OAuth("chatgpt-oauth"), facts.suggested)
    }

    private fun detect(env: EnvReader, files: Set<String>, daemonUp: Boolean) =
        SetupDetection(
            env = env,
            credentials = CredentialPresenceProbe { it in files },
            daemon = DaemonUpProbe { daemonUp },
            controlPort = ControlPortResolver { 0 },
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
