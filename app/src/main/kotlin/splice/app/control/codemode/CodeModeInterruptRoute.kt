package splice.app.control.codemode

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.api.HeadResolver
import splice.app.control.mount.ControlGuard
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.provider.codex.CodeModeInterruptOutcome
import splice.provider.codex.CodeModeInterruptedCell

/** Only this authenticated control request interrupts native cells; transport cancellation carries no such intent. */
internal class CodeModeInterruptRoute(
    private val heads: HeadResolver,
    private val ports: ConsolePorts,
    private val guard: ControlGuard,
) {
    fun register(route: Route) {
        route.post("/api/heads/{head}/sessions/{session}/interrupt") {
            guard.guarded(call) { post(call) }
        }
    }

    private suspend fun post(call: ApplicationCall) {
        val conversation = conversation(call.receiveText())
        val session = call.parameters["session"].orEmpty()
        if (conversation == null || session.isBlank()) {
            return refuse(
                call,
                HttpStatusCode.BadRequest,
                "expected an exact session and a conversation_key string",
            )
        }
        val managed = heads.resolveHeadOrRespond(call, call.parameters["head"].orEmpty()) ?: return
        interrupt(call, managed, session, conversation)
    }

    private suspend fun interrupt(call: ApplicationCall, managed: ManagedHead, session: String, conversation: String) {
        val head = managed.head.key
        if (!managed.head.healthSnapshot().running) {
            return refuse(call, HttpStatusCode.Conflict, "head $head is not running")
        }
        val bridge = ports.turns.codeMode?.bridge(head)
            ?: return refuse(call, HttpStatusCode.Conflict, "head $head has no live code-mode control capability")
        val cells = when (val outcome = bridge.interruptActiveCells(session, conversation)) {
            is CodeModeInterruptOutcome.Interrupted -> outcome.cells
            CodeModeInterruptOutcome.Unavailable -> {
                return refuse(
                    call,
                    HttpStatusCode.NotFound,
                    "no owned code-mode conversation for this head and session",
                )
            }
        }
        val status = if (cells.any { !it.terminated || it.failure != null }) {
            HttpStatusCode.Conflict
        } else {
            HttpStatusCode.OK
        }
        call.respondText(
            JsonWire.string(outcomes(head, session, conversation, cells)),
            ContentType.Application.Json,
            status,
        )
    }

    private fun conversation(text: String): String? {
        val body = JsonScalars.objectOrNull(Json, text) ?: return null
        val value = body[INTERRUPT_CONVERSATION_KEY] as? JsonPrimitive
        return if (body.keys == setOf(INTERRUPT_CONVERSATION_KEY) && value?.isString == true) {
            JsonScalars.str(value)
        } else {
            null
        }
    }

    private fun outcomes(
        head: String,
        session: String,
        conversation: String,
        cells: List<CodeModeInterruptedCell>,
    ): JsonObject = buildJsonObject {
        put("head", head); put("session", session); put(INTERRUPT_CONVERSATION_KEY, conversation)
        putJsonArray("cells") {
            cells.forEach { cell ->
                add(
                    buildJsonObject {
                        put("cell_id", cell.cellId); put("terminated", cell.terminated); put("failure", cell.failure)
                    },
                )
            }
        }
    }

    private suspend fun refuse(call: ApplicationCall, status: HttpStatusCode, message: String) {
        call.respondText(
            JsonWire.string(buildJsonObject { put("error", message) }),
            ContentType.Application.Json,
            status,
        )
    }
}

// why: the request and addressed response carry the same exact conversation identity.
private const val INTERRUPT_CONVERSATION_KEY = "conversation_key"
