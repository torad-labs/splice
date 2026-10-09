package splice.usage

import splice.core.model.ClientWindows
import splice.core.model.ModelCatalog

/** What the statusline needs of a head to put the client's units back into the row's window. */
public data class UsageHeadStatusline(
    /** Turns the statusline blob's client units back into the row's declared window and label. */
    val catalog: ModelCatalog? = null,
    /** Where the statusline records each session's real window. Null = a head that never learns. */
    val clientWindows: ClientWindows? = null,
)
