// NEW: desktop-door (docs/specs/desktop-door.md, decision 6). The desktop key: the one key Claude Desktop's own
// environment holds, which every command in a Code tab session can read, so it opens the door and nothing else. It is
// a proof, never a credential: the door checks it and sends the head its own turn key in its place. Kept in splice's
// state folder, owner-only, minted at first use, and replaced only by `splice desktop rotate-key`; `splice rotate-key`
// replaces the management key and never this one. The file-held key, and its SH-12 rule (a file that is there but
// can't be used is said in the log before a new key is made), is [FileHeldKey], shared with the management key.
package splice.app.desktop

import splice.client.desktop.DESKTOP_ENV_PLACE
import splice.client.desktop.DESKTOP_KEY_PREFIX
import splice.core.config.FileHeldKey
import splice.core.util.LogSink
import java.nio.file.Path

internal class DesktopKey(file: Path, log: LogSink) {
    private val held = FileHeldKey(
        file = file,
        prefix = DESKTOP_KEY_PREFIX,
        tag = "desktop",
        consequence = "the desktop key set in $DESKTOP_ENV_PLACE is refused at the door until the new one is set " +
            "there; `splice desktop rotate-key` (or `splice desktop on`) says it",
        log = log,
    )

    /** The key the door opens to, minted into its file the first time there is none. */
    fun get(): String = held.get()

    /** A new key in place of the old one, which is refused from now on. */
    fun replace(): String = held.replace()

    /** Whether [header] presents this key as a bearer, compared in constant time. */
    fun matchesBearer(header: String?): Boolean = held.matchesBearer(header)
}
