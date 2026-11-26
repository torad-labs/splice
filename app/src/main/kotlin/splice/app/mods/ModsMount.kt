package splice.app.mods

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.control.ConsolePorts
import splice.app.control.api.ControlBodies
import splice.app.control.mount.ControlGuard
import splice.app.control.mount.Door
import splice.app.mods.install.InstalledMods
import splice.app.mods.install.ModRefusal
import splice.core.util.SafeFailureText
import splice.http.JsonReply
import splice.upstream.codemode.ProcessDispatchers
import java.io.IOException

/** The Kotlin mount of the mod contract's catalog path. */
internal const val MOD_CATALOG_PATH = "/api/mods"

internal class ModsMount(
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
    private val io: CoroutineDispatcher = ProcessDispatchers().io(),
) {
    fun register(route: Route) {
        route.route(MOD_CATALOG_PATH) {
            get { guard.guarded(call) { serve(call) { answer(call, ModRows(it).all()) } } }
            get("/{name}") { guard.guarded(call) { serve(call) { one(call, it) } } }
            post { guard.guarded(call, Door.MANAGEMENT_KEY) { serve(call) { install(call, it) } } }
            put("/{name}") { guard.guarded(call, Door.MANAGEMENT_KEY) { serve(call) { switch(call, it) } } }
            delete("/{name}") { guard.guarded(call, Door.MANAGEMENT_KEY) { serve(call) { remove(call, it) } } }
        }
    }

    private suspend fun one(call: ApplicationCall, mods: InstalledMods) {
        val name = call.parameters["name"].orEmpty()
        val mod = mods.find(name) ?: throw ModRefusal("no installed mod named $name", ModRefusal.Kind.MISSING)
        answer(call, ModRows(mods).row(mod))
    }

    private suspend fun install(call: ApplicationCall, mods: InstalledMods) {
        val mod = mods.install(ModRequests.install(call))
        mods.runtime.changed(mod.name)
        mods.runtime.settled(mod.name)
        answer(call, ModRows(mods).row(mod), HttpStatusCode.Created)
    }

    private suspend fun switch(call: ApplicationCall, mods: InstalledMods) {
        val mod = mods.switch(call.parameters["name"].orEmpty(), ModRequests.switch(call))
        mods.runtime.changed(mod.name)
        mods.runtime.settled(mod.name)
        answer(call, ModRows(mods).row(mod))
    }

    private suspend fun remove(call: ApplicationCall, mods: InstalledMods) {
        val mod = mods.remove(call.parameters["name"].orEmpty())
        mods.runtime.changed(mod.name)
        mods.runtime.settled(mod.name)
        answer(
            call,
            buildJsonObject {
                put("name", mod.name)
                put("linked", mod.linked)
            },
        )
    }

    private suspend inline fun serve(call: ApplicationCall, crossinline action: suspend (InstalledMods) -> Unit) {
        val mods = ports.mods
        val why = mods?.unreadable
        when {
            mods == null -> error(call, HttpStatusCode.ServiceUnavailable, "this daemon has not loaded its mods")
            why != null -> error(call, HttpStatusCode.ServiceUnavailable, why)
            else -> withContext(io) {
                try {
                    action(mods)
                } catch (refused: ModRefusal) {
                    error(call, status(refused.kind), refused.message.orEmpty())
                } catch (failure: IOException) {
                    error(
                        call,
                        HttpStatusCode.InternalServerError,
                        "could not update mods: ${SafeFailureText.render(failure)}",
                    )
                }
            }
        }
    }

    private fun status(kind: ModRefusal.Kind): HttpStatusCode = when (kind) {
        ModRefusal.Kind.INPUT -> HttpStatusCode.BadRequest
        ModRefusal.Kind.MISSING -> HttpStatusCode.NotFound
        ModRefusal.Kind.CONFLICT -> HttpStatusCode.Conflict
    }

    private suspend fun answer(call: ApplicationCall, body: JsonObject, status: HttpStatusCode = HttpStatusCode.OK) =
        JsonReply(status, body.toString()).send(call)

    private suspend fun error(call: ApplicationCall, status: HttpStatusCode, message: String) =
        JsonReply(status, ControlBodies.errorJson(message)).send(call)
}
