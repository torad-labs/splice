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
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class NoBrowserOnTurnFailureTest {

    @Test
    fun `the browser primitive is called from exactly the three operator-initiated surfaces`() {
        // Sorted, module-relative. OAuthLoginFlow and DeviceLoginFlow are the two login verbs'
        // flows; AdminSupport.openUrl exists for `splice dashboard`. Nothing else may reach it.
        assertEquals(
            listOf(
                "app/src/main/kotlin/splice/app/auth/OAuthLoginFlow.kt",
                "app/src/main/kotlin/splice/app/cli/AdminSupport.kt",
                "app/src/main/kotlin/splice/app/auth/DeviceLoginFlow.kt",
            ).sorted(),
            referencing("openBrowser(").filterNot { it.endsWith("splice/app/auth/LoginIo.kt") }.sorted(),
            "a new browser call site is a new way to open the operator's browser — sanction it here " +
                "deliberately, or do not ship it",
        )
    }

    @Test
    fun `the dashboard wrapper is the only other route, and it is a verb`() {
        assertEquals(
            listOf("app/src/main/kotlin/splice/app/cli/status/DashboardCommand.kt"),
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

    /** A NESTED CHECKOUT IS NOT THIS TREE — the exclusion [referencing] rests on, proved rather
     *  than asserted: the same token in a second copy of app/src/main/kotlin under a directory that
     *  is itself a checkout must not enter the set. RED without the `onEnter` guard below. */
    @Test
    fun `a nested checkout's sources are not this tree's sources`(@TempDir root: File) {
        val here = File(root, "app/src/main/kotlin/splice/app").apply { mkdirs() }
        File(here, "Here.kt").writeText("fun a() = openBrowser(url)\n")
        val nested = File(root, ".claude/worktrees/x/app/src/main/kotlin/splice/app").apply { mkdirs() }
        File(nested, "There.kt").writeText("fun b() = openBrowser(url)\n")
        // A worktree's .git is a FILE pointing at the parent's gitdir; a clone's is a directory.
        File(root, ".claude/worktrees/x/.git").writeText("gitdir: /elsewhere\n")
        assertEquals(listOf("app/src/main/kotlin/splice/app/Here.kt"), referencing(root, "openBrowser("))
    }

    /** Module-relative paths of every production Kotlin source under the gateway tree that contains
     *  [token]. Module-relative so the expected sets above read the same from any working directory. */
    private fun referencing(token: String): List<String> = referencing(gatewayRoot().toFile(), token)

    /** [referencing] against an explicit [root], which is what makes the exclusion testable.
     *
     *  A NESTED CHECKOUT IS NOT THIS TREE. `.claude/worktrees/<name>/` holds a COMPLETE second copy
     *  of app/src/main/kotlin, and a walk that descends into one grades another branch's sources as
     *  if they shipped here — measured 2026-09-21, when two live worktrees put seven foreign paths
     *  into this set and reddened a law nobody had broken. So the walk stops at any directory that
     *  is itself a checkout, which is the same refusal ProjectMap makes for the architecture laws. */
    private fun referencing(root: File, token: String): List<String> =
        root.walkTopDown()
            .onEnter { dir -> dir == root || !File(dir, ".git").exists() }
            .filter { it.isFile && it.extension == "kt" && it.path.contains("/src/main/kotlin/") }
            .filter { it.readText().contains(token) }
            .map { it.relativeTo(root).path }
            .toList()

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
