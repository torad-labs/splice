// NEW: desktop-harnesses. What [DesktopHarnessRoutes] reads and changes: a PATCH body checked family by family against
// the families that have a choice (HarnessFamily), and the harness map as Settings and Accounts draw it: each family
// with its words, the harnesses it can run on, splice's default, its harness now and who chose it, the providers and
// prefixes it covers, and whether splice serves it; then every prefix's switch, as the file holds them.
package splice.app.control.api.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.desktop.harness.FamilyState
import splice.app.desktop.harness.HARNESS_KEY_ENABLED
import splice.app.desktop.harness.HARNESS_KEY_FAMILIES
import splice.app.desktop.harness.HARNESS_KEY_HARNESS
import splice.app.desktop.harness.HarnessChoice
import splice.app.desktop.harness.HarnessFamily
import splice.app.desktop.harness.HarnessFileRead
import splice.app.desktop.harness.HarnessSwitch
import splice.app.desktop.harness.HarnessTarget
import splice.app.desktop.harness.HarnessWords
import splice.core.harness.HarnessDefinition
import splice.core.util.JsonScalars

/** What a PATCH asks of one family: a harness, a switch, or both; null leaves that part as it is. */
internal data class HarnessEdit(val harness: HarnessDefinition?, val enabled: Boolean?)

/** A PATCH body read: the families to change and the ones to put back to the default, or why it is refused. */
internal sealed class HarnessPatch {
    data class Changes(val set: Map<HarnessFamily, HarnessEdit>, val reset: Set<HarnessFamily>) : HarnessPatch()

    /** Each refused family with why; [why] says the first in one line. */
    data class Refused(val why: String, val families: Map<String, String>) : HarnessPatch()
}

internal class DesktopHarnessRouteOps {
    private val words = HarnessWords()

    /** [body], each family an edit or null to put it back to the default. */
    fun parse(body: JsonObject): HarnessPatch {
        if (body.isEmpty()) return HarnessPatch.Refused("name at least one harness family", emptyMap())
        val refused = body.mapNotNull { (key, value) -> refusal(key, value)?.let { key to it } }.toMap()
        if (refused.isNotEmpty()) {
            val (key, why) = refused.entries.first()
            return HarnessPatch.Refused("$key: $why", refused)
        }
        val set = mutableMapOf<HarnessFamily, HarnessEdit>()
        val reset = mutableSetOf<HarnessFamily>()
        body.forEach { (key, value) ->
            val family = words.family(key) ?: return@forEach
            if (value is JsonObject) set[family] = edit(family, value) else reset += family
        }
        return HarnessPatch.Changes(set, reset)
    }

    /** The console's choices once [patch] is made of [held], each edit laid over the family's harness in [states]. */
    fun changed(
        held: Map<HarnessFamily, HarnessChoice>,
        states: List<FamilyState>,
        patch: HarnessPatch.Changes,
    ): Map<HarnessFamily, HarnessChoice> {
        val now = states.associate { it.family to it.choice }
        val edited = patch.set.mapValues { (family, edit) ->
            val base = held[family] ?: now.getValue(family)
            HarnessChoice(edit.harness ?: base.harness, edit.enabled ?: base.enabled)
        }
        return held + edited - patch.reset
    }

    /** The map as the console draws it, from [target] and the file as [read] holds it. */
    fun snapshot(target: HarnessTarget, read: HarnessFileRead, restartRequired: Boolean): JsonObject {
        val harnesses = target.harnesses
        val states = harnesses.states(harnesses.choicesOf(read))
        val switches = harnesses.switches(states)
        val document = harnesses.document(states)
        val families = states.map { state -> familyJson(state, switches.filter { it.family == state.family }, target) }
        return JsonObject(
            document + mapOf(
                HARNESS_KEY_FAMILIES to JsonArray(families),
                "file" to JsonPrimitive(harnesses.file.file.fileName.toString()),
                "overrides_error" to ((read as? HarnessFileRead.Unreadable)?.why?.let(::JsonPrimitive) ?: JsonNull),
                "ignored" to JsonArray((read as? HarnessFileRead.Held)?.ignored.orEmpty().map(::JsonPrimitive)),
                "restart_required" to JsonPrimitive(restartRequired),
            ),
        )
    }

    private fun familyJson(state: FamilyState, switches: List<HarnessSwitch>, target: HarnessTarget): JsonObject {
        val family = state.family
        val providers = switches.map { it.provider }.distinct()
        val configured = when (family) {
            HarnessFamily.ANTIGRAVITY -> target.antigravityServed()
            HarnessFamily.OPENAI -> providers.isNotEmpty()
        }
        return buildJsonObject {
            put("family", family.wire)
            put("title", family.title)
            put("description", family.description)
            put(HARNESS_KEY_HARNESS, state.choice.harness.wire)
            put(HARNESS_KEY_ENABLED, state.choice.enabled)
            put("set_by", state.setBy.wire)
            put("toggleable", family.toggleable)
            put("choices", JsonArray(family.choices.map { JsonPrimitive(it.wire) }))
            put(
                "default",
                buildJsonObject {
                    put(HARNESS_KEY_HARNESS, state.default.harness.wire)
                    put(HARNESS_KEY_ENABLED, state.default.enabled)
                },
            )
            put("providers", JsonArray(providers.map(::JsonPrimitive)))
            put("prefixes", JsonArray(switches.map { JsonPrimitive(it.prefix) }))
            put("configured", configured?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private fun refusal(key: String, value: JsonElement): String? {
        val family = words.family(key)
            ?: return "not a Desktop harness family; one of ${HarnessFamily.entries.joinToString { it.wire }}"
        return when {
            value is JsonNull -> null
            value !is JsonObject -> "expected {\"harness\": id, \"enabled\": true|false} or null"
            value.isEmpty() -> "name a harness or a switch"
            else -> value.entries.firstNotNullOfOrNull { (field, v) -> fieldRefusal(family, field, v) }
        }
    }

    private fun fieldRefusal(family: HarnessFamily, field: String, value: JsonElement): String? = when (field) {
        HARNESS_KEY_HARNESS -> harnessRefusal(family, value)
        HARNESS_KEY_ENABLED -> when {
            switchOf(value) == null -> "enabled must be true or false"
            !family.toggleable -> "${family.wire} sessions always run; only their harness changes"
            else -> null
        }
        else -> "`$field` is not a harness setting; set harness or enabled"
    }

    /** Why [value] is no harness [family] runs on, or null when it is one. */
    private fun harnessRefusal(family: HarnessFamily, value: JsonElement): String? {
        val named = JsonScalars.str(value).takeIf { (value as? JsonPrimitive)?.isString == true }
            ?: return "harness must be a harness id"
        if (family.choices.any { it.wire == named }) return null
        return "`$named` is not a harness ${family.wire} runs on; one of ${family.choices.joinToString { it.wire }}"
    }

    private fun edit(family: HarnessFamily, value: JsonObject): HarnessEdit {
        val id = JsonScalars.str(value, HARNESS_KEY_HARNESS)
        return HarnessEdit(family.choices.firstOrNull { it.wire == id }, switchOf(value[HARNESS_KEY_ENABLED]))
    }

    private fun switchOf(element: JsonElement?): Boolean? =
        (element as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}
