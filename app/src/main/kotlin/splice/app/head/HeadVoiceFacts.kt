// NEW: failure-handling slice 2 (2026-10-26). What a head's lines name besides the head itself, read once per head
// from the provider it talks to, so the lines a person reads say whom to pay or sign in with.
package splice.app.head

import splice.core.failure.VoiceFacts
import splice.core.topology.ApiKeyProviderRegistry
import splice.core.topology.ProviderConfig
import splice.core.topology.ProviderFamilyRule

/** What a head's lines name besides the head itself: the provider a person acts with (DeepSeek, xAI, Anthropic), by
 *  the family the console colours the head by, and whether its model server runs on this machine. A family no
 *  company answers to (one the operator wrote, or none) names nobody, so a line never guesses whom to pay. */
internal class HeadVoiceFacts {
    private val families = ProviderFamilyRule()

    // The two sign-in families no api-key row names; every other family a sign-in reads as has a row.
    private val signInNames = mapOf("anthropic" to "Anthropic", "meta" to "Meta")

    fun of(providerKey: String, provider: ProviderConfig): VoiceFacts {
        val family = families.of(providerKey, provider)
        val named = family?.let { ApiKeyProviderRegistry.row(it)?.label ?: signInNames[it] }
        return VoiceFacts(providerName = named, local = family == "local")
    }
}
