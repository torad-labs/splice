package splice.app.sources.observability

/** Confirms whether the captured page reached durable history before its producer releases ownership. */
internal fun interface PerfCheckpointSettlement {
    fun settle(durable: Boolean)
}
