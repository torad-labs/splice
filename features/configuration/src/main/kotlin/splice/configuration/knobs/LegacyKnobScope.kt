// NEW: which command the eight legacy codex and grok knobs reach (§9 audit, Oct 12, splice-lead).
//
// port, pinnedModel, chatgptApiBase and codexAuthPath were the management API's names for THE codex head, and grokPort,
// grokModel, xaiApiBase and grokAuthPath for THE grok head. They govern a head only while it is the sole head of its
// sign-in kind (DR-80: TopologyKnobLayer.soleLegacyHeadKeys, which HeadBuildInputs gates on). On any other head a
// command's own value for one of them was written, answered live or restart, and never read. The one exception is the
// auth file: a head of the kind whose provider declares no auth file falls back to the knob through its own config
// (CodexResponsesArm, GrokResponsesArm), so there it is read and stays.
package splice.configuration.knobs

import splice.core.config.Knob
import splice.core.topology.AuthKind
import splice.core.topology.Topology

internal class LegacyKnobScope(private val topology: Topology) {
    /** Why [head] cannot take its own value for [knob], or null when it can. */
    fun refusal(head: String, knob: Knob): String? {
        val kind = KIND_OF[knob] ?: return null
        val sole = topology.heads.keys.singleOrNull { kindOf(it) == kind }
        val readsOwnAuthFile = knob in AUTH_FILE_KNOBS && kindOf(head) == kind && authFileOf(head) == null
        return when {
            sole == head || readsOwnAuthFile -> null
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

    private fun authFileOf(head: String): String? =
        topology.heads[head]?.provider?.let(topology.providers::get)?.auth?.file
}

// why: each legacy knob and the sign-in kind of the one head it was named for.
private val KIND_OF: Map<Knob, String> = mapOf(
    Knob.PORT to AuthKind.ChatgptOAuth.wire,
    Knob.PINNED_MODEL to AuthKind.ChatgptOAuth.wire,
    Knob.CHATGPT_API_BASE to AuthKind.ChatgptOAuth.wire,
    Knob.CODEX_AUTH_PATH to AuthKind.ChatgptOAuth.wire,
    Knob.GROK_PORT to AuthKind.GrokOAuth.wire,
    Knob.GROK_MODEL to AuthKind.GrokOAuth.wire,
    Knob.XAI_API_BASE to AuthKind.GrokOAuth.wire,
    Knob.GROK_AUTH_PATH to AuthKind.GrokOAuth.wire,
)

// why: the two a head of the kind with no declared auth file reads through its own config.
private val AUTH_FILE_KNOBS = setOf(Knob.CODEX_AUTH_PATH, Knob.GROK_AUTH_PATH)
