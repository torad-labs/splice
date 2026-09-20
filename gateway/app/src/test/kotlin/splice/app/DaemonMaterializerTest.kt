// NEW: law 19 pin — proves the daemon materializer wires the hook exec, so the noexec capture-hook
// guard cannot be silently unwired (a materializer built without it skips the exec-probe and a
// pasted credential would reach the model on a noexec mount).
package splice.app

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.launch.ClaudePolicy
import splice.core.launch.HookExec
import splice.core.launch.MaterializeSpec
import splice.core.launch.TokenCaptureSpec
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class DaemonMaterializerTest {

    private val capture = TokenCaptureSpec(
        envVar = "OPENROUTER_API_KEY",
        tokenPattern = "sk-or-[A-Za-z0-9_-]{20,}",
        providerLabel = "OpenRouter",
    )

    private fun captureSpec(configDir: Path) = MaterializeSpec(
        configDir = configDir,
        policy = ClaudePolicy(emptySet(), emptySet()),
        availableModelIds = listOf("m"),
        defaultModel = "m",
        modelOptionsCache = JsonObject(emptyMap()),
        statuslineCommand = "",
        loginCommand = "openrouter login",
        signInLabel = "OpenRouter",
        tokenCapture = capture,
    )

    @Test
    fun `a capture head fails closed when the wired exec reports noexec`(@TempDir tmp: Path) {
        val failing = HookExec { _, _ -> IOException("Cannot run program: error=13, Permission denied") }
        val materializer = DaemonMaterializer.build(tmp, rewrite = null, hookExec = failing, controlPort = 3096)

        assertThrows<IOException> { materializer.materialize(captureSpec(tmp.resolve(".claude-head"))) }
    }

    // LAW 19 WIRING PIN (V4-103). The test above proves the guard WORKS once an exec is wired; it
    // cannot fail when the daemon stops wiring one, and that is the failure that actually happened —
    // ControlPlane called DaemonMaterializer.build(...) and let a DEFAULT supply the exec, so the
    // call site that disables the noexec guard looked exactly like the one that enables it. Two
    // things make this pin falsifiable rather than decorative: `build` now has no default for
    // hookExec, so omitting it is a compile error, and this assertion catches the half the compiler
    // cannot state — a caller passing SOME exec that is not the real one.
    @Test
    fun `the daemon wires the real hook exec`() {
        val call = buildCallArguments(controlPlaneSource())
        assertNotNull(call, "DaemonMaterializer.build(...) not found in ControlPlane.kt")
        assertTrue(
            call!!.contains("hookExec = HookProcessExec.exec"),
            "ControlPlane must pass the real exec, got: $call",
        )
    }

    /** ControlPlane.kt, found by walking up from the working directory: under Gradle the cwd is the
     *  module dir and from an IDE it is the repo root, so neither is assumed. */
    private fun controlPlaneSource(): String {
        val relative = "gateway/app/src/main/kotlin/splice/app/ControlPlane.kt"
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(relative)
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent
        }
        error("$relative not found above ${Paths.get("").toAbsolutePath()}")
    }

    /** The balanced argument text of the build(...) call. A regex cannot do this: the first argument
     *  is sharing.rewrite(), whose ')' would close the match one paren early. */
    private fun buildCallArguments(source: String): String? {
        val marker = "DaemonMaterializer.build("
        val open = source.indexOf(marker)
        if (open < 0) return null
        val start = open + marker.length
        var depth = 1
        var i = start
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return source.substring(start, i - 1)
    }
}
