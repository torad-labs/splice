// NEW: door-default (docs/specs/door-default.md, decisions 2 and 5). What a launch of `claude` carries while it is on
// the Splice gateway: Claude Code's gateway mode at the gateway, the terminal's key, the window, and the model a
// session starts on, read from the lane and the gateway's lineup at each launch, so a sign-in reaches the next session
// with no restart; and splice's /login (DoorLogin), its file laid in the person's Claude folder where it is missing and
// its hook in the overlay. head-catalog (Nov 19, 2026): no model list rides it; `/model` is the gateway's own list,
// which Claude Code discovers (GatewayModels). Null on the head lane, which launches `claude` on its head with Claude
// Code's own `/model`: the rows that launch added after Claude Code's models (decision 9) are retired. A head's own
// command starts on the same gateway, on its own model (gateway).
package splice.app.desktop.lane

import kotlinx.serialization.json.JsonObject
import splice.app.desktop.GatewayModels
import splice.app.desktop.catalog.DesktopLineup
import splice.app.desktop.catalog.DoorCatalog
import splice.client.desktop.DoorHooks
import splice.client.desktop.DoorPickerRow
import splice.client.desktop.DoorSession
import splice.client.login.DoorLogin
import splice.client.resume.ResumeHookTarget
import splice.core.topology.AuthKind
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.launch.recipe.DoorLaunchSpec
import splice.launch.recipe.GatewayAt
import splice.sessions.registry.LaunchMarker

internal class ClaudeDoorLaunch(
    private val lane: ClaudeLane,
    private val login: DoorLogin,
    private val log: LogSink,
    private val doorHooks: DoorHooks? = null,
    private val hookTarget: ResumeHookTarget? = null,
) {
    private val ids = GatewayModels()

    suspend fun launch(): DoorLaunchSpec? {
        if (!lane.onDoor()) return null
        val lineup = lane.lineup()
        return spec(lineup, lane.starting(lineup).first, layLogin = true)
    }

    /** Side-by-side launch (`splice launch claude` / `claude-splice`): always runs on the door and never writes
     *  files into `~/.claude`, keeping default `claude` and its personal login untouched. */
    suspend fun launchSideBySide(): DoorLaunchSpec {
        val lineup = lane.lineup()
        return spec(lineup, lane.starting(lineup).first, layLogin = false)
    }

    /** custom-provider (cp-command): a launch of a door command on the door whatever lane `claude` is on, running on
     *  the provider [target] (its key). A session starts on that provider's pinned model. It declares the terminal's
     *  window, which every turn the terminal's key sends is scaled to. With its provider out of the door it starts on
     *  no model, as `claude` does with no provider in the door. */
    suspend fun launchCommand(target: String?): DoorLaunchSpec {
        val lineup = lane.lineup()
        val provider = lineup.providers.firstOrNull { it.key == target }
        return spec(lineup, provider?.let(lineup::pinnedOf)?.ifEmpty { null }, layLogin = true)
    }

    /** The Splice gateway a session of [provider]'s head is pointed at, as `claude`'s is: its address, the terminal's
     *  key and the window the gateway reports every count as a share of, and the provider's pinned selector
     *  (DesktopLineup.pinnedOf: its pin, or its first model when its pin is not in the gateway) as the gateway lists
     *  it; null for the model while the provider is not in the gateway. */
    suspend fun gateway(provider: String): GatewayAt {
        val lineup = lane.lineup()
        val served = lineup.providers.firstOrNull { it.key == provider }
        val model = served?.let(lineup::pinnedOf)?.takeIf { it.isNotEmpty() }?.let { ids.idFor(lineup, it) }
        return GatewayAt(lane.door, lane.key.get(), lane.window(lineup), model)
    }

    private fun spec(lineup: DesktopLineup, selector: String?, layLogin: Boolean): DoorLaunchSpec {
        val model = selector?.let { ids.idFor(lineup, it) }
        val session = DoorSession(lane.door, lane.key.get(), lane.window(lineup), model)
        val loginHooks = if (layLogin) hooks() else overlayLoginHooks()
        val target = hookTarget
        val hooksHelper = doorHooks
        val allHooks = if (target != null && hooksHelper != null) {
            val speedHooks = if (hasChatGpt(lineup)) hooksHelper.speedHooks(target) else emptyMap()
            val startHooks = hooksHelper.sessionStartHooks(target)
            hooksHelper.merge(loginHooks, speedHooks, startHooks)
        } else {
            loginHooks
        }
        return DoorLaunchSpec(
            session.env(),
            session.unset(),
            session.overlay(LaunchMarker.here, LaunchMarker.SPLICE_LAUNCH_KEY, allHooks),
            session.window,
            wallMs = lineup.clientWallMs(),
        )
    }

    private fun hasChatGpt(lineup: DesktopLineup): Boolean =
        lineup.providers.any { it.authKind == AuthKind.ChatgptOAuth.wire }

    /** The picker's rows: the model catalog's, as the door serves them now (DoorCatalog.picker), for a door this
     *  machine shares with another. */
    fun rowsOf(lineup: DesktopLineup): List<DoorPickerRow> = DoorCatalog(lineup).picker()

    /** splice's /login laid, and the hooks that answer it; none when the disk refuses, said in the log: the session
     *  starts without splice's /login rather than not at all. A /login of the person's in the way is left as it is,
     *  with no hook to catch what they typed, and `splice claude` says so. */
    private fun hooks(): Map<String, List<JsonObject>> = Cancellables.runCatchingCancellable {
        if (login.lay() == null) login.hooks() else emptyMap()
    }.getOrElse { failure ->
        log("[claude] splice's /login was not laid, so /login is not splice's: ${SafeFailureText.render(failure)}\n")
        emptyMap()
    }

    /** Side-by-side /login hook in the `--settings` overlay alone: writes only into splice's state folder and never
     *  touches `~/.claude`, so `splice launch claude` and `claude-splice` still catch `/login` and `/splice:login`
     *  and open Splice Console's Accounts page. */
    private fun overlayLoginHooks(): Map<String, List<JsonObject>> = Cancellables.runCatchingCancellable {
        login.hooks()
    }.getOrElse { failure ->
        log("[claude] splice's /login hook was not staged: ${SafeFailureText.render(failure)}\n")
        emptyMap()
    }
}
