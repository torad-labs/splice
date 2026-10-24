// NEW: process environment owns per-launch foreground identity, separate from model and resume choices.
package splice.launch.recipe

import splice.core.client.FOREGROUND_OWNER_ENV
import splice.launch.LaunchRecipe
import java.util.UUID

internal data class LaunchEnvironment(val env: Map<String, String>, val unset: List<String>) {
    fun recipe(argv: List<String>, warning: String?): LaunchRecipe {
        val owned = env + (FOREGROUND_OWNER_ENV to UUID.randomUUID().toString())
        return LaunchRecipe(owned, unset, argv, warning)
    }
}
