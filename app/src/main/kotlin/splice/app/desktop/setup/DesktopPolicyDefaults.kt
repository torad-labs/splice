// NEW: desktop-policy (docs/specs/desktop-full.md, outcome 9). The default policy splice gives Claude Desktop, as the
// build ships it: `claude-code-desktop-policy/ccd-policy.json`, the flat keys of Anthropic's configuration reference
// (https://claude.com/docs/third-party/claude-desktop/configuration) with everything useful on. It holds no address,
// credential or model list: the door's address and the helper are the daemon's to give (DesktopAppConfig.config), and
// the models are written as they stand (DesktopAppConfig.withModels). What a person set up in Claude Code is laid over
// it where the configuration is made (DesktopAppConfig.policy).
package splice.app.desktop.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

internal class DesktopPolicyDefaults {
    /** The policy the build ships; empty when the build ships none it can read, which leaves Desktop with its
     *  connection to the door alone. */
    fun read(): JsonObject {
        val text = javaClass.classLoader.getResourceAsStream(POLICY_DEFAULTS_RESOURCE)?.use { it.readBytes() }
        return text?.let { JsonScalars.objectOrNull(Json, it.decodeToString()) } ?: JsonObject(emptyMap())
    }
}

// The default policy's place among the build's resources.
private const val POLICY_DEFAULTS_RESOURCE = "claude-code-desktop-policy/ccd-policy.json"
