package splice.observability.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.runtime.PerfProfileRequest
import splice.core.util.WallClock

/**
 * The status of a capture that is not there.
 *
 * Asked for the latest capture when this process has taken none, or keeps none, the answer is "none". Until
 * Nov 11, 2026 it was a made-up record, failed, with a new id on every call, and the console drew a red
 * "failed · 0 B" for a capture nobody had started. An id that is asked for and not kept still answers failed.
 */
internal class ProfileAbsence(private val identity: PerfIdentity, private val clock: WallClock) {
    fun reply(id: String?): JsonObject = if (id == null) {
        none()
    } else {
        ProfileRecord(PerfProfileRequest(), clock())
            .reply(identity, clock(), PROFILE_FAILED, "profile_not_found_or_expired")
    }

    private fun none(): JsonObject = buildJsonObject {
        put("schema_version", identity.schemaVersion)
        put("status", PROFILE_NONE)
        put("profile_id", JsonNull)
        put("identity", Json.encodeToJsonElement(identity))
        put("process_id", ProcessHandle.current().pid())
        put("size_bytes", 0)
        put("artifact_available", false)
        put("issues", JsonArray(emptyList()))
    }
}

private const val PROFILE_NONE = "none"
