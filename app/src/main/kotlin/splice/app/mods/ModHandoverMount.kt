package splice.app.mods

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import splice.app.control.ConsolePorts
import splice.app.control.api.ControlBodies
import splice.app.control.mount.ControlGuard
import splice.app.control.mount.Door
import splice.app.mods.handover.MOD_HANDOVER_PATH
import splice.app.mods.handover.ModHandoverBatch
import splice.http.JsonReply

internal class ModHandoverMount(private val ports: ConsolePorts, private val guard: ControlGuard) {
    fun register(route: Route) {
        route.post(MOD_HANDOVER_PATH) { guard.guarded(call, Door.MANAGEMENT_KEY) { receive(call) } }
    }

    private suspend fun receive(call: ApplicationCall) {
        val runner = ports.modRunner
        val batch = try {
            Json.decodeFromString<ModHandoverBatch>(call.receiveText())
        } catch (_: SerializationException) {
            null
        }
        when {
            runner == null -> error(
                call,
                HttpStatusCode.ServiceUnavailable,
                "This daemon has not loaded its mod runner.",
            )
            batch?.valid() != true -> error(call, HttpStatusCode.BadRequest, "Invalid mod handover batch.")
            !runner.acceptHandover(batch) -> error(
                call,
                HttpStatusCode.ServiceUnavailable,
                "This daemon is not taking handover batches yet, or is stopping.",
            )
            else -> JsonReply(HttpStatusCode.NoContent, "").send(call)
        }
    }

    private suspend fun error(call: ApplicationCall, status: HttpStatusCode, message: String) =
        JsonReply(status, ControlBodies.errorJson(message)).send(call)
}
