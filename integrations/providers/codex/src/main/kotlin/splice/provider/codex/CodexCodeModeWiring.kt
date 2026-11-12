package splice.provider.codex

import splice.dialect.responses.request.ResponsesToolResultMedia

/**
 * The models the backend runs on the code-mode surface alone, asked at TURN time and never captured (V4-441):
 * the daemon's roster is refreshed while it runs, and a set held from build would hide the refresh. Ids are
 * whatever the backend published (`tool_mode = "code_mode_only"`); [CodexCodeModeModels.eligible] normalizes.
 */
public fun interface CodeModeOnlyModels {
    public fun ids(): Collection<String>
}

/** The backend models that explicitly permit original image detail, refreshed at each turn. */
public fun interface CodeModeOriginalImageModels {
    public fun ids(): Collection<String>
}

internal object NoCodeModeOriginalImageModels : CodeModeOriginalImageModels {
    override fun ids(): Collection<String> = emptyList()
}

/** No backend list known: only the operator's `code_mode_models` runs code mode. */
internal object NoCodeModeOnlyModels : CodeModeOnlyModels {
    override fun ids(): Collection<String> = emptyList()
}

/**
 * The code-mode pieces a Codex head is wired with. [bridge] is the cell runtime (null = code mode off),
 * [models] the upstream model ids the operator adds to those the backend marks code-mode-only (null adds
 * none), and [onlyModels] the models the backend marks `code_mode_only`, asked each turn (V4-441).
 */
public class CodexCodeModeWiring(
    private val bridge: CodexCodeModeBridge? = null,
    private val models: Collection<String>? = null,
    private val onlyModels: CodeModeOnlyModels = NoCodeModeOnlyModels,
    private val originalImageModels: CodeModeOriginalImageModels = NoCodeModeOriginalImageModels,
    private val settings: CodexCodeModeSettings = CodexCodeModeSettings(),
) {
    internal fun turnBuilder(media: ResponsesToolResultMedia): CodexCodeModeTurnBuilder =
        CodexCodeModeTurnBuilder(bridge, media, models, onlyModels, originalImageModels, settings)

    internal fun onHeadStop() {
        bridge?.onHeadStop()
    }
}
