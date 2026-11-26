// NEW: process environment owns per-launch foreground identity, separate from model and resume choices.
package splice.launch.recipe

import splice.client.desktop.GATEWAY_HEADERS
import splice.core.client.FOREGROUND_OWNER_ENV
import splice.core.client.LAUNCH_OWNER_HEADER
import splice.core.util.LogSafe
import splice.launch.LaunchNotice
import splice.launch.LaunchNotices
import splice.launch.LaunchRecipe
import java.util.UUID

internal data class LaunchEnvironment(val env: Map<String, String>, val unset: List<String>) {
    fun recipe(argv: List<String>, warning: String?, caller: LaunchCaller, notices: LaunchNotices?): LaunchRecipe {
        val owner = UUID.randomUUID().toString()
        val headers = listOfNotNull(env[GATEWAY_HEADERS]?.takeIf(String::isNotBlank), "$LAUNCH_OWNER_HEADER: $owner")
        val owned = env + mapOf(FOREGROUND_OWNER_ENV to owner, GATEWAY_HEADERS to headers.joinToString("\n"))
        val labels = LaunchLabels.read(caller.ambient)
        val pid = caller.launch?.toLongOrNull()?.takeIf { it > 0 }
        val launch = LaunchNotice(owner, pid, caller.cwd, labels.settings)
        notices?.launched(launch)
        val names = labels.settings.modsOff.names
        val unknown = notices?.unknownMods(names) ?: names
        val offWarnings = unknown.sorted().map {
            val name = LogSafe.str(it)
            "SPLICE_MODS_OFF names a mod that is not installed: $name"
        }
        val warnings = listOfNotNull(warning) + labels.warnings + offWarnings
        val messages = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n")
        return LaunchRecipe(owned, unset, argv, messages)
    }
}
