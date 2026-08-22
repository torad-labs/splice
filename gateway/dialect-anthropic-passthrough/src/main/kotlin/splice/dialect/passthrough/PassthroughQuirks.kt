// NEW: the vendor-deformation configuration surface — split out of PassthroughRequestBuilder.kt
// (2026-08-17, concentration campaign). This is the only part of the dialect the outside world
// touches (Daemon.kt constructs it; PassthroughProvider and PassthroughStreamTranslator take it as
// a constructor param), and it is declarative config with zero JSON-walking logic and zero splice
// imports. Every relocated member kept its identical name and argument list.
package splice.dialect.passthrough

/**
 * The COMPUTED per-install device identity a vendor requires on every upstream call — today only
 * Kimi's persisted `X-Msh-*` set.
 *
 * A function and not config, which is the distinction this type exists to hold: `staticHeaders`
 * beside it is operator-DECLARED TOML (`anthropic-version`, a gated UA) and is why a new
 * anthropic-compatible vendor is TOML-only, while these cannot be declared — they are derived from
 * per-install state the daemon persists and re-reads. Absent (`{ emptyMap() }`) for every head but
 * kimi, and that empty default is the reason a head that needs no identity wires nothing.
 *
 * Re-read per call rather than captured, so a device identity rotated on disk is picked up without
 * rebuilding the provider.
 */
public fun interface IdentityHeaders {
    public operator fun invoke(): Map<String, String>
}

/**
 * The knobs that turn a FAITHFUL Anthropic passthrough into one vendor's accepted shape.
 *
 * DEFAULTS ARE NEUTRAL, and that inversion is the point (campaign claude-head, CH-2). Every knob
 * below was hardcoded ON when Kimi was the dialect's only consumer, which made "passthrough" a
 * misnomer: a real Anthropic upstream loses prompt caching to [stripCacheControl], has its tool
 * schemas rewritten by [mfjsSanitize], has `redacted_thinking` silently dropped by
 * [blockAllowlist], and can be handed a forged thinking signature by [synthesizeSignatures] that
 * a signature-VERIFYING upstream later rejects. A vendor now opts INTO its own deformations
 * ([PassthroughQuirksDefaults.kimi] is the one definition of Kimi's set); a head that declares
 * nothing gets its bytes forwarded as sent.
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
)

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
