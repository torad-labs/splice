// NEW: each real synthetic launch gets a distinct opaque owner without changing model environment or arguments.
package splice.launch

import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.core.client.FOREGROUND_OWNER_ENV
import splice.launch.recipe.LaunchService
import java.nio.file.Path

class ForegroundOwnerLaunchTest {
    @Test
    fun `the process owner is minted per launch independently of model selectors`(@TempDir home: Path) {
        val service = LaunchService(ClaudeConfigMaterializer(home))
        val spec = LaunchSpec(
            trees = HeadTrees(home.resolve(".claude-synthetic")),
            models = LaunchModels(
                pinnedModel = "synthetic-model",
                availableModelIds = listOf("synthetic-model"),
                modelLabels = mapOf("synthetic-model" to "Synthetic"),
                contextWindow = 272_000,
                modelOptionsCache = buildJsonObject { },
            ),
            signIn = LaunchSignIn(
                loginCommand = "",
                signInLabel = "",
            ),
            gateway = LaunchGateway(
                statuslineCommand = "",
                port = 0,
                inferenceToken = "synthetic",
                apiTimeoutMs = 1_000,
            ),
            policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        )
        val one = service.launch(spec, emptyList(), dangerouslySkipPermissions = false)
        val two = service.launch(spec, emptyList(), dangerouslySkipPermissions = false)
        val firstOwner = requireNotNull(one.env[FOREGROUND_OWNER_ENV])
        val nextOwner = requireNotNull(two.env[FOREGROUND_OWNER_ENV])
        assertTrue(firstOwner != nextOwner, "launch owners are distinct")
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        assertTrue(uuid.matches(firstOwner), "the first owner is a random UUID")
        assertTrue(uuid.matches(nextOwner), "the next owner is a random UUID")
        assertEquals(one.env - FOREGROUND_OWNER_ENV, two.env - FOREGROUND_OWNER_ENV)
        assertEquals(one.argv, two.argv)
        assertEquals(one.unset, two.unset)
        assertTrue(firstOwner != "synthetic-model", "the owner is not a model selector")
    }
}
