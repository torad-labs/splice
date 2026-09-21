// V4-38 RULE 2, the product rule, and the wall that stops this class returning on a head nobody has
// written yet: a browser must NEVER open as a SIDE EFFECT of a failed turn. Opening a login page is
// a response to the operator asking to sign in — the login verb, or /login — and to nothing else.
//
// This is a REACHABILITY AUDIT, not a behavioural test, and it is honest about that: no turn-failure
// path can be driven into a browser inside a unit test because the browser primitive is not on the
// head's object graph at all. What CAN be pinned is the call-site set — enumerate every production
// reference to the browser primitive and assert it is exactly the sanctioned one — so a future
// change that wires a failure path to it fails the build by name instead of shipping a browser loop
// that only the operator notices.
package splice.app.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

class NoBrowserOnTurnFailureTest {

    @Test
    fun `the browser primitive is called from exactly the three operator-initiated surfaces`() {
        // Sorted, module-relative. OAuthLoginFlow and DeviceLoginFlow are the two login verbs'
        // flows; AdminSupport.openUrl exists for `splice dashboard`. Nothing else may reach it.
        assertEquals(
            listOf(
                "app/src/main/kotlin/splice/app/OAuthLoginFlow.kt",
                "app/src/main/kotlin/splice/app/cli/AdminSupport.kt",
                "app/src/main/kotlin/splice/app/DeviceLoginFlow.kt",
            ).sorted(),
            referencing("openBrowser(").filterNot { it.endsWith("splice/app/LoginIo.kt") }.sorted(),
            "a new browser call site is a new way to open the operator's browser — sanction it here " +
                "deliberately, or do not ship it",
        )
    }

    @Test
    fun `the dashboard wrapper is the only other route, and it is a verb`() {
        assertEquals(
            listOf("app/src/main/kotlin/splice/app/cli/DashboardCommand.kt"),
            referencing("openUrl(").filterNot { it.endsWith("splice/app/cli/AdminSupport.kt") },
        )
    }

    @Test
    fun `no turn or head path references a browser primitive at all`() {
        // The "on ANY head" half: the head machinery (TurnDriver, TurnPreparation, HeadServer, the
        // adapters) and the provider arms must have no path to the primitive, whatever status or
        // failure class reaches them. This is the assertion that keeps the class dead for kimi, for
        // muse, and for the next head — not just for grok.
        val offenders = (referencing("openBrowser(") + referencing("openUrl("))
            .filter { path -> path.contains("/head/") || path.substringAfterLast('/').startsWith("Turn") }
        assertTrue(offenders.isEmpty(), "a turn-failure path can reach the browser: $offenders")
    }

    /** Module-relative paths of every production Kotlin source under the gateway tree that contains
     *  [token]. Module-relative so the expected sets above read the same from any working directory. */
    private fun referencing(token: String): List<String> {
        val root = gatewayRoot()
        Files.walk(root).use { stream ->
            return stream
                .filter { it.toString().contains("/src/main/kotlin/") && it.toString().endsWith(".kt") }
                .filter { Files.readString(it).contains(token) }
                .map { root.relativize(it).toString() }
                .collect(Collectors.toList())
        }
    }

    /** The `gateway/` directory, found by walking up rather than assuming a working directory. */
    private fun gatewayRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("app/src/main/kotlin")) &&
                Files.isDirectory(dir.resolve("core/src/main/kotlin"))
            ) {
                return dir
            }
            dir = dir.parent
        }
        error("gateway root not found walking up from ${System.getProperty("user.dir")}")
    }
}
