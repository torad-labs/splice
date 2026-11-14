// NEW (Nov 13, 2026): a session's fallback model, set and cleared by a person. That evening every Fable login on one
// machine ran out for the week and every turn waited until Sunday. A session given a fallback runs on it while no login
// can serve the model it asks for, and goes back the first turn one can (HeldModelFallback). splice never sets one by
// itself: only these routes, on a person's word, do.
package splice.app.control.mount.fallback

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.head.SessionFallbackStore
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.http.JsonReply
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import java.io.IOException

private const val FALLBACK_BAD_BODY = "body must be {\"model\": \"<a model the session's command has>\"}"
private const val FALLBACK_NO_SESSION = "no session has that id, and no running session has that name"
private const val SEVERAL_NAMED = "more than one running session has that name; name it by its id"
private const val FALLBACK_UNWIRED = "this splice keeps no session fallbacks"

/** The session a request names, or the reply that refuses it. */
private sealed class Target {
    data class Found(val id: String, val record: SessionRecord) : Target()

    data class Refused(val reply: JsonReply) : Target()
}

/** GET /api/sessions/fallbacks, and PUT and DELETE /api/sessions/{id}/fallback. [ports] is read at call time:
 *  ControlPlane assigns [ConsolePorts.sessionFallbacks] after the server is built. */
internal class SessionFallbackRoute(
    private val registry: SessionSource,
    private val heads: Map<String, ManagedHead>,
    private val ports: ConsolePorts,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Every session that has a fallback, and the model it falls back to. */
    fun list(): JsonReply {
        val store = ports.sessionFallbacks ?: return refused(HttpStatusCode.ServiceUnavailable, FALLBACK_UNWIRED)
        val sessions = buildJsonObject { store.all().forEach { (session, model) -> put(session, model) } }
        return JsonReply(HttpStatusCode.OK, buildJsonObject { put("sessions", sessions) }.toString())
    }

    /** Gives [session], an id or a running session's name, the fallback [body] names, when its command has it. */
    fun set(session: String, body: String): JsonReply {
        val store = ports.sessionFallbacks ?: return refused(HttpStatusCode.ServiceUnavailable, FALLBACK_UNWIRED)
        val model = modelOf(body) ?: return refused(HttpStatusCode.BadRequest, FALLBACK_BAD_BODY)
        return when (val found = target(session)) {
            is Target.Refused -> found.reply
            is Target.Found -> setOn(store, found, model)
        }
    }

    /** Keeps [model] as [found]'s fallback when the session's command has it, or every command when none is known. */
    private fun setOn(store: SessionFallbackStore, found: Target.Found, model: String): JsonReply {
        val command = found.record.head
        val onHead = command?.let { key -> heads[key]?.let(::listOf) } ?: heads.values.toList()
        if (onHead.none { it.statusline.catalog?.contains(model) == true }) {
            val where = command?.let { "the session's command, $it," } ?: "no command splice runs"
            return refused(HttpStatusCode.BadRequest, "$where has no model $model")
        }
        return saved(store, found.id, model, "runs on $model while every login is held on the model it asks for")
    }

    /** Takes [session]'s fallback away: it waits for the model it asks for again, as every session does by default. A
     *  session the registry no longer lists is named by the id its fallback is kept under. A name two running sessions
     *  share is refused, as [set] refuses it, since fallbacks are kept by id and clearing the name would clear neither. */
    fun clear(session: String): JsonReply {
        val store = ports.sessionFallbacks ?: return refused(HttpStatusCode.ServiceUnavailable, FALLBACK_UNWIRED)
        val id = when (val found = target(session)) {
            is Target.Found -> found.id
            is Target.Refused ->
                session.takeIf { found.reply.status == HttpStatusCode.NotFound && it in store.all() }
                    ?: return found.reply
        }
        return saved(store, id, null, "waits for the model it asks for while every login is held on it")
    }

    /** The session [session] names: the one with that id, or else the one running session with that name. */
    private fun target(session: String): Target {
        val all = registry.read()
        all.firstOrNull { it.sessionId == session }?.let { return Target.Found(session, it) }
        val named = all.filter { it.name == session && it.availability != SessionAvailability.GONE }
            .mapNotNull { record -> record.sessionId?.let { Target.Found(it, record) } }
        return when {
            named.size == 1 -> named.single()
            named.isEmpty() -> Target.Refused(refused(HttpStatusCode.NotFound, FALLBACK_NO_SESSION))
            else -> Target.Refused(refused(HttpStatusCode.Conflict, SEVERAL_NAMED))
        }
    }

    private fun saved(store: SessionFallbackStore, session: String, model: String?, effect: String): JsonReply =
        try {
            store.set(session, model)
            JsonReply(
                HttpStatusCode.OK,
                buildJsonObject {
                    put("session", session)
                    put("model", model?.let(::JsonPrimitive) ?: JsonNull)
                    put("said", "The session $effect.")
                }.toString(),
            )
        } catch (e: IOException) {
            refused(HttpStatusCode.InternalServerError, "the setting could not be saved: ${SafeFailureText.render(e)}")
        }

    private fun refused(status: HttpStatusCode, reason: String): JsonReply =
        JsonReply(status, buildJsonObject { put("error", reason) }.toString())

    private fun modelOf(body: String): String? =
        (JsonScalars.objectOrNull(json, body)?.get("model") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
