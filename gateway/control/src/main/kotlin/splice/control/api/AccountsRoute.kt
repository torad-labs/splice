// NEW: V4-132 — GET /api/accounts (FEATURES.md §6, §4.5 "All accounts, one screen"). Every OAuth
// account of every provider in one payload, joined on the credential path so one login shared by
// several heads (two heads pointed at the same primary file, HeadAccountPools.kt:54) is one row,
// not one per head. Windows carry their own reported LENGTH (Grok's is 30 days, not a week);
// plan is reported only when a provider sends one; exclusion, selection, the operator's pin and
// the next target follow the REAL selector order (AccountPool.candidates). A head with one login
// (no pool at all) still appears, read from its /api/auth view, with `single_login: true`.
package splice.control.api

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.control.HeadAccountPoolView
import splice.control.HeadAccountView
import splice.control.ManagedHead
import splice.core.auth.AuthDescription
import splice.core.topology.AuthKindRegistry

internal class AccountsRoute(private val heads: Map<String, ManagedHead>) {
    suspend fun accountsJson(): String {
        val joined = LinkedHashMap<String, JoinedAccount>()
        heads.values.forEach { managed -> fold(managed, joined) }
        return buildJsonObject {
            putJsonArray("accounts") { joined.values.forEach { row -> addJsonObject { write(this, row) } } }
        }.toString()
    }

    private suspend fun fold(managed: ManagedHead, joined: MutableMap<String, JoinedAccount>) {
        val description = managed.auth.describe()
        // Api-key and client-forwarded heads (Claude on the client's own login) are a different
        // feature row (§4.5 "Key providers", "Claude logins") — this join is OAuth accounts only.
        if (!AuthKindRegistry.isOAuth(description.kind)) return
        val pool = managed.accountPool
        if (pool == null) {
            foldSingleLogin(managed.head.key, description, joined)
            return
        }
        val authPaths = managed.accountAuth?.descriptions().orEmpty().mapValues { (_, d) -> d.fields["auth_path"] }
        foldPooled(managed.head.key, description.kind, pool.view(null), authPaths, joined)
    }

    private fun foldSingleLogin(
        headKey: String,
        description: AuthDescription,
        joined: MutableMap<String, JoinedAccount>,
    ) {
        val authPath = description.fields["auth_path"]
        merge(joined, authPath ?: "$headKey:single", headKey) {
            JoinedAccount(
                credentialPath = authPath,
                kind = description.kind,
                label = null,
                primary = true,
                singleLogin = true,
                plan = null,
                fiveHour = QuotaWindowView(null, null, null),
                sevenDay = QuotaWindowView(null, null, null),
                authExclusion = AuthExclusionView(null, null),
                flags = AccountFlags(
                    available = null,
                    credentialPresent = description.present,
                    selected = null,
                    pinned = null,
                    nextTarget = null,
                ),
            )
        }
    }

    private fun foldPooled(
        headKey: String,
        kind: String,
        view: HeadAccountPoolView,
        authPaths: Map<String, String?>,
        joined: MutableMap<String, JoinedAccount>,
    ) {
        view.accounts.forEach { account ->
            val authPath = authPaths[account.label]
            merge(joined, authPath ?: "$headKey:${account.label}", headKey) {
                pooledAccount(kind, account, view, authPath)
            }
        }
    }

    private fun pooledAccount(
        kind: String,
        account: HeadAccountView,
        view: HeadAccountPoolView,
        authPath: String?,
    ): JoinedAccount = JoinedAccount(
        credentialPath = authPath,
        kind = kind,
        label = account.label,
        primary = account.primary,
        singleLogin = false,
        plan = account.plan,
        fiveHour = QuotaWindowView(
            account.fiveHourUsedPercent,
            account.fiveHourResetEpochSeconds,
            account.fiveHourWindowSeconds,
        ),
        sevenDay = QuotaWindowView(
            account.sevenDayUsedPercent,
            account.sevenDayResetEpochSeconds,
            account.sevenDayWindowSeconds,
        ),
        authExclusion = AuthExclusionView(account.authExcludedUntilEpochMillis, account.authExclusionReason),
        flags = AccountFlags(
            available = account.available,
            credentialPresent = account.credentialPresent,
            selected = account.selected,
            pinned = account.label == view.pinnedLabel,
            nextTarget = account.label == view.nextTargetLabel,
        ),
    )

    // The one join point: an existing row (another head sharing this credential path) only grows
    // its `heads` set; a new key builds the row once, from whichever head reached it first. inline
    // (kt-no-lambda-seam exemption): a raw () -> JoinedAccount here would need a named fun
    // interface for one two-call-site builder.
    private inline fun merge(
        joined: MutableMap<String, JoinedAccount>,
        key: String,
        headKey: String,
        build: () -> JoinedAccount,
    ) {
        val existing = joined[key]
        if (existing != null) {
            existing.heads += headKey
        } else {
            joined[key] = build().also { it.heads += headKey }
        }
    }

    private fun write(into: JsonObjectBuilder, row: JoinedAccount) {
        into.put("credential_path", row.credentialPath)
        into.put("kind", row.kind)
        into.put("label", row.label)
        into.put("primary", row.primary)
        into.put("single_login", row.singleLogin)
        into.put("plan", row.plan)
        into.put("five_hour_used_percent", row.fiveHour.usedPercent)
        into.put("five_hour_reset_epoch_seconds", row.fiveHour.resetEpochSeconds)
        into.put("five_hour_window_seconds", row.fiveHour.windowSeconds)
        into.put("seven_day_used_percent", row.sevenDay.usedPercent)
        into.put("seven_day_reset_epoch_seconds", row.sevenDay.resetEpochSeconds)
        into.put("seven_day_window_seconds", row.sevenDay.windowSeconds)
        into.put("available", row.flags.available)
        into.put("credential_present", row.flags.credentialPresent)
        into.put("auth_excluded_until_epoch_millis", row.authExclusion.untilEpochMillis)
        into.put("auth_exclusion_reason", row.authExclusion.reason)
        into.put("selected", row.flags.selected)
        into.put("pinned", row.flags.pinned)
        into.put("next_target", row.flags.nextTarget)
        into.putJsonArray("heads") { row.heads.sorted().forEach { add(it) } }
    }
}

/** One quota window's percent, reset and (V4-132) its own reported LENGTH — [AccountPool]'s own
 *  [splice.upstream.credentials.AccountView] carries the same three fields; this is the console-payload copy of
 *  that shape, grouped so [JoinedAccount] stays under checks/constructor-width.ts's 12-param
 *  ceiling with five-hour and seven-day as ONE field each instead of three. */
private data class QuotaWindowView(val usedPercent: Double?, val resetEpochSeconds: Long?, val windowSeconds: Long?)

/** Why a pooled or single-login account cannot be selected right now, or both null when it can. */
private data class AuthExclusionView(val untilEpochMillis: Long?, val reason: String?)

/** The five yes/no/unknown facts a console row renders as booleans — grouped for the same
 *  constructor-width reason as [QuotaWindowView]. */
private data class AccountFlags(
    val available: Boolean?,
    val credentialPresent: Boolean,
    val selected: Boolean?,
    val pinned: Boolean?,
    val nextTarget: Boolean?,
)

/** One joined row: an OAuth account (or a single-login head with none) plus every head riding it.
 *  A `data class` (LongParameterList's `ignoreDataClasses`) even though nothing here compares or
 *  copies one — [merge] mutates [heads] in place as later heads join the same credential path. */
private data class JoinedAccount(
    val credentialPath: String?,
    val kind: String,
    val label: String?,
    val primary: Boolean,
    val singleLogin: Boolean,
    val plan: String?,
    val fiveHour: QuotaWindowView,
    val sevenDay: QuotaWindowView,
    val authExclusion: AuthExclusionView,
    val flags: AccountFlags,
    val heads: MutableSet<String> = sortedSetOf(),
)
