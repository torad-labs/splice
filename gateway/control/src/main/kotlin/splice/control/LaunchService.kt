// PORT-OF: server/launcher/{ensure-proxy,assemble-env}.mjs exec-recipe intent @ 4ca99f7, as a
// daemon endpoint (P4-LAUNCH). The bin shim POSTs /launch{head}; the daemon materializes the
// head's Claude config (P5-PREP) and returns the exec recipe: env sets (ANTHROPIC_BASE_URL at the
// head port, a dummy key, CLAUDE_CONFIG_DIR), env UNSETS (strip inherited Anthropic vars so the
// gateway wins), and the claude argv (--dangerously-skip-permissions unless SAFE, default
// --model). The shim `exec env ... claude ...`. The launcher wantMode/patchMode dead branch is
// deliberately NOT ported.
package splice.control

import kotlinx.serialization.json.JsonObject
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.ClaudePolicy

/** What a head needs to produce a launch recipe (supplied by :app at wiring time). */
public data class LaunchSpec(
    val configDir: java.nio.file.Path,
    val pinnedModel: String,
    val availableModelIds: List<String>,
    val modelOptionsCache: JsonObject,
    val policy: ClaudePolicy,
    val port: Int,
)

public data class LaunchRecipe(
    val env: Map<String, String>,
    val unset: List<String>,
    val argv: List<String>,
)

public class LaunchService(
    private val materializer: ClaudeConfigMaterializer,
    private val claudeBinary: String = "claude",
) {
    /** Materialize the head's config + build the exec recipe. safe=true drops the skip-perms flag. */
    public fun launch(spec: LaunchSpec, extraArgs: List<String>, safe: Boolean): LaunchRecipe {
        materializer.materialize(
            spec.configDir,
            spec.policy,
            spec.availableModelIds,
            spec.pinnedModel,
            spec.modelOptionsCache,
        )
        val env = mapOf(
            "ANTHROPIC_BASE_URL" to "http://127.0.0.1:${spec.port}",
            "ANTHROPIC_API_KEY" to "splice-local",
            "CLAUDE_CONFIG_DIR" to spec.configDir.toString(),
        )
        val unset = listOf("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_MODEL")
        val argv = buildList {
            add(claudeBinary)
            if (!safe) add("--dangerously-skip-permissions")
            add("--model")
            add(spec.pinnedModel)
            addAll(extraArgs)
        }
        return LaunchRecipe(env, unset, argv)
    }
}
