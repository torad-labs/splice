// NEW: V4-129 — the DR-102 guard: the materializer refuses the operator's own ~/.claude. V4-445 removed the
// narrow door wrap used to write there (materializeWrap): a wrapped `claude` now writes nothing into the
// vanilla dir, so this pins that no entry point is left that does.
package splice.client.wrap

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.MaterializeSpec
import java.nio.file.Path

class ClaudeConfigMaterializerWrapTest {

    private val optionsCache: JsonElement = buildJsonObject { put("cache", "claude-splice-models") }

    @Test
    fun `the materializer still refuses the vanilla dir and has no second door into it`(@TempDir home: Path) {
        val vanilla = home.resolve(".claude")
        val spec = MaterializeSpec(
            configDir = vanilla,
            policy = ClaudePolicy(share = setOf("settings"), isolate = emptySet()),
            availableModelIds = listOf("claude-fable-5"),
            defaultModel = "claude-fable-5",
            modelOptionsCache = optionsCache,
            statuslineCommand = "curl -sS :3096/statusline/claude-splice",
        )
        assertThrows(IllegalArgumentException::class.java) { ClaudeConfigMaterializer(home).materialize(spec) }
    }
}
