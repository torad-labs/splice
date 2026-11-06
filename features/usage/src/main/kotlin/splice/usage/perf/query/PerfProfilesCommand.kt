package splice.usage.perf.query

import kotlinx.serialization.json.JsonObject
import splice.core.perf.runtime.PerfProfileRequest
import splice.core.perf.runtime.PerfProfiles
import splice.usage.perf.query.PerfQueryJson.queryError

/** Shared validation for local CLI profile control; artifact paths remain owned by PerfProfiles. */
public class PerfProfilesCommand(private val profiles: PerfProfiles) : PerfProfileCommand {
    override fun execute(args: List<String>): JsonObject {
        val action = args.firstOrNull() ?: "status"
        val values = PerfCliArguments.read(args.drop(1)) ?: return invalid()
        return if (valid(action, values)) dispatch(action, values) else invalid()
    }

    private fun valid(action: String, values: Map<String, String>): Boolean = when (action) {
        "capabilities" -> values.isEmpty()
        "status" -> values.keys.all { it == "id" }
        "stop" -> values.keys == setOf("id")
        "start" -> values.keys.all { it in setOf("profiler", "mode", "duration") }
        else -> false
    }

    private fun dispatch(action: String, values: Map<String, String>): JsonObject = when (action) {
        "capabilities" -> profiles.capabilities()
        "status" -> profiles.status(values["id"])
        "stop" -> profiles.stop(values.getValue("id"))
        "start" -> start(values)
        else -> invalid()
    }

    private fun start(values: Map<String, String>): JsonObject {
        val duration = if ("duration" in values) values["duration"]?.toIntOrNull() else DEFAULT_DURATION
        return if (duration == null || duration !in 1..MAX_DURATION) {
            invalid()
        } else {
            profiles.start(PerfProfileRequest(values["profiler"] ?: "jfr", values["mode"] ?: "cpu", duration))
        }
    }

    private fun invalid(): JsonObject = queryError(
        "invalid_profile_arguments",
        "unsupported_profile_action_or_settings",
    )
}

private const val DEFAULT_DURATION = 60
private const val MAX_DURATION = 3_600
