// NEW: desktop-full (docs/specs/desktop-full.md, df-app-window). Desktop sets the Code tab's standard window
// and a separate window for 1M variants. The `[desktop]` table supplies both values. Its app-mode marker identifies
// the client, not the selected variant: native 1M models can omit the `context-1m-` beta.
package splice.client.desktop

import splice.core.model.ClaudeCodeNativeWindow
import splice.core.model.ClientWindow
import splice.core.topology.desktop.DesktopConfig

public class DesktopAppWindow(private val desktop: DesktopConfig = DesktopConfig()) {
    /** Whether a request carrying [header] (its [APP_MODE_HEADER]) is one of Claude Desktop's in app mode. */
    public fun app(header: String?): Boolean = header?.trim().equals(APP_MODE, ignoreCase = true)

    /** The Code tab's stated divisor or an upper bound; null for requests outside app mode. */
    public fun of(
        header: String?,
        beta: String?,
        model: String,
        publishedWindow: Long = 0,
        runtimeWindow: Long? = null,
    ): ClientWindow? {
        if (!app(header)) return null
        val million = model.endsWith("[1m]", ignoreCase = true) ||
            beta.orEmpty().split(',').any { it.trim().startsWith(MILLION_WINDOW_BETA) }
        if (million) return ClientWindow.Stated(desktop.codeWindow1m)
        val native = ClaudeCodeNativeWindow.of(model)
        val described = discoverable(runtimeWindow ?: publishedWindow)
        // A beta-free request can still use the native window or the large capacity learned from discovery.
        return if (native != null || maxOf(publishedWindow, described) >= desktop.codeWindow1m) {
            val advertised = maxOf(discoverable(publishedWindow), described)
            ClientWindow.Bound(maxOf(native ?: 0, advertised, desktop.codeWindow1m))
        } else {
            ClientWindow.Stated(desktop.codeWindow)
        }
    }

    private fun discoverable(window: Long): Long = window.takeIf { it <= MAX_DISCOVERY_WINDOW } ?: 0
}

/** The request header Claude Code names its betas in. */
public const val ANTHROPIC_BETA_HEADER: String = "anthropic-beta"

// The beta Claude Code asks for the 1M variant with.
private const val MILLION_WINDOW_BETA = "context-1m-"

// Claude Code ignores larger runtime window declarations in its discovery reader.
private const val MAX_DISCOVERY_WINDOW = 10_000_000L
