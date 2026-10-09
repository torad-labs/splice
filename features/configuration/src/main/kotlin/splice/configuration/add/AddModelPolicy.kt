package splice.configuration.add

/** How the endpoint's model list is used for a profile. */
internal data class AddModelPolicy(
    /** Whether the endpoint's model list decides the models check. False for a server that answers
     *  ANY model id (llama-server lists a file path, not the id a row sends): there an unlisted row
     *  is trusted and reported as such, the rule LocalRuntimeProbe applies at boot. */
    val listAuthoritative: Boolean = true,
    /** The endpoint supplies the serving roster; declared rows supply only pinned models and tiers. */
    val discoverRoster: Boolean = false,
)
