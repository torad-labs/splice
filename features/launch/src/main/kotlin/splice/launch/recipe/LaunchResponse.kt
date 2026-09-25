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
import splice.core.auth.CredentialVerdict
import splice.launch.LaunchHead
import splice.launch.LaunchRecipe
import splice.launch.LaunchSpec
import java.time.Instant

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

    // Names the exact fix: `splice key set` for an api-key head, the login command for an OAuth head, and
    // Claude Code's own /login for a client head upstream rejected (V4-220 item 6b: splice holds nothing
    // there, so the only fix is the caller's login).
    // V4-227: the head reads a stored key and its key file on every request (ApiKeyAuthProvider), so
    // neither needs a restart. Only a key kept in the DAEMON's environment does, since the daemon reads
    // its environment once at start, so that path is named second, with its restart.
    private fun missingAuthWarning(target: LaunchHead, auth: AuthDescription, spec: LaunchSpec): String {
        val label = target.head.label
        val envVar = auth.fields["env_var"]
        val keyFile = auth.fields["key_file"]
        val verdict = auth.verdict
        return when {
            verdict is CredentialVerdict.Rejected ->
                "'$label': upstream rejected the Claude login on the last forwarded turn " +
                    "(${Instant.ofEpochMilli(verdict.atEpochMs)}); run /login in this session"
            // A file-configured head's primary fix is the file it reads, not an env var it never used.
            keyFile != null ->
                "'$label' has no upstream API key: add it to $keyFile, or run: splice key set $envVar. " +
                    "The next request reads either, with no restart"
            envVar != null ->
                "'$label' has no upstream API key: requests will fail until you run: splice key set $envVar. " +
                    "The next request reads it, with no restart (a key kept in the daemon's environment " +
                    "instead needs export $envVar there, then: splice restart)"
            else -> "'$label' is not signed in. Requests will fail until you run: ${spec.loginCommand}"
        }
    }
}
