// NEW: native places keep credential-stamped selector, auth and quota owners without changing display or edit identities.
package splice.app.provider

import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginPlacesSource
import splice.app.auth.claude.ClaudeCredentialProfiles
import splice.app.auth.claude.ClaudeNativeAuth
import splice.app.auth.claude.ClaudeNativeQuota
import splice.core.config.StatePaths
import splice.core.usage.QuotaSnapshot
import splice.core.util.LogSink
import splice.head.usage.CredentialQuotaFiles
import splice.upstream.credentials.AccountQuotaSource
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal class ClaudeNativeAccountWiring(
    private val paths: StatePaths,
    private val places: ClaudeLoginPlacesSource,
    private val log: LogSink,
) {
    private data class Entry(val key: String?, val auth: ClaudeNativeAuth, val account: WiredAccount)
    private val held = ConcurrentHashMap<String, Entry>()

    fun accounts(head: String): List<WiredAccount> {
        val views = places()?.places().orEmpty().filter { it.head == head }
        if (views.none { it.credential.present }) return emptyList()
        val primary = views.firstOrNull { it.id == ClaudeLoginPlaceId.SPLICE }?.id ?: views.first().id
        return views.map { view ->
            val entry = requireNotNull(
                held.compute("$head:${view.id.wire}") { _, previous ->
                    previous?.takeIf { it.key == it.auth.credentialKey } ?: account(view)
                },
            )
            entry.account.copy(
                primary = view.id == primary,
                credential = WiredAccountCredential(
                    present = view.credential.present,
                    refusal = view.credential.refusal,
                ),
            )
        }
    }

    private fun account(view: ClaudeLoginPlaceView): Entry {
        val auth = ClaudeNativeAuth(
            Path.of(view.credential.path).parent,
            view.id,
            ClaudeCredentialProfiles(paths.stateDir, log),
            log,
        )
        val wired = WiredAccount(
            label = "native:${view.id.wire}",
            primary = view.id == ClaudeLoginPlaceId.SPLICE,
            auth = auth,
            quota = WiredAccountQuota(
                file = paths.stateDir.resolve("${view.head}-native%${view.id.wire}-quota.json"),
                read = ClaudeNativeQuota(
                    auth,
                    CredentialQuotaFiles(paths.quotaFile(view.head), log),
                    object : AccountQuotaSource {
                        private fun current(): ClaudeLoginPlaceView? =
                            places()?.places()?.singleOrNull { it.head == view.head && it.id == view.id }
                        override fun snapshot(): QuotaSnapshot? = current()?.quota
                        override val held: Boolean get() = current()?.standing?.held == true
                    },
                ),
            ),
            credential = WiredAccountCredential(present = view.credential.present, refusal = view.credential.refusal),
            nativePlace = view.id,
        )
        return Entry(auth.credentialKey, auth, wired)
    }
}
