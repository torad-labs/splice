// NEW: desktop-door (docs/specs/desktop-door.md, decision 2). Anthropic's own model IDs, as Claude Code sends them, read
// as the tier they name. Claude Code sends one for its start-up check, for the picker's `Default` row and for a subagent
// or skill that names a tier, whatever the picker holds; under a provider switch it spells it for that provider. Read
// on Nov 16, 2026 from Claude Code 2.1.325 in Mantle mode: `anthropic.claude-opus-5-5`, and in its other spellings a
// region prefix (`us.`, `global.`), a version suffix (`-v1:0`), the bare ID (`claude-opus-5-5`) and the tier's alias
// (`opus`). A fact of Claude Code's dialect, so it lives here with the others. The terminal's door also reads the one
// model an ID names (modelOf), to run it on the head that serves it (DoorRouting).
package splice.client.models

/** Which tier an Anthropic model ID names, in every spelling Claude Code sends it. */
public class AnthropicTiers {
    /** The tier (`opus`, `sonnet`, `haiku` or `fable`) [model] names, or null when it is no Anthropic ID: a
     *  provider's own model, or a selector of a head. */
    public fun tierOf(model: String): String? {
        val id = model.trim().lowercase()
        if (id in TIERS) return id
        return CLAUDE_ID.matchEntire(bare(id))?.groupValues?.get(1)
    }

    /** The one model [model] names, spelled bare and without a release date (`claude-haiku-4-5` for
     *  `global.anthropic.claude-haiku-4-5-20251001-v1:0`), so two spellings of it compare equal; null for a tier's
     *  alias (`opus`), which names no one model, and for anything that is no Anthropic ID. */
    public fun modelOf(model: String): String? {
        val id = bare(model.trim().lowercase())
        return id.takeIf(CLAUDE_ID::matches)?.replace(RELEASE_DATE, "")
    }

    private fun bare(id: String): String = id.replace(MANTLE_PREFIX, "").replace(VERSION_SUFFIX, "")
}

// The tiers Claude Code names models by, which a head's `model_slots` maps to its own models.
private val TIERS = setOf("opus", "sonnet", "haiku", "fable")

// A region prefix (`us.`, `eu.`, `global.`) and the Mantle namespace, both optional, at the start.
private val MANTLE_PREFIX = Regex("^(?:[a-z]+\\.)?anthropic\\.")

// A Bedrock version suffix at the end: `-v1:0`, `-v2`.
private val VERSION_SUFFIX = Regex("-v\\d+(?::\\d+)?$")

// `claude-opus-5-5`, `claude-haiku-4-5-20251001`, `claude-3-5-sonnet-20241022`: a family word among version numbers.
private val CLAUDE_ID = Regex("^claude-(?:\\d+-)*(opus|sonnet|haiku|fable)(?:-\\d+)*$")

// A release date at the end of an ID (`-20251001`), which the undated ID names too.
private val RELEASE_DATE = Regex("-\\d{8}$")
