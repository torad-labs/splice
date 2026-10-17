// NEW: V4-412. Where each head's provider hold lives in the state dir. Two kinds of file, never
// shared: the head's own (its single-account cooldown) and one per pool account, named by head and
// label, so one account's refusal never reads as its sibling's and no two writers rewrite one file.
package splice.app.head

import splice.app.auth.claude.ClaudeNativeAuth
import splice.app.provider.Wired
import splice.core.config.StatePaths
import splice.core.util.LogSink
import splice.upstream.retry.FileProviderHoldStore
import splice.upstream.retry.ProviderHoldStore

internal class ProviderHoldFiles(
    private val statePaths: StatePaths,
    private val log: LogSink,
) {
    fun forHead(key: String): ProviderHoldStore =
        FileProviderHoldStore(statePaths.stateDir.resolve("$key-provider-hold.json"), log)

    fun forAccounts(key: String, wired: Wired): Map<String, ProviderHoldStore> =
        wired.accounts.associate { account ->
            val native = account.auth as? ClaudeNativeAuth
            val store = if (native != null) {
                forHead(key)
            } else {
                FileProviderHoldStore(statePaths.stateDir.resolve("$key-${account.label}-provider-hold.json"), log)
            }
            account.label to (native?.credentialKey?.let(store::forCredential) ?: store)
        }
}
