// NEW: #791 (server mode, F9 from an outside source). `POST /server/client/checkin`, mounted only with
// `[server.client] checkin = true`: a deployment's own launcher, which may send no version header on each turn, reports
// the version it runs once, `{"version": "0.10.0"}`, under the person's key. The server keeps each person's latest
// report in memory, so the minimum client judges their turns by it while a turn names no version itself; a restart
// forgets every report until the next check-in. The answer says what was kept and whether that version is served.
package splice.server.client

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.client.ClientVersion
import splice.core.client.ClientVersionParser
import splice.core.topology.ServerClient
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.http.JsonBody
import splice.server.SERVER_ROOT
import splice.server.ServerKey
import splice.share.RefusalVoice
import java.util.concurrent.ConcurrentHashMap

internal class ClientCheckins(private val client: ServerClient, private val voice: RefusalVoice) {
    private val versions = ClientVersionParser()
    private val jsonBody = JsonBody()
    private val route = listOf(SERVER_ROOT, CLIENT_SEGMENT, CHECKIN_SEGMENT)

    /** Each person's latest reported version, by their lowercased email. */
    private val latest = ConcurrentHashMap<String, ClientVersion>()

    /** Whether [call], for [path], is a check-in this server takes. */
    fun asks(call: ApplicationCall, path: List<String>): Boolean =
        client.checkin && path == route && call.request.httpMethod == HttpMethod.Post

    /** The version [person] last checked in with, or null when they have not since the server started. */
    fun of(person: String): ClientVersion? = latest[person]

    /** Keeps the version [call]'s body reports for [key]'s person, or refuses a body that names none. */
    suspend fun answer(call: ApplicationCall, key: ServerKey) {
        val named = JsonScalars.str(jsonBody.parse(call), VERSION_FIELD)
        val version = versions.parse(named)
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
