// NEW (Marcos, 2026-10-28): the standing prompt splice ships for a head that sets none of its own. "Ship Splice by
// default with a pre defined configured append system prompt, that the user can change if they'd like": a head's own
// system_prompt or system_prompt_file replaces it, and an empty system_prompt turns it off.
package splice.app.head

import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.dialect.responses.ResponsesStandingPrompt

/** The instructions splice ships beside Claude Code's own prompt for a head on a provider, or null when it ships none.
 *  The OpenAI Responses adapter ships one; the other dialects ship none, so their heads' bytes stay as they were. */
internal class ShippedStandingPrompts(private val responses: ResponsesStandingPrompt = ResponsesStandingPrompt()) {
    fun forProvider(provider: ProviderConfig): String? =
        if (provider.dialect == Dialect.OPENAI_RESPONSES) responses.text() else null
}
