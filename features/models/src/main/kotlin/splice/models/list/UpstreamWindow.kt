package splice.models.list

/** The context windows an endpoint publishes for one model. An absent number is "not published", never zero. */
internal data class UpstreamWindow(
    val context: Long? = null,
    /** The largest window the endpoint accepts as an override, above its default [context]
     *  (the Codex backend's `max_context_window`: 872000 for gpt-6-astra against a 272000 default). */
    val maxContext: Long? = null,
)
