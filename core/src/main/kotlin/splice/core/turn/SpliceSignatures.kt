// NEW: V4-455 — every thinking signature splice itself mints, named in one place.
package splice.core.turn

/** Signatures splice writes on thinking blocks no vendor signed. An upstream that verifies thinking
 *  signatures (Anthropic) rejects the whole request for any of them, so a request to it drops them. */
public object SpliceSignatures {
    /** A synthesizing profile (Kimi) stamps this at close on a thinking block its upstream never signed. */
    public const val SYNTHESIZED: String = "splice-synth-v1"

    /** The wait notice's ([SpliceNotice.SIGNATURE]) and the synthesized one. */
    public val MINTED: Set<String> = setOf(SpliceNotice.SIGNATURE, SYNTHESIZED)
}
