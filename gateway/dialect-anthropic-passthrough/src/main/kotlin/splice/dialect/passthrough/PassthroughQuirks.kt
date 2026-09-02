// NEW: the vendor-deformation configuration surface — split out of PassthroughRequestBuilder.kt
// (2026-08-17, concentration campaign). PassthroughArm/PassthroughAssembly select it, while
// PassthroughProvider and PassthroughStreamTranslator consume it. It is declarative config with zero
// JSON-walking logic and zero splice imports. Every relocated member kept its name and argument list.
package splice.dialect.passthrough

/**
 * Computed runtime identity headers a vendor requires on upstream calls — today Kimi OAuth/API-key's
 * `X-Msh-*` host, platform, version, and OS set.
 *
 * A function and not config, which is the distinction this type exists to hold: static headers are
 * data, while these values derive from runtime state. Absent (`{ emptyMap() }`) for generic and
 * CLIENT arms, so a head that needs no computed identity wires nothing.
 */
public fun interface IdentityHeaders {
    public operator fun invoke(): Map<String, String>
}

/**
 * The knobs that turn a FAITHFUL Anthropic passthrough into one vendor's accepted shape.
 *
 * CONSTRUCTOR DEFAULTS ARE NEUTRAL, and that inversion is the point (campaign claude-head, CH-2).
 * Every knob below was hardcoded ON when Kimi was the dialect's only consumer, which made
 * "passthrough" a misnomer: a real Anthropic upstream loses prompt caching to [stripCacheControl],
 * has its tool schemas rewritten by [mfjsSanitize], has `redacted_thinking` silently dropped by
 * [blockAllowlist], and can be handed a forged thinking signature by [synthesizeSignatures] that a
 * signature-VERIFYING upstream later rejects. Assembly selects [PassthroughQuirksDefaults.kimi]
 * only for provider ID `kimi` on OAuth/API-key arms, then applies TOML overrides. Generic and CLIENT
 * arms receive no Kimi/vendor deformations unless TOML opts into them.
 */
public data class PassthroughQuirks(
    val providerTag: String,
    /** Kimi's Anthropic surface accepts ONLY adaptive-style thinking for effort control;
     *  budget-based inference fails for Kimi model ids. Neutral forwards `thinking` verbatim
     *  (and stops owning `output_config`, so a client's own rides through). */
    val mapThinkingToAdaptive: Boolean = false,
    /** Compact-turn effort pin. null (the default) = compact INHERITS the session's own effort
     *  (v27 doctrine: compact turns inherit the session's model AND effort — a mismatch on either
     *  invalidates the prompt cache and re-reads the whole transcript cold). Set ONLY to
     *  deliberately pin a provider whose compact cost dominates. */
    val compactEffort: String? = null,
    /** Drop temperature/top_p/top_k when a live probe shows the endpoint rejects them. */
    val stripSamplingParams: Boolean = false,
    /** Rewrite tool `input_schema` into Moonshot-Flavored JSON Schema (and drop `strict` / invent
     *  an empty `description`). An upstream that accepts full JSON Schema must leave this OFF:
     *  the sanitizer discards `format`, `prefixItems`, `$ref` siblings and tuple `items`, which
     *  CHANGES tool semantics. */
    val mfjsSanitize: Boolean = false,
    /** Content-block types the upstream accepts; every other block is DROPPED. null (neutral) =
     *  every block rides. Kimi's list comes from its own 400 and excludes `redacted_thinking`,
     *  `document` and `search_result` — silent content loss against an upstream that accepts them. */
    val blockAllowlist: Set<String>? = null,
    /** Deep-strip every `cache_control` marker. Neutral PRESERVES them: against an upstream with
     *  prompt caching, stripping is a silent cold-read on every turn, not an error. */
    val stripCacheControl: Boolean = false,
    /** Synthesize ONE thinking-block signature at close when the upstream sent none. Required for
     *  Kimi (never signs; Claude Code discards unsigned thinking blocks) and WRONG for an upstream
     *  that signs and verifies — a truncated block would otherwise persist a forged signature into
     *  the transcript and return it upstream on the next turn. */
    val synthesizeSignatures: Boolean = false,
    /** DR-119: drop the RESPONSE-side server-tool surface — server_tool_use /
     *  web_search_tool_result blocks and citations_delta on text — instead of forwarding it
     *  verbatim. Neutral forwards: Claude Code renders server search results and keeps citations
     *  only if these reach the transcript. Kimi keeps its historical swallow (byte-identity law —
     *  flipping kimi's translator output is an operator decision, DR-123-class). */
    val dropServerToolBlocks: Boolean = false,
) {
    init {
        // DR-121: compact_effort vocabulary wall. The TOML field is shared with the codex knob,
        // whose vocabulary includes "medium" — a rung this dialect's ladder never emits (the
        // SCH-006 confusion class). effortLadder returns the pin RAW, so an unvalidated value
        // reaches kimi's wire as output_config.effort on every thinking-carrying compact turn and
        // 400s every compaction until the TOML is fixed. Failing in init covers every
        // construction path — the QuirksOverlay copy() at daemon assembly included — and the
        // message names the fix. Case-exact on purpose: the ladder emits lowercase only.
        require(compactEffort == null || compactEffort in kimiEfforts) {
            "[$providerTag] compact_effort '$compactEffort' is not a kimi rung (low|high|max) — " +
                "the sibling codex knob's vocabulary (e.g. 'medium') does not apply here; fix " +
                "[providers.*.quirks] compact_effort or remove it to let compact inherit the session effort"
        }
    }
}

/**
 * The vendor deformation sets this dialect ships. A type rather than a companion factory on
 * [PassthroughQuirks] (Kotlin main sources carry no `companion` blocks); the member keeps the old
 * factory's exact name and argument, so a call site only gains a receiver.
 */
public class PassthroughQuirksDefaults {

    /** KIMI's deformation set — the shape that was hardcoded before the inversion. ONE
     *  definition, so provider wiring and the byte-identity goldens cannot drift apart. */
    public fun kimi(providerTag: String): PassthroughQuirks = PassthroughQuirks(
        providerTag = providerTag,
        mapThinkingToAdaptive = true,
        mfjsSanitize = true,
        blockAllowlist = KIMI_BLOCK_TYPES,
        stripCacheControl = true,
        synthesizeSignatures = true,
        dropServerToolBlocks = true,
    )
}

/** Kimi's own 400 enumerates the accepted content tags; everything else is dropped. FILE SCOPE ON
 *  PURPOSE: one shared immutable table, and [PassthroughQuirksDefaults.kimi] is its only reader. */
private val KIMI_BLOCK_TYPES: Set<String> = setOf(
    "text",
    "image",
    TYPE_THINKING,
    "tool_use",
    TYPE_TOOL_RESULT,
    "server_tool_use",
    "web_search_tool_result",
)
