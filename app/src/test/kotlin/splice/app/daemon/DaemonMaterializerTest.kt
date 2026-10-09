// A capture head fails closed when the daemon's hook exec reports a noexec mount, so a pasted credential
// can never reach the model through a capture hook that cannot run.
package splice.app.daemon

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudePolicy
import splice.client.MaterializeSpec
import splice.client.login.HookExec
import splice.client.login.TokenCaptureSpec
import splice.client.resume.ResumeHookTarget
import java.io.IOException
import java.nio.file.Path

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
        val materializer = DaemonMaterializer.build(
            tmp,
            rewrite = null,
            hookExec = failing,
            resumeHook = ResumeHookTarget(3096) { tmp.resolve("turn-auth-header") },
        )

        assertThrows<IOException> { materializer.materialize(captureSpec(tmp.resolve(".claude-head"))) }
    }
}
