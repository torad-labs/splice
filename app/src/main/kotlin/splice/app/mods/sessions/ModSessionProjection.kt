package splice.app.mods.sessions

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.mods.handover.MOD_HANDOVER_CAPACITY
import splice.app.mods.run.ModEvent
import splice.app.mods.run.ModEventAudience
import splice.app.mods.run.ModEventHub
import splice.app.mods.run.ModEventProjection
import splice.launch.ModsOff

internal const val MOD_SESSION_START = "session.start"

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

/** A newer sighting cannot take the start from an earlier one already waiting on the wire. */
internal class ModEarlySessions(private val hub: ModEventHub) {
    private val waiting = ArrayDeque<ModEarlySession>()

    @Volatile var ready = true
        private set

    fun hold() {
        ready = false
    }

    @Synchronized
    fun poll(): ModEarlySession? = waiting.removeFirstOrNull().also { if (it == null) ready = true }

    fun stage(sighting: ModSessionSighting, launches: ModLaunches): Boolean {
        if (ready) return false
        return synchronized(this) {
            if (ready) return@synchronized false
            if (waiting.size < MOD_HANDOVER_CAPACITY) {
                val ahead = ModEarlySession(sighting, launches)
                waiting.addLast(ahead)
                hub.offer(MOD_SESSION_START, null, ahead, ahead)
            }
            true
        }
    }
}

/** A position in each intake queue, resolved only after the predecessor's started pairs are known. */
internal class ModEarlySession(val sighting: ModSessionSighting, private val launches: ModLaunches) :
    ModEventProjection,
    ModEventAudience {
    @Volatile var projection: ModSessionProjection? = null

    @Volatile private var recovered = false
    override val unresolved: Boolean get() = !recovered

    fun resolve() {
        recovered = true
    }

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
