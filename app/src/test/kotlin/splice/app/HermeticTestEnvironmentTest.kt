// The rig's own tripwire for HERMETIC ENVIRONMENT (build-logic/src/main/kotlin/
// splice.kotlin-common.gradle.kts): no test JVM may inherit a credential-shaped env var from the
// shell that started gradle. ApiKeyAuthProvider.readKey reads the env var BEFORE the key file and
// before the store, so an inherited OPENROUTER_API_KEY silently outranks a fixture's own
// credential — measured 2026-09-21, when it reddened MultiProviderDaemonTest's two openrouter arms
// on this machine and on no CI runner, and carried the operator's real key into a mock upstream's
// recording inside the test JVM.
//
// RED before the scrub on any machine that exports one; vacuously green on a machine that does
// not, which is the point — the machines that CAN be wrong are exactly the ones this catches, and
// the suite must not be quietly measuring whoever ran it.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HermeticTestEnvironmentTest {

    @Test
    fun `no credential env var reaches the test JVM`() {
        val leaked = System.getenv().keys.filter { CREDENTIAL_SUFFIXES.any(it::endsWith) }.sorted()
        assertEquals(
            emptyList<String>(),
            leaked,
            "the test JVM inherited credential env var(s) from the shell that ran gradle (names " +
                "only above, never values). ApiKeyAuthProvider reads the env FIRST, so each one " +
                "outranks every fixture's key file and every hermetic key store: restore the " +
                "setEnvironment scrub in splice.kotlin-common.gradle.kts",
        )
    }

    private companion object {
        // The shape splice derives for a provider head: TopologySchema.effectiveApiKeyEnv is
        // "${provider.uppercase()}_API_KEY", and the token spelling is the same hazard.
        val CREDENTIAL_SUFFIXES = listOf("_API_KEY", "_API_TOKEN")
    }
}
