// NEW: gpt-speed (docs/specs/gpt-speed.md, decisions 1 and 2) — what a head's materialization lays down for /fast and
// /ultrafast: the skills (SpeedSkills) and the UserPromptSubmit hook that catches them (SpeedHook). Held by
// ClaudeConfigMaterializer, so its own budget stays for the config it has always written.
package splice.client.speed

import kotlinx.serialization.json.JsonObject
import splice.client.login.HookInstaller
import splice.client.resume.ResumeHookTarget
import splice.core.model.ServiceTier
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.nio.file.Path

/** [personSkills] is the person's ~/.claude/skills; [target] is the daemon the hook calls, null for a materializer with
 *  no daemon behind it, which lays down no hook. */
internal class SpeedMaterial(
    private val log: LogSink,
    private val hooks: HookInstaller,
    private val target: ResumeHookTarget?,
    private val personSkills: Path,
) {
    /** /fast and /ultrafast in the head's own skills for a head signed in with ChatGPT ([tiers] the tiers its pinned
     *  model offers), and splice's earlier ones removed from any other. [sharesSkills] says whether the head offers the
     *  person's own skills, which then stay offered beside splice's. Best-effort: a failure is said and the head launches
     *  with Claude Code's own /fast turned off and the hook still catching the typed command. */
    fun skills(configDir: Path, tiers: List<ServiceTier>?, sharesSkills: Boolean) {
        val global = personSkills.takeIf { sharesSkills }
        Cancellables.runCatchingCancellable { SpeedSkills(log).write(configDir, tiers.orEmpty(), global) }
            .exceptionOrNull()
            ?.let { cause ->
                log(
                    "[speed] /fast and /ultrafast NOT laid down in $configDir " +
                        "(${SafeFailureText.render(cause)}); the / menu does not offer them on this launch\n",
                )
            }
    }

    /** The hook that catches /fast and /ultrafast, on a head signed in with ChatGPT ([tiers] non-null) with a daemon
     *  behind it; none otherwise. */
    fun hook(configDir: Path, headKey: String, tiers: List<ServiceTier>?): Map<String, List<JsonObject>> {
        val daemon = target?.takeIf { tiers != null } ?: return emptyMap()
        return SpeedHook.install(configDir, daemon, headKey, hooks)
    }
}
