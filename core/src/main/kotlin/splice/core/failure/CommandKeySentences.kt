// NEW: custom-provider (issue #680, cp-credential). The one line a turn ends with when a provider whose key a team's
// credential command supplies has no usable key: `<provider> has no key: <why>`, with both ways to sign in, whole
// (door-default.md decision 6: a 4xx Claude Code shows once). SpliceSentences says it when the command gave no key, and
// ProviderSentences when the provider refused the key it renewed to; both come here, so the sentence is written once.
package splice.core.failure

internal object CommandKeySentences {
    /** The line for a provider on command auth with no key to send, for [why]; [command] is the terminal sign-in the
     *  head names, else `splice login <head>`. */
    fun noKey(why: String, command: String?, facts: LineFacts): Said {
        val provider = facts.provider.key ?: facts.head
        val terminal = command?.takeIf(String::isNotBlank) ?: "splice login ${facts.head}"
        return Said(
            "$provider has no key: ${why.trimEnd('.')}",
            next = Next.Then("Type /login to sign in on splice's accounts page, or run $terminal in a terminal."),
            whole = true,
        )
    }

    /** The line for [cause] on a provider on command auth that is not recovering ([ongoing]): it refused the key splice
     *  renewed after its first 401. Null for any other provider. */
    fun refused(cause: FailureCause.Provider.AuthRejected, facts: LineFacts, ongoing: Boolean): Said? {
        if (!facts.provider.commandLogin || ongoing) return null
        val provider = facts.provider.key ?: facts.head
        val said = cause.said?.text?.let { ": ${it.trimEnd('.')}" }.orEmpty()
        return noKey("$provider refused a renewed key (401)$said", facts.loginCommand, facts)
    }
}
