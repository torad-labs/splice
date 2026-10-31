package splice.hub.front

/** Leaves socket input untouched until the daemon attests the generation this link may represent. */
internal class HolderGenerationReads(
    private val attestations: RouteAttestations,
    private val timers: Timers,
    private val clock: Clock,
) {
    fun bind(holder: Holder, link: HolderLink, attested: (String) -> Unit) {
        holder.link = link
        holder.attestedGeneration = null
        holder.ready = false
        link.reading = false
        await(holder, link, attested)
    }

    private fun await(holder: Holder, link: HolderLink, attested: (String) -> Unit) {
        if (link.closed || holder.link !== link) return
        attestations.read(holder.socket) { generation -> received(holder, link, generation, attested) }
    }

    private fun received(holder: Holder, link: HolderLink, generation: String?, attested: (String) -> Unit) {
        if (link.closed || holder.link !== link) return
        if (generation == null) {
            timers.at(clock.now() + RETRY) { await(holder, link, attested) }
        } else {
            attested(generation)
            if (holder.link === link && !link.closed) link.resumeReading()
        }
    }
}
