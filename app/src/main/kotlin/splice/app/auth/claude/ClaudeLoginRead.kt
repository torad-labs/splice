// NEW: each place joins only its currently live credential to successful windows and provider refusals.
package splice.app.auth.claude

import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginStanding
import splice.app.head.ProviderHoldFiles
import splice.core.config.StatePaths
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.head.usage.CredentialQuotaFiles

internal class ClaudeLoginRead(
    private val paths: StatePaths,
    private val log: LogSink,
    private val clock: WallClock,
) {
    private val facts = ClaudeLoginFactsReader()
    private val holds = ProviderHoldFiles(paths, log)

    fun read(location: ClaudeLoginLocation): ClaudeLoginPlaceView {
        val native = facts.read(location)
        val head = location.target.head.key
        val quota = native.key?.let { CredentialQuotaFiles(paths.quotaFile(head), log).read(it) }
        val hold = native.key?.let { holds.forHead(head).forCredential(it)?.load() }
        val until = listOfNotNull(hold?.providerResetAtEpochSeconds, hold?.plan?.resetEpochSeconds).maxOrNull()
        return ClaudeLoginPlaceView(
            id = location.id,
            head = head,
            credentialPath = location.credentials.toString(),
            credentialPresent = native.present,
            account = native.account,
            quota = quota,
            standing = ClaudeLoginStanding(
                held = until?.let { it > clock() / 1000L },
                untilEpochSeconds = until,
            ),
            refusal = native.refusal,
        )
    }
}
