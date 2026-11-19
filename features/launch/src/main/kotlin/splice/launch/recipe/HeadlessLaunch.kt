// NEW: the Context Getter (docs/specs/harness.md, decision 9). A session splice starts itself on a head: the launch a
// person's session of that head gets in the same folder (the head's login, the MCP hub's tools, the person's settings),
// read the way the /launch route reads it, with no effort of the head's; or, for a session on the door, the launch
// `claude` gets on the door in that folder. The caller adds its own words after the argv.
package splice.launch.recipe

import splice.launch.LaunchHead
import splice.launch.LaunchOutcome
import splice.launch.wrap.WrappedClaudeLaunch
import java.nio.file.Path

public class HeadlessLaunch(private val service: LaunchService) {
    private val claude = WrappedClaudeLaunch(service.wrap)

    /** The launch of [target] in [folder], or the refusal that keeps one from being made: a head with no launch of its
     *  own, or one whose login is being replaced. It thinks at Claude Code's own level for its model, so the head's
     *  effort is not handed on. */
    public fun of(target: LaunchHead, folder: Path): LaunchOutcome {
        val spec = target.spec ?: return LaunchOutcome.Refused("the head has no launch of its own")
        // The Claude head runs over the person's own Claude home under either name, as the /launch route launches it.
        val wrapped = service.wrap.takeIf { claude.ownHome(target.head.key) }?.ownHome()
        service.loginGuard?.refusal(wrapped?.configDir ?: spec.trees.own)?.let { return LaunchOutcome.Refused(it) }
        val launched = (target.catalog?.let(spec::withWindows) ?: spec).copy(effort = null)
        return service.launchOutcome(
            launched,
            extraArgs = emptyList(),
            dangerouslySkipPermissions = false,
            keyPresentNow = target.keyPresence.keyPresentNow(),
            caller = LaunchCaller(cwd = folder.toString(), wrapped = wrapped),
        )
    }

    /** The launch `claude` gets on the door in [folder], as the /launch route gives it, or null while `claude` is not
     *  on the door. It runs over the person's own Claude folder, and the caller names the model it runs. */
    public suspend fun onDoor(folder: Path): LaunchOutcome? {
        val door = service.door?.launch() ?: return null
        val caller = LaunchCaller(cwd = folder.toString())
        return service.onDoor.outcome(door, emptyList(), false, caller, headDirs = emptyList())
    }
}
