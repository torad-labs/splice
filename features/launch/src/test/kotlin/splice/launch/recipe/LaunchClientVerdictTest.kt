// NEW: V4-220 item 6b — a launch of a client (Claude) head whose forwarded login upstream rejected warns
// with the fix that works: Claude Code's own /login. The launch warning was written for heads splice
// holds a credential for, so a client head read either as fine (it always described itself present)
// or, once it could read rejected, as "not signed in — run: <head> login", a splice sign-in that
// cannot fix the caller's own login.
package splice.launch.recipe

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudePolicy
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.CredentialVerdict
import splice.launch.HeadTrees
import splice.launch.LaunchHead
import splice.launch.LaunchRecipe
import splice.launch.LaunchSpec
import splice.launch.runningHead
import java.nio.file.Path

private const val HEAD = "claude-splice"
private const val REJECTED_AT = 1_790_000_000_000L

class LaunchClientVerdictTest {

    private fun spec(configDir: Path) = LaunchSpec(
        trees = HeadTrees(configDir),
        pinnedModel = "claude-fable-5",
        availableModelIds = listOf("claude-fable-5"),
        modelLabels = mapOf("claude-fable-5" to "Claude Fable 5"),
        contextWindow = 200_000,
        modelOptionsCache = buildJsonObject { },
        statuslineCommand = "\"/bin/curl\" -s :3096/statusline/$HEAD",
        loginCommand = "$HEAD login",
        signInLabel = "Claude",
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        port = 3104,
        inferenceToken = "test-token",
        apiTimeoutMs = 960_000,
        forwardClientAuth = true,
        headKey = HEAD,
    )

    private fun clientHead(verdict: CredentialVerdict, configDir: Path) = LaunchHead(
        head = runningHead(HEAD),
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() =
                AuthDescription(verdict !is CredentialVerdict.Rejected, "client", mapOf("head" to HEAD), verdict)
        },
        spec = spec(configDir),
    )

    private val raw = LaunchRecipe(env = emptyMap(), unset = emptyList(), argv = listOf("claude"))

    @Test
    fun `a rejected forwarded login warns with Claude Code's own login, dated`(@TempDir tmp: Path) = runTest {
        val target = clientHead(CredentialVerdict.Rejected(REJECTED_AT), tmp)

        val warning = LaunchResponse().withAuthWarning(target, spec(tmp), raw).warning.orEmpty()

        assertTrue(warning.contains("upstream rejected the Claude login"), warning)
        assertTrue(warning.contains("run /login in this session"), warning)
        assertTrue(warning.contains("2026-09-21T"), warning)
        assertTrue(!warning.contains("$HEAD login"), "a splice sign-in cannot fix the caller's login: $warning")
    }

    @Test
    fun `an unverified or accepted forwarded login launches without a warning`(@TempDir tmp: Path) = runTest {
        listOf(CredentialVerdict.Unverified, CredentialVerdict.Accepted(REJECTED_AT)).forEach { verdict ->
            val recipe = LaunchResponse().withAuthWarning(clientHead(verdict, tmp), spec(tmp), raw)
            assertEquals(null, recipe.warning, "$verdict")
        }
    }
}
