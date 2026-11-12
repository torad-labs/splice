// NEW: V4-358. The id Claude Code HOLDS for a catalog row is not always the row's id.
//
// Claude Code 2.1.283 keeps at most 100 images in a request and strips the oldest past that, leaving
// "[media removed: request limit]" in each stripped tool_result; for a session it counts as 1M-context
// the cap is 600 (bundle: `XEo(messages, w ? 600 : 100, 20, ...)`, with `w` true for a model id matching
// /\[1m\]/i). A claudex trace showed a request with 100 input_image parts followed by one with 80 and
// the marker, on a backend whose window was 872k: the client's rule, not the backend's, was losing the
// screenshots. The id is a value splice authors (project CLAUDE.md s22), so a row whose window can hold
// far more than 100 images is handed to the client with the 1M hint, and the head scales the counts it
// reports (ModelCatalog.usageScale) so the client still compacts at the row's real window.
package splice.core.model

/** Which catalog rows the client is told are 1M-context, and the id it is handed for them. Pure: the
 *  answer is a function of the catalog's declared windows, so the launch and any test read one rule. */
public class ClientSpelling(private val catalog: ModelCatalog) {
    /** [id] as the client should hold it: with the 1M hint when the row warrants it, else [id] itself. */
    public fun of(id: String): String = if (warrants(id)) id + MILLION_HINT else id

    // Only a row the client resolves from the launch env can be moved: an id the client already knows
    // (`claude-...`, or a presented row) has its own window and media rule, and a tier hint the operator
    // wrote ("k3[1m]", "[500k]") is theirs. A row the head does not serve is not one we may rename.
    private fun warrants(id: String): Boolean =
        catalog.contains(id) &&
            !ModelTierSuffix.present(id) &&
            catalog.envGoverned(id) &&
            catalog.contextWindowFor(id) >= PRESENT_FROM_WINDOW
}

private const val MILLION_HINT = "[1m]"

// why: the smallest window whose compaction line holds twice the client's 100-image cap of screenshots,
// so the cap is what ends a screenshot session instead of the window. Under it the client compacts
// before 100 images pile up, and presenting the row would only scale its counts for nothing. Derived
// from the client and the launch: a 1440x900 screenshot is ceil(1440/28) * ceil(900/28) = 52 * 33 = 1,716
// tokens (bundle 2.1.283, 28-pixel patches); the client compacts at 85% (the launch's
// CLAUDE_AUTOCOMPACT_PCT_OVERRIDE) of the window less its 20,000-token output reserve, so
// 0.85 * (W - 20,000) >= 200 * 1,716 gives W >= 423,765, rounded up. It takes in the 500k and 872k rows and
// leaves the 256k and 272k ones as they were.
private const val PRESENT_FROM_WINDOW = 425_000L
