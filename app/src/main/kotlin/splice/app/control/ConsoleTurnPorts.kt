package splice.app.control

import splice.app.provider.codex.HeadCodeModeControls
import splice.core.client.ForegroundToolActivity
import splice.head.turn.LiveTurnsByHead

/** Running turn capabilities are assigned before serving and read at request time. */
internal class ConsoleTurnPorts {
    /** Explicit conversation interruption reaches the provider assembly's live bridges. */
    internal var codeMode: HeadCodeModeControls? = null

    /** Optional local tool callbacks retain the existing unknown or stale session policy when unwired. */
    internal var foreground: ForegroundToolActivity? = null

    /** The same live registry HeadServerFactory fills; an unwired route reports a named 503. */
    internal var live: LiveTurnsByHead? = null
}
