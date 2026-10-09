package splice.configuration.add

/** The one model a local runtime serves, as the runtime described it: its id, its display label and the window
 *  it ADVERTISES, never the model's raw ceiling. */
public data class RuntimeModel(
    public val id: String,
    public val label: String,
    public val contextWindow: Long,
)
