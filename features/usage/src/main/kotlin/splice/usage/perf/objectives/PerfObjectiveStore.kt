package splice.usage.perf.objectives

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import splice.core.perf.OutcomeTag
import splice.core.perf.observation.PerfObjectiveSet
import splice.core.util.PerfWorkBudget
import splice.core.util.SecureFile
import splice.usage.perf.query.PerfObjectiveSelection
import java.io.IOException
import java.nio.file.Path

/** Explicit local objectives and rules. Hosts supply a StatePaths-derived path and close the store at shutdown. */
public class PerfObjectiveStore(private val path: Path) : AutoCloseable {
    private val lease = PerfWorkBudget.acquire(STORE_BYTES)
    private var value = ObjectiveValidation.canonical(ObjectiveSettings())
    private var issue: String? = if (lease == null) "shared_work_budget" else null
    private var closed = false

    init {
        if (lease != null) load()
    }

    @Synchronized
    public fun settings(): JsonObject {
        val result = ObjectiveJson.codec.encodeToJsonElement(value) as JsonObject
        return ObjectiveJson.reply(
            JsonObject(
                result + listOfNotNull(
                    issue?.let {
                        "load_issue" to ObjectiveJson.text(it)
                    },
                ).toMap(),
            ),
        )
    }

    /** Validates the entire replacement before atomic publication. A failed write never changes effective settings. */
    @Synchronized
    public fun update(settings: JsonObject): JsonObject {
        if (closed || lease == null) return ObjectiveJson.failure("store_unavailable")
        if (settings.toString().length > SETTINGS_LIMIT) return ObjectiveJson.failure("settings_size_limit")
        return try {
            val candidate = ObjectiveJson.codec.decodeFromJsonElement(ObjectiveSettings.serializer(), settings)
            val problem = ObjectiveValidation.problem(candidate)
            if (problem == null) publish(ObjectiveValidation.canonical(candidate)) else ObjectiveJson.failure(problem)
        } catch (_: SerializationException) {
            ObjectiveJson.failure("invalid_settings_shape")
        } catch (_: IOException) {
            ObjectiveJson.failure("settings_write_failed")
        }
    }

    private fun publish(normalized: ObjectiveSettings): JsonObject {
        val encoded = ObjectiveJson.codec.encodeToString(normalized)
        if (encoded.toByteArray(
                Charsets.UTF_8,
            ).size > SETTINGS_LIMIT
        ) {
            return ObjectiveJson.failure("settings_size_limit")
        }
        SecureFile.writeAtomic0600(path, encoded)
        value = normalized
        issue = null
        return ObjectiveJson.reply(
            buildJsonObject { put("status", OutcomeTag.OK.wire); put("revision", value.revision) },
        )
    }

    @Synchronized
    public fun objective(id: String): PerfObjectiveSet? = snapshot()?.objectives?.find { it.id == id }?.objective()

    /** The query source must enforce these filters when resolving a named objective. */
    @Synchronized
    public fun cohort(id: String): Map<String, String>? = snapshot()?.objectives?.find { it.id == id }?.cohort?.toMap()

    /** Authoritative named query selection from one immutable generation. */
    @Synchronized
    public fun selection(id: String): PerfObjectiveSelection? = snapshot()?.objectives?.find { it.id == id }?.let {
        PerfObjectiveSelection(it.objective(), it.cohort.toMap())
    }

    @Synchronized
    internal fun snapshot(): ObjectiveSettings? = value.takeIf { !closed && issue == null }

    /** Serializes the revision check and local lifecycle commit with settings updates; never wraps query or HTTP. */
    @Synchronized
    internal fun publishIfCurrent(
        revision: String,
        publication: ObjectivePublication,
    ): ObjectivePublicationResult = when {
        snapshot()?.revision != revision -> ObjectivePublicationResult.SETTINGS_CHANGED
        publication.publish() -> ObjectivePublicationResult.PUBLISHED
        else -> ObjectivePublicationResult.FAILED
    }

    internal fun lifecyclePath(): Path = path.resolveSibling("${path.fileName}.alerts")

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            value = ObjectiveSettings()
            lease?.close()
        }
    }

    private fun load() {
        try {
            val encoded = ObjectiveJson.read(path, SETTINGS_LIMIT) ?: return
            val candidate = ObjectiveJson.codec.decodeFromString(ObjectiveSettings.serializer(), encoded)
            issue = ObjectiveValidation.problem(candidate)
            if (issue == null) value = ObjectiveValidation.canonical(candidate)
        } catch (_: SerializationException) {
            issue = "invalid_settings_file"
        } catch (_: IOException) {
            issue = "settings_read_failed"
        }
    }
}

private const val SETTINGS_LIMIT = 32_768
private const val STORE_BYTES = 512L * 1_024
