// NEW: server mode (docs/specs/server-mode.md, "Mode routes"). The modes a team server's listener hands
// `/server/mod/<name>/...` to, as the daemon holds them now: a mode serves there only while splice.toml's
// `[modes.<name>] server_routes` is true, and with `server_admin_only` only an admin. The switches are read per
// request, so a write to splice.toml applies to the next one. A mode that does not serve here is no mode at all to its
// caller.
package splice.app.modes

import splice.core.server.ModAnswer
import splice.core.server.ModRequest
import splice.core.server.ServerModRoutes

internal class ModeServerRoutes(private val modes: DaemonModes) : ServerModRoutes {
    override fun answer(name: String, request: ModRequest): ModAnswer {
        val mode = modes.mode(name) ?: return ModAnswer.NoSuchMod
        val setting = modes.settings[name]
        return when {
            setting?.serverRoutes != true -> ModAnswer.NoSuchMod
            setting.serverAdminOnly == true && !request.caller.admin -> ModAnswer.AdminsOnly
            else -> when (val run = ModeFailure.run(name) { mode.handler.serve(request) }) {
                is ModeRun.Done -> ModAnswer.Answered(run.value)
                is ModeRun.Threw -> ModAnswer.Failed(run.said)
            }
        }
    }
}
