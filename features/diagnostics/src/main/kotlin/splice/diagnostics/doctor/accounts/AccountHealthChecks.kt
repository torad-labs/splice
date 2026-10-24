// NEW: the doctor checks read from Accounts' roster projection (AccountRosterProjection): a native login that
// cannot take over, and each head's and account's newest retained provider refusal. Moved out of
// AccountRosterProjection.kt, so the projection file keeps the reading and this one the verdicts.
package splice.diagnostics.doctor.accounts

import splice.core.head.ProviderAnswer
import splice.core.wire.HttpStatus
import splice.diagnostics.doctor.AccountPoolText
import splice.diagnostics.doctor.AccountPoolsRead
import splice.diagnostics.doctor.CheckStatus
import splice.diagnostics.doctor.DoctorCheck
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Diagnoses only the roster's allowlisted facts. No native credential read or refresh is performed. */
internal object AccountHealthChecks {
    private val date = DateTimeFormatter.ofPattern("MMM d, h:mm a 'CT'", Locale.US)
        .withZone(ZoneId.of("America/Chicago"))

    fun checks(
        read: AccountPoolsRead.Read,
        authRefusals: Map<String, ProviderAnswer> = emptyMap(),
    ): List<DoctorCheck> =
        read.nativeLogins.filter { it.expired || !it.present }.map { login ->
            val current = read.pools[login.head]?.let {
                AccountPoolText().summary(it, read.accountNames[login.head].orEmpty())
            }
            val standing = if (login.expired) {
                "has an expired access token and cannot take over"
            } else {
                "has no access token"
            }
            DoctorCheck(
                "native login ${login.place.command}",
                CheckStatus.WARN,
                listOfNotNull(
                    current?.let { "${login.head} is $it" },
                    "${login.place.command}'s login $standing",
                ).joinToString(". "),
                "Sign in again: run ${login.place.command} and use /login.",
            )
        } + read.lastRefusals.mapNotNull { (head, answer) ->
            refusal(head, answer).takeUnless { authRefusals[head] == answer }
        } + read.accountRefusals.flatMap { (head, accounts) ->
            accounts.map { (label, answer) -> refusal("$head account $label", answer) }
        }

    fun refusal(head: String, answer: ProviderAnswer): DoctorCheck = DoctorCheck(
        head,
        CheckStatus.WARN,
        "$head's newest retained provider refusal was HTTP ${answer.status} " +
            "at ${date.format(Instant.ofEpochMilli(answer.observedAtEpochMs))}",
        if (answer.status == HttpStatus.FORBIDDEN) {
            "Check the provider's permissions and subscription for $head."
        } else {
            null
        },
    )
}
