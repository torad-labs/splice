package splice.app.control

import splice.core.model.ClientWindows
import splice.core.model.ModelCatalog

/** What the statusline route needs from one head to turn Claude Code's counts back into the row's units. */
public data class StatuslineContext(
    /** This head's model catalog, for the statusline: Claude Code reports every non-"[1m]" row
     *  against the PINNED row's window and splice scales the counts it sends back, so the blob
     *  Claude Code pipes to /statusline carries client units on a scaled row. The catalog is what
     *  turns them back into the row's declared window and its label. Null = render the blob as is. */
    val catalog: ModelCatalog? = null,
    /** Where the statusline route records each session's real window (`session_id` +
     *  `context_window_size` from the blob) so the head scales that session's counts against it.
     *  Null = a head that never learns (tests). */
    val clientWindows: ClientWindows? = null,
)
