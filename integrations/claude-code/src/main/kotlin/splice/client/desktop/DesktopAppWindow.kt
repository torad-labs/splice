// NEW: desktop-full (docs/specs/desktop-full.md, df-app-window). Desktop sets the Code tab's standard window
// and a separate window for 1M variants. The `[desktop]` table supplies both values. Its app-mode marker identifies
// the client, not the selected variant: native 1M models can omit the `context-1m-` beta.
package splice.client.desktop

import splice.client.models.AnthropicTiers
import splice.core.topology.desktop.DesktopConfig

public class DesktopAppWindow(private val desktop: DesktopConfig = DesktopConfig()) {
    private val tiers = AnthropicTiers()

    /** Whether a request carrying [header] (its [APP_MODE_HEADER]) is one of Claude Desktop's in app mode. */
    public fun app(header: String?): Boolean = header?.trim().equals(APP_MODE, ignoreCase = true)

    /** The Code tab's divisor when the request identifies it; null when app mode or the variant is unknown. */
    public fun of(header: String?, beta: String?, model: String, publishedWindow: Long = 0): Long? {
        if (!app(header)) return null
        val million = model.endsWith("[1m]", ignoreCase = true) ||
            beta.orEmpty().split(',').any { it.trim().startsWith(MILLION_WINDOW_BETA) }
        return when {
            million -> desktop.codeWindow1m
            // Native Claude models can omit the beta. So can a 1M selection on a row offered at the large window.
            publishedWindow >= desktop.codeWindow1m || tiers.modelOf(model) != null -> null
            else -> desktop.codeWindow
        }
    }
}

/** The request header Claude Code names its betas in. */
public const val ANTHROPIC_BETA_HEADER: String = "anthropic-beta"

// The beta Claude Code asks for the 1M variant with.
private const val MILLION_WINDOW_BETA = "context-1m-"
