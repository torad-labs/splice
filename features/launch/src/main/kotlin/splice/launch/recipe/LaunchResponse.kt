// PORT-OF: ControlServer.kt (LaunchResponse) @ a77531a — invariants unchanged: the /launch response
// body and the auth warning it carries, next to LaunchService.kt (LaunchSpec, LaunchRecipe,
// LaunchService already live there). Widened private -> internal: its sole call site is now
// LaunchRoutes. LAYOUT-01: the auth warning reads the LaunchHead projection, not ManagedHead.
package splice.launch.recipe

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.auth.AuthDescription
import splice.launch.LaunchHead
import splice.launch.LaunchRecipe
import splice.launch.LaunchSpec

internal class LaunchResponse {
    // The exec-recipe response body: {env, unset, argv, warning?} — the shim reads it to run the head.
    fun launchRecipeJson(recipe: LaunchRecipe): String = buildJsonObject {
        putJsonObject("env") { recipe.env.forEach { (k, v) -> put(k, v) } }
        putJsonArray("unset") { recipe.unset.forEach { add(it) } }
        putJsonArray("argv") { recipe.argv.forEach { add(it) } }
        if (recipe.warning != null) put("warning", recipe.warning)
    }.toString()

    // A head with no upstream credentials still launches (Claude Code opens fine) but every
    // request 401s upstream — warn NOW, at the moment the user can still fix it.
    suspend fun withAuthWarning(target: LaunchHead, spec: LaunchSpec, raw: LaunchRecipe): LaunchRecipe {
        val auth = target.auth.describe()
        return if (auth.present) {
            raw
        } else {
            raw.copy(warning = listOfNotNull(raw.warning, missingAuthWarning(target, auth, spec)).joinToString("; "))
        }
    }

    // Names the exact fix: the env var for an api-key head, the login command for an OAuth head.
    // The key is read from the DAEMON's environment, so "export then retry" silently fails until
    // the daemon restarts — the message says so.
    private fun missingAuthWarning(target: LaunchHead, auth: AuthDescription, spec: LaunchSpec): String {
        val label = target.head.label
        val envVar = auth.fields["env_var"]
        val keyFile = auth.fields["key_file"]
        return when {
            // A file-configured head's primary fix is the file it reads, not an env var it never used.
            keyFile != null ->
                "'$label' has no upstream API key: add it to $keyFile " +
                    "(or export $envVar) — then run: splice restart"
            envVar != null ->
                "'$label' has no upstream API key: $envVar is not set in the daemon's environment. " +
                    "Requests will fail until you export $envVar and run: splice restart"
            else -> "'$label' is not signed in — requests will fail until you run: ${spec.loginCommand}"
        }
    }
}
