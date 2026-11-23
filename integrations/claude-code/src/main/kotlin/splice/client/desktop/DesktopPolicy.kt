// NEW: desktop-policy (docs/specs/desktop-full.md, outcome 9). What splice gives Claude Desktop beyond its connection
// to the door: splice's default policy, in the flat keys of Anthropic's configuration reference as Desktop's library
// holds them (https://claude.com/docs/third-party/claude-desktop/configuration), with what the person already set up
// in Claude Code laid over it (ClaudeCodeSetup). Desktop on splice then starts out matching their Claude Code, with
// nothing to enter again in Desktop's own Setup window.
//
// A configuration is filled, never overruled: a key it sets is the person's, whether they set it in Setup or by hand,
// so it stays, and only what it leaves unset is given. Its marketplace list gains the ones it lacks, and its headers
// the one the door reads a Desktop request by.
package splice.client.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars

internal class DesktopPolicy(private val setup: ClaudeCodeSetup) {
    /** The policy: [defaults], with the person's Claude Code effort, permission mode, prompt suggestion setting,
     *  and marketplaces laid over it. */
    fun generated(defaults: JsonObject): JsonObject {
        val effort = setup.effort()?.let { mapOf(POLICY_EFFORT to JsonPrimitive(it)) }.orEmpty()
        val permMode = setup.permissionMode()?.let { mapOf(POLICY_PERMISSION_MODE to JsonPrimitive(it)) }.orEmpty()
        val suggestions = setup.promptSuggestionEnabled()
            ?.let { mapOf(POLICY_PROMPT_SUGGESTION to JsonPrimitive(it)) }
            .orEmpty()
        val added = setup.marketplaces()
        val markets = both(defaults[POLICY_MARKETPLACES], added).takeIf { added.isNotEmpty() }
            ?.let { mapOf(POLICY_MARKETPLACES to it) }
            .orEmpty()
        return JsonObject(given() + defaults + effort + permMode + suggestions + markets)
    }

    /** Projects and auto mode, on unless the person sets them otherwise (in Desktop, or in the console's Desktop
     *  policy): the lowest layer of the policy, under the default the build ships. */
    private fun given(): JsonObject = buildJsonObject {
        put("projectsEnabled", true)
        put("autoModeEnabled", true)
    }

    /** What every configuration on the door holds, whoever made it, over any policy: nobody sets these from the
     *  console (DesktopPolicyCatalog.locked). Gateway mode has no claude.ai sign-in: a person who wants their Claude
     *  account and splice runs a second Desktop beside their own (DesktopAppConfig.beside), so Desktop opens straight
     *  on splice and offers no sign-in. Model discovery is on, and auto mode runs without the server's own check. */
    fun standing(): JsonObject = buildJsonObject {
        // The live test (Nov 19, 2026, Desktop 5.11492.5): unset, Desktop first shows its chooser (Continue, or sign in
        // with claude.ai) and asks the door only once a person picks; "3p" opens it straight on splice. The second key
        // is the same choice as a Desktop older than 4.16336 reads it.
        put("deploymentMode", "3p")
        put("disableDeploymentModeChooser", true)
        put(MODEL_DISCOVERY, true)
        put("inferenceGatewayAutoModeServerChecks", false)
    }

    /** [config], a configuration that points Desktop at the door, given what it leaves unset of [policy] and of
     *  [standing], the marketplaces of [policy] its own list lacks, missing sub-keys of `claudeAiImport`, and
     *  [APP_MODE_HEADER] among its own headers. A key it sets stays as the person set it; a list or headers it holds
     *  in a form that is no JSON (a managed profile's string) are left as they are. */
    fun filled(config: JsonObject, policy: JsonObject): JsonObject {
        val unset = (policy + standing()).filterKeys { it !in config }
        val wanted = (policy[POLICY_MARKETPLACES] as? JsonArray).orEmpty()
        val markets = (config[POLICY_MARKETPLACES] as? JsonArray)
            ?.let { mapOf(POLICY_MARKETPLACES to both(it, wanted)) }
            .orEmpty()
        val importFill = mergedImport(config[POLICY_IMPORT], policy[POLICY_IMPORT])
        return JsonObject(config + unset + markets + importFill + marked(config))
    }

    /** Fills missing keys inside `claudeAiImport` when [current] is already a JSON object without overwriting
     *  any key the person explicitly configured. */
    private fun mergedImport(current: JsonElement?, wanted: JsonElement?): Map<String, JsonElement> {
        val held = current as? JsonObject
        val defaults = wanted as? JsonObject
        val missing = if (held != null && defaults != null) defaults.filterKeys { it !in held } else emptyMap()
        return if (held == null || missing.isEmpty()) emptyMap() else mapOf(POLICY_IMPORT to JsonObject(held + missing))
    }

    /** [config]'s custom headers with [APP_MODE_HEADER] among them, which is how the door counts a turn against the
     *  window Desktop gave that session; empty where they already carry it or are no JSON object. */
    private fun marked(config: JsonObject): Map<String, JsonElement> {
        val headers = (config[APP_HEADERS] ?: JsonObject(emptyMap())) as? JsonObject
        if (headers == null || APP_MODE_HEADER in headers) return emptyMap()
        return mapOf(APP_HEADERS to JsonObject(headers + (APP_MODE_HEADER to JsonPrimitive(APP_MODE))))
    }

    /** [listed], then each of [more] that is fetched from a place none before it is. */
    private fun both(listed: JsonElement?, more: List<JsonElement>): JsonArray {
        val held = (listed as? JsonArray).orEmpty()
        val places = held.mapTo(mutableSetOf(), ::place)
        return JsonArray(held + more.filter { places.add(place(it)) })
    }

    /** Where a marketplace is fetched from, which is what makes two entries the same one: GitHub reads a repository's
     *  name without regard to case. */
    private fun place(entry: JsonElement): String {
        val named = entry as? JsonObject
        val kind = JsonScalars.str(named, MARKETPLACE_SOURCE).orEmpty()
        val at = marketplaceAddressOf[kind]?.let { JsonScalars.str(named, it) }.orEmpty()
        return "$kind $at".lowercase()
    }
}

// Desktop's keys for effort, marketplaces, permission mode, prompt suggestions, and session import.
private const val POLICY_EFFORT = "defaultModelEffort"
private const val POLICY_MARKETPLACES = "allowedPluginMarketplaces"
private const val POLICY_PERMISSION_MODE = "codeDefaultPermissionMode"
private const val POLICY_PROMPT_SUGGESTION = "promptSuggestionEnabled"

/** Desktop's key for its claude.ai import settings, an object a configuration may hold part of. */
public const val POLICY_IMPORT: String = "claudeAiImport"
