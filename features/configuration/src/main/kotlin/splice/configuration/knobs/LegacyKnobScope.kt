// NEW: which command the six legacy codex and grok knobs reach (§9 audit, Oct 12, splice-lead).
//
// port, pinnedModel and chatgptApiBase were the management API's names for THE codex head, and grokPort, grokModel and
// xaiApiBase for THE grok head. They govern a head only while it is the sole head of its
// sign-in kind (DR-80: TopologyKnobLayer.soleLegacyHeadKeys, which HeadBuildInputs gates on). On any other head a
// command's own value for one of them was written, answered live or restart, and never read. The two auth-file knobs,
// codexAuthPath and grokAuthPath, retired on Nov 9, 2026 (RetiredKnob): a value for either is a boot finding.
package splice.configuration.knobs

import splice.core.config.Knob
import splice.core.topology.AuthKind
import splice.core.topology.Topology

internal class LegacyKnobScope(private val topology: Topology) {
    /** Why [head] cannot take its own value for [knob], or null when it can. */
    fun refusal(head: String, knob: Knob): String? {
        val kind = KIND_OF[knob] ?: return null
        val sole = topology.heads.keys.singleOrNull { kindOf(it) == kind }
        return when {
            sole == head -> null
            sole != null ->
                "only $sole reads this setting, as the one command that signs in this way; " +
                    "set this command's own value in its [heads.$head] table"
            else ->
                "no command reads this setting while two or more commands sign in this way; " +
                    "set each one's value in its own [heads.<key>] table"
        }
    }

    private fun kindOf(head: String): String? =
        topology.heads[head]?.provider?.let(topology.providers::get)?.auth?.kind
}

// why: each legacy knob and the sign-in kind of the one head it was named for.
private val KIND_OF: Map<Knob, String> = mapOf(
    Knob.PORT to AuthKind.ChatgptOAuth.wire,
    Knob.PINNED_MODEL to AuthKind.ChatgptOAuth.wire,
    Knob.CHATGPT_API_BASE to AuthKind.ChatgptOAuth.wire,
    Knob.GROK_PORT to AuthKind.GrokOAuth.wire,
    Knob.GROK_MODEL to AuthKind.GrokOAuth.wire,
    Knob.XAI_API_BASE to AuthKind.GrokOAuth.wire,
)
