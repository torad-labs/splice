package splice.hub.front

internal data class HolderLinkContext(
    val files: HubFiles,
    val connections: Links,
    val timers: Timers,
    val clock: Clock,
    val routes: Routes,
    val relay: Relay,
)
