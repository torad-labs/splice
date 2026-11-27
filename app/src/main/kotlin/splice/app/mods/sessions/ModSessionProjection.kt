package splice.app.mods.sessions

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.mods.run.ModEvent
import splice.app.mods.run.ModEventAudience
import splice.app.mods.run.ModEventProjection
import splice.launch.ModsOff

internal class ModSessionProjection(
    private val name: String,
    private val pending: ModPendingSession,
    private val seenBefore: Boolean?,
) : ModEventProjection {
    override fun invoke(): ModEvent? = pending.entry?.let { entry ->
        val id = entry.pair.id
        ModEvent(
            name,
            id,
            buildJsonObject {
                put("session", id)
                put("cwd", entry.context.cwd)
                put("labels", entry.context.labels)
                if (seenBefore != null) put("seenBefore", seenBefore)
            },
        )
    }
}

/** A position in each intake queue, resolved only after the predecessor's started pairs are known. */
internal class ModEarlySession(val sighting: ModSessionSighting, private val launches: ModLaunches) :
    ModEventProjection,
    ModEventAudience {
    @Volatile var projection: ModSessionProjection? = null

    override fun invoke(): ModEvent? = projection?.invoke()

    override fun options(): ModsOff? = sighting.owner?.let(launches::stored)?.launch?.settings?.modsOff
}

internal data class ModSessionSighting(
    val id: String,
    val owner: String?,
    val at: Long? = null,
    val cwd: String? = null,
    val scrubber: splice.app.mods.events.ModEventText? = null,
)
