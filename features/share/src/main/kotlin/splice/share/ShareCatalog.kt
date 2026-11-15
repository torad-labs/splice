// NEW: what a listener that offers this machine's heads answers about them, apart from the turns: the directory at
// `GET /` and the shape it is written in, a command's launch at `GET /<command>/launch`, and the two refusals a
// command's name can meet (not offered, not running). Moved out of ShareListener, unchanged on the wire, when server
// mode (docs/specs/server-mode.md) came to serve the same two routes to key holders: one writer, so the other
// machine's `splice remote` reads both the same.
package splice.share

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.topology.SHARE_AUTH_PARAM
import splice.core.topology.SHARE_MODELS_PARAM
import splice.core.wire.HttpStatus

/** What `GET /` answers: this machine and the commands it offers. */
@Serializable
public data class ShareInfo(val machine: String, val commands: List<ShareOffer>)

/** One offered command: what an address names it by, its head, whether it runs now, its models and its logins. */
@Serializable
public data class ShareOffer(
    val command: String,
    val head: String,
    val running: Boolean,
    val models: List<String>,
    val logins: List<String>,
)

public class ShareCatalog(
    private val heads: SharedHeads,
    /** Blocking work runs here: reading a head's logins and its live launch. */
    private val io: CoroutineDispatcher,
    private val errors: RefusalVoice,
) {
    private val launches = SharedLaunches(heads.machine)
    private val json = Json { encodeDefaults = true }

    /** `GET /`: this machine and each command it offers, with its head, models and logins. */
    public suspend fun directory(call: ApplicationCall) {
        val offers = withContext(io) {
            heads.offered().map { head ->
                val models = head.launch()?.models?.availableModelIds.orEmpty()
                ShareOffer(head.command, head.key, head.running(), models, head.logins())
            }
        }
        val body = json.encodeToString(ShareInfo.serializer(), ShareInfo(heads.machine, offers))
        call.respondText(body, ContentType.Application.Json)
    }

    /** The offered head [name] names, by the command it runs as here or by its head's key. One not offered is answered
     *  404 here, naming the ones that are, and null is returned. */
    public suspend fun named(call: ApplicationCall, name: String): SharedHead? {
        val offered = withContext(io) { heads.offered() }
        val head = offered.firstOrNull { it.command == name } ?: offered.firstOrNull { it.key == name }
        if (head == null) {
            val offers = offered.joinToString(", ") { it.command }.ifEmpty { "nothing" }
            val why = "${heads.machine} offers no command named '$name'; it offers $offers"
            errors.refuse(call, HttpStatus.NOT_FOUND, why)
        }
        return head
    }

    /** `GET /<command>/launch?auth=&models=`: what the other machine's launch needs, filtered by both params. */
    public suspend fun launch(call: ApplicationCall, head: SharedHead) {
        if (!head.running()) return notRunning(call, head)
        val params = call.request.queryParameters
        val read = withContext(io) { launches.of(head, params[SHARE_AUTH_PARAM], params[SHARE_MODELS_PARAM]) }
        when (read) {
            is SharedLaunchRead.Read -> {
                val body = json.encodeToString(SharedLaunch.serializer(), read.launch)
                call.respondText(body, ContentType.Application.Json)
            }
            is SharedLaunchRead.Refused -> errors.refuse(call, read.status, read.why)
        }
    }

    public suspend fun notRunning(call: ApplicationCall, head: SharedHead) {
        val why = "${head.command} is not running on ${heads.machine}; `splice restart` there starts it again"
        errors.refuse(call, HttpStatus.SERVICE_UNAVAILABLE, why)
    }
}

/** The path segment, after a command's name, that roots its head's API: a turn is a request under it. */
public const val HEAD_API_ROOT: String = "v1"

/** The path segment, after a command's name, that answers what a launch of that command needs. */
public const val HEAD_LAUNCH_ROUTE: String = "launch"
