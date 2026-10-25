// NEW: native places keep credential-stamped selector, auth and quota owners without changing display or edit identities.
package splice.app.provider

import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginPlacesSource
import splice.app.auth.claude.ClaudeCredentialProfiles
import splice.app.auth.claude.ClaudeLoginHeaders
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
    private data class Entry(
        val key: String?,
        /** The account the place's credential proved when this entry was built; null when none was proved yet. */
        val proved: String?,
        val auth: ClaudeNativeAuth,
        val account: WiredAccount,
    ) {
        /** Whether this entry still serves [view]: the credential it was built for is still the place's, or a token
         *  refresh left the place on the same account (or on one not proved yet, which a refresh is for a moment).
         *  Keeping the entry keeps the rate-limit hold the account is under, which a rebuilt entry starts without. A
         *  place proved to be ANOTHER account gets a new entry, so a hold never carries onto a different login. */
        fun serves(view: ClaudeLoginPlaceView): Boolean {
            val now = view.identity.account?.uuid
            val sameAccount = proved != null && (now == null || now == proved)
            return key == auth.credentialKey || sameAccount
        }
    }

    private val held = ConcurrentHashMap<String, Entry>()

    fun accounts(head: String): List<WiredAccount> {
        val views = places()?.places().orEmpty().filter { it.head == head }
        if (views.none { it.credential.present }) return emptyList()
        val primary = views.firstOrNull { it.id == ClaudeLoginPlaceId.SPLICE }?.id ?: views.first().id
        return views.map { view ->
            val entry = requireNotNull(
                held.compute("$head:${view.id.wire}") { _, previous ->
                    previous?.takeIf { it.serves(view) } ?: account(view)
                },
            )
            entry.account.copy(
                primary = view.id == primary,
                credential = WiredAccountCredential(
                    present = view.credential.present,
                    refusal = view.credential.refusal,
                    account = view.identity.account?.uuid,
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
            extraHeaders = ClaudeLoginHeaders,
            nativePlace = view.id,
        )
        return Entry(auth.credentialKey, view.identity.account?.uuid, auth, wired)
    }
}
