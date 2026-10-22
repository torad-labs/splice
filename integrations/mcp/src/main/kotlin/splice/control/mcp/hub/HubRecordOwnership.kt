package splice.control.mcp.hub

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars

private const val HOLDER_LAUNCHER_STARTED_AT = "launcher_started_at"

/** A generation-specific systemd scope recorded before a holder process can escape. */
internal data class HubHolderScopeClaim(
    val generation: String,
    val scope: String,
)

/** A generation's holder process and unique systemd scope, known before its child can be attested. */
internal data class HubHolderLaunch(
    val generation: String,
    val holderPid: Long,
    val scope: String,
    val holderStartedAt: Long? = null,
) {
    fun claim(): HubHolderScopeClaim = HubHolderScopeClaim(generation, scope)
}

/** The holder and child in one generation-specific systemd scope. */
internal data class HubHolderOwner(
    val generation: String,
    val holderPid: Long,
    val childPid: Long,
    val scope: String,
    val holderStartedAt: Long? = null,
    val executable: String? = null,
    val executableIdentity: String? = null,
) {
    fun launch(): HubHolderLaunch = HubHolderLaunch(generation, holderPid, scope, holderStartedAt)
}

/** Decodes and updates an owner only while its exact socket record still occupies the file. */
internal class HubRecordOwnership(private val files: HubRecordFiles) {
    fun attest(record: HubHolderRecord, owner: HubHolderOwner): HubHolderRecord? {
        val current = files.read(record.path)?.takeIf { it == record.body } ?: return null
        if (launch(current)?.let { it.claim() != owner.launch().claim() || it.holderPid != owner.holderPid } == true) {
            return null
        }
        val attested = attestedBody(current, owner)
        return files.write(record.path, attested).takeIf { it }?.let { HubHolderRecord(record.path, attested) }
    }

    private fun attestedBody(current: JsonObject, owner: HubHolderOwner): JsonObject {
        val fields = current.toMutableMap().also { values ->
            values[HubFields.GENERATION] = JsonPrimitive(owner.generation)
            values[HubFields.PID] = JsonPrimitive(owner.holderPid)
            values[HubFields.CHILD_PID] = JsonPrimitive(owner.childPid)
            values[HubFields.SCOPE] = JsonPrimitive(owner.scope)
            values[HubFields.STATE] = JsonPrimitive(HOLDER_LIVE)
            owner.holderStartedAt?.let { values[HOLDER_LAUNCHER_STARTED_AT] = JsonPrimitive(it) }
            owner.executable?.let { values[HubFields.EXECUTABLE] = JsonPrimitive(it) }
            owner.executableIdentity?.let { values[HubFields.EXECUTABLE_ID] = JsonPrimitive(it) }
        }
        return JsonObject(fields)
    }

    fun owner(record: HubHolderRecord): HubHolderOwner? = owner(record.body)

    fun launch(record: HubHolderRecord): HubHolderLaunch? = launch(record.body)

    fun claim(record: HubHolderRecord): HubHolderScopeClaim? = claim(record.body)

    fun started(record: HubHolderRecord, launch: HubHolderLaunch): HubHolderRecord? {
        val current = files.read(record.path)?.takeIf { it == record.body } ?: return null
        if (claim(current) != launch.claim() || launch.holderStartedAt == null) return null
        val started = JsonObject(
            current + mapOf(
                HubFields.PID to JsonPrimitive(launch.holderPid),
                HOLDER_LAUNCHER_STARTED_AT to JsonPrimitive(launch.holderStartedAt),
            ),
        )
        return files.write(record.path, started).takeIf { it }?.let { HubHolderRecord(record.path, started) }
    }

    fun unlaunched(record: HubHolderRecord): Boolean =
        claim(record)?.let { claim ->
            JsonScalars.str(record.body, HubFields.LAUNCH_GATED) == "true" &&
                launch(record) == null &&
                !files.gateReleased(claim)
        } == true

    fun currentExit(record: HubHolderRecord, owner: HubHolderOwner): Boolean =
        files.read(record.path)?.let { current ->
            JsonScalars.str(current, HubFields.STATE) == HOLDER_EXITED && owner(current) == owner
        } == true

    fun currentScopeExit(record: HubHolderRecord): Boolean =
        files.read(record.path)?.let { current ->
            JsonScalars.str(current, HubFields.STATE) == HOLDER_EXITED &&
                JsonScalars.long(current, HOLDER_EXIT_CONFIRMED_AT) != null &&
                claim(current) == claim(record.body)
        } == true

    fun currentLaunchExit(record: HubHolderRecord): Boolean =
        files.read(record.path)?.let { current ->
            JsonScalars.str(current, HubFields.STATE) == HOLDER_EXITED &&
                JsonScalars.long(current, HOLDER_EXIT_CONFIRMED_AT) != null &&
                launch(current) == launch(record.body)
        } == true

    private fun owner(body: JsonObject): HubHolderOwner? = launch(body)?.let { launch ->
        JsonScalars.long(body, HubFields.CHILD_PID)?.let { child ->
            HubHolderOwner(
                launch.generation,
                launch.holderPid,
                child,
                launch.scope,
                launch.holderStartedAt,
                JsonScalars.str(body, HubFields.EXECUTABLE),
                JsonScalars.str(body, HubFields.EXECUTABLE_ID),
            )
        }
    }

    private fun launch(body: JsonObject): HubHolderLaunch? = claim(body)?.let { claim ->
        JsonScalars.long(body, HubFields.PID)?.let { holder ->
            HubHolderLaunch(
                claim.generation,
                holder,
                claim.scope,
                JsonScalars.long(body, HOLDER_LAUNCHER_STARTED_AT),
            )
        }
    }

    private fun claim(body: JsonObject): HubHolderScopeClaim? {
        val generation = JsonScalars.str(body, HubFields.GENERATION)
        val scope = JsonScalars.str(body, HubFields.SCOPE)
        if (generation == null || scope == null) return null
        return HubHolderScopeClaim(generation, scope)
    }
}
