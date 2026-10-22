package splice.hub.front

/** Leave legacy input in the kernel until the owner attests the generation every new exchange must carry. */
internal class HolderGenerationReads(
    private val routes: Routes,
    private val timers: Timers,
    private val clock: Clock,
) {
    fun bind(holder: Holder, link: HolderLink) {
        holder.link = link
        if (holder.generation != null) return
        link.reading = false
        await(holder, link)
    }

    private fun await(holder: Holder, link: HolderLink) {
        if (link.closed || holder.link !== link) return
        val generation = routes.generation(holder.socket)
        if (generation == null) {
            timers.at(clock.now() + RETRY) { await(holder, link) }
        } else {
            holder.generation = generation
            link.resumeReading()
        }
    }
}
