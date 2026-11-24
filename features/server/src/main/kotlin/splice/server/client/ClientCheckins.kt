// NEW: #791 (server mode, F9 from an outside source). `POST /server/client/checkin`, mounted only with
// `[server.client] checkin = true`: a deployment's own launcher, which may send no version header on each turn, reports
// the version it runs once, `{"version": "0.10.0"}`, under the person's key. The server keeps each person's latest
// report in memory, so the minimum client judges their turns by it while a turn names no version itself; a restart
// forgets every report until the next check-in. The answer says what was kept and whether that version is served. A
// body past 4 KiB is refused 413 before more of it is held, a body that is anything but that one-key object, written as
// strict JSON, is refused 400 and keeps nothing, and a version is read only in its bounded shape (ClientVersionParser).
package splice.server.client

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.client.ClientVersion
import splice.core.client.ClientVersionParser
import splice.core.topology.ServerClient
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.server.SERVER_ROOT
import splice.server.ServerKey
import splice.share.CappedBodies
import splice.share.RefusalVoice
import java.util.concurrent.ConcurrentHashMap

internal class ClientCheckins(private val client: ServerClient, private val voice: RefusalVoice) {
    private val versions = ClientVersionParser()
    private val bodies = CappedBodies(voice)
    private val route = listOf(SERVER_ROOT, CLIENT_SEGMENT, CHECKIN_SEGMENT)

    /** Each person's latest reported version, by their lowercased email. */
    private val latest = ConcurrentHashMap<String, ClientVersion>()

    /** Whether [call], for [path], is a check-in this server takes. */
    fun asks(call: ApplicationCall, path: List<String>): Boolean =
        client.checkin && path == route && call.request.httpMethod == HttpMethod.Post

    /** The version [person] last checked in with, or null when they have not since the server started. */
    fun of(person: String): ClientVersion? = latest[person]

    /** Keeps the version [call]'s body reports for [key]'s person, or refuses a body that names none: 413 for one past
     *  [CHECKIN_BODY_BYTES], 400 for one that is no strict JSON `{"version"}` with no other key, whose string names a
     *  version. */
    suspend fun answer(call: ApplicationCall, key: ServerKey) {
        val sent = bodies.read(call, CHECKIN_BODY_BYTES, CHECKIN_READ_MS) ?: return
        val named = JsonScalars.strictObjectOrNull(Json, sent.decodeToString())
            ?.takeIf { it.keys == setOf(VERSION_FIELD) }?.get(VERSION_FIELD)
        // why: the version is a JSON string; `{"version":0.9.3}` is a number literal whose text would read as one.
        val version = versions.parse(JsonScalars.strIfString(named))
            ?: return voice.refuse(call, HttpStatus.BAD_REQUEST, "a check-in is {\"version\": \"0.10.0\"}")
        latest[key.person] = version
        val floor = client.floor
        val body = buildJsonObject {
            put("person", key.person)
            put(VERSION_FIELD, version.wire)
            put("minimum", floor?.wire)
            put("served", floor == null || version >= floor)
        }
        call.respondText(body.toString(), ContentType.Application.Json)
    }
}

private const val CLIENT_SEGMENT = "client"
private const val CHECKIN_SEGMENT = "checkin"
private const val VERSION_FIELD = "version"

// why: a check-in is one short version; a body any larger is no check-in, and no more of it is held.
private const val CHECKIN_BODY_BYTES = 4 * 1024

// why: a body that small has arrived well within ten seconds, or its sender is holding the connection open.
private const val CHECKIN_READ_MS = 10_000L
