// NEW: desktop-full (docs/specs/desktop-full.md, df-app-window). The window Claude Desktop gives its Code tab's Claude
// Code in third-party mode, as Desktop 5.11492.5 sets it (read from its bundle, Nov 18, 2026):
// CLAUDE_CODE_MAX_CONTEXT_TOKENS=200000, and a million for a model picked as its 1M variant, which Claude Code asks for
// with a `context-1m-` beta (it strips the `[1m]` hint from the model it sends). Claude Code compacts at a share of that
// window, and splice writes the counts it reads, so the door scales each app-mode turn's counts to it (repo CLAUDE.md
// §22). A request is app mode's when it carries the header splice's configuration gives every request of Desktop's.
// The pair itself is splice.toml's `[desktop]` (DesktopConfig): Desktop's shipped windows unless the table states the
// ones this install's Desktop gives, so scaling follows the window Desktop actually sets here.
package splice.client.desktop

import splice.core.topology.desktop.DesktopConfig

public class DesktopAppWindow(private val desktop: DesktopConfig = DesktopConfig()) {
    /** Whether a request carrying [header] (its [APP_MODE_HEADER]) is one of Claude Desktop's in app mode. */
    public fun app(header: String?): Boolean = header?.trim().equals(APP_MODE, ignoreCase = true)

    /** The window Desktop gave the Code tab for [model], in a request carrying [header] and the `anthropic-beta`
     *  [beta]; null for a request that is not app mode's. */
    public fun of(header: String?, beta: String?, model: String): Long? {
        if (!app(header)) return null
        val million = model.endsWith(ONE_MILLION_HINT, ignoreCase = true) ||
            beta.orEmpty().split(',').any { it.trim().startsWith(MILLION_WINDOW_BETA) }
        return if (million) desktop.codeWindow1m else desktop.codeWindow
    }
}

/** The request header Claude Code names its betas in. */
public const val ANTHROPIC_BETA_HEADER: String = "anthropic-beta"

// How a model's 1M variant is named, and the beta Claude Code asks for it with.
private const val ONE_MILLION_HINT = "[1m]"
private const val MILLION_WINDOW_BETA = "context-1m-"
