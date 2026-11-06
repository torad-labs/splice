package splice.app.observability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.runtime.PerfProfileArtifact
import splice.core.perf.runtime.PerfProfileRequest
import splice.core.perf.runtime.PerfProfiles
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.DaemonSettings
import splice.daemonclient.MgmtKeyFile
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.PostReading

/** Profiles always target the authenticated running daemon; this command process never starts a capture. */
internal class PerfRemoteProfiles(private val env: EnvReader, private val errors: TerminalOutput) : PerfProfiles {
    override fun capabilities(): JsonObject = read("/capabilities")
    override fun status(id: String?): JsonObject = if (id == null) read("") else read(idPath(id))
    override fun stop(id: String): JsonObject = request(idPath(id) + "/stop", "{}")
    override fun artifact(id: String): PerfProfileArtifact? = null
    override fun start(request: PerfProfileRequest): JsonObject = request(
        "",
        buildJsonObject {
            put("profiler", request.profiler)
            put("mode", request.mode)
            put("durationSeconds", request.durationSeconds)
        }.toString(),
    )

    private fun idPath(id: String): String = if (PROFILE_ID.matches(id)) "/$id" else "/invalid"
    private fun read(suffix: String): JsonObject = request(suffix, null)

    private fun request(suffix: String, body: String?): JsonObject {
        val key = MgmtKeyFile().read(env)
        if (key !is MgmtKeyRead.Present) return failed("management_key_unavailable")
        val port = DaemonSettings(errors).controlPort(env)
        val url = "http://127.0.0.1:$port/api/perf/profiles$suffix"
        val reply = if (body == null) {
            ControlPlaneClient.send(url, "GET", key.key, TIMEOUT_MS)
        } else {
            (ControlPlaneClient.post(url, key.key, body, TIMEOUT_MS) as? PostReading.Answered)?.reply
        } ?: return failed("daemon_unavailable_or_reply_lost")
        return decode(reply.body, reply.status)
    }

    private fun decode(body: String, status: Int): JsonObject {
        val parsed = JsonScalars.objectOrNull(Json, body) ?: return failed("invalid_daemon_reply")
        return if (status in ControlPlaneClient.OK_RANGE || JsonScalars.str(parsed, "status") != null) {
            parsed
        } else {
            failed("daemon_refused_profile_request")
        }
    }

    private fun failed(reason: String): JsonObject = buildJsonObject { put("status", "error"); put("reason", reason) }
}

private val PROFILE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

// Bound authenticated profile-control HTTP waits to fifteen seconds so a stopped daemon cannot hang the CLI.
private const val TIMEOUT_MS = 15_000
