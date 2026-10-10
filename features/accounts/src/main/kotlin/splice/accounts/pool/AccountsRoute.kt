// NEW: V4-132 — GET /api/accounts (FEATURES.md §6, §4.5 "All accounts, one screen"). Every OAuth
// account of every provider in one payload, joined on the credential path so one login shared by
// several heads (two heads pointed at the same primary file, HeadAccountPools.kt:54) is one row,
// not one per head. Windows carry their own reported LENGTH (Grok's is 30 days, not a week);
// plan is reported only when a provider sends one; exclusion, selection, the operator's pin and
// the next target follow the REAL selector order (AccountPool.candidates). A head with one login
// (no pool at all) still appears, read from its /api/auth view, with `single_login: true`.
package splice.accounts.pool

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.accounts.AccountHead
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginRows
import splice.core.auth.AuthDescription
import splice.core.auth.REFUSAL_FIELD
import splice.core.topology.AuthKindRegistry
import splice.core.usage.QuotaView
import java.util.concurrent.TimeUnit

/** [firsts] answers for a command's first OAuth account, so its row can be renamed and removed. Null leaves it
 *  fixed. */
public class AccountsRoute(private val heads: Map<String, AccountHead>, private val firsts: FirstAccounts? = null) {
    private val extras = AccountRowExtras(heads)
    private val management = AccountRowManagement()

    /** [nowSeconds] decides which windows are current (V4-407), by the rule /api/usage applies (V4-396). */
    public suspend fun accountsJson(
        nowSeconds: Long = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()),
    ): String = accountsJson(emptyMap(), emptyList(), emptyMap(), nowSeconds)

    /** Provider names come from the declared topology; native windows come only from command-local observations.
     *  [carrying] maps a head to the place whose credential carried its newest matched request, null before any. */
    public suspend fun accountsJson(
        providers: Map<String, String>,
        native: List<ClaudeLoginPlaceView>,
        carrying: Map<String, ClaudeLoginPlaceId?>,
        nowSeconds: Long = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()),
    ): String {
        val joined = LinkedHashMap<String, JoinedAccount>()
        heads.values.forEach { head -> fold(head, joined) }
        val nativeRows = ClaudeLoginRows.list(native, providers, carrying, heads, nowSeconds)
        return buildJsonObject {
            putJsonArray("accounts") {
                joined.values.forEach { row -> addJsonObject { write(this, row, nowSeconds, providers) } }
                nativeRows.forEach { add(it) }
            }
            extras.pools(this)
        }.toString()
    }

    private suspend fun fold(head: AccountHead, joined: MutableMap<String, JoinedAccount>) {
        val description = head.auth.describe()
        val pool = head.activePool
        // A forwarded head's stored pool logins are real accounts. Only its unheld caller is represented by places.
        if (description.kind == "client" && pool == null) return
        if (!AuthKindRegistry.isOAuth(description.kind) && description.kind !in listOf("api-key", "client")) return
        if (pool == null || description.kind == "api-key") {
            foldSingleLogin(head.key, description, head.quota?.quota(), joined)
            return
        }
        foldPooled(head.key, description.kind, pool.view(null), head.accountAuth?.descriptions().orEmpty(), joined)
    }

    private fun foldSingleLogin(
        headKey: String,
        description: AuthDescription,
        quota: QuotaView?,
        joined: MutableMap<String, JoinedAccount>,
    ) {
        val authPath = description.fields["auth_path"]
        merge(joined, authPath ?: "$headKey:single", headKey) {
            JoinedAccount(
                credential = JoinedCredential(path = authPath, kind = description.kind, singleLogin = true),
                label = null,
                primary = true,
                quota = JoinedQuota(
                    plan = quota?.plan,
                    // The length the provider reported rides the head's QuotaView (null when it gave none), so a long
                    // window is 30 days where the provider says 30 days, never a week by default.
                    fiveHour = QuotaWindowView(
                        quota?.fiveHour?.usedPct?.toDouble(),
                        quota?.fiveHour?.resetsAt,
                        quota?.fiveHour?.windowSeconds,
                    ),
                    sevenDay = QuotaWindowView(
                        quota?.sevenDay?.usedPct?.toDouble(),
                        quota?.sevenDay?.resetsAt,
                        quota?.sevenDay?.windowSeconds,
                    ),
                    observedAtEpochSeconds = quota?.fiveHour?.observedAt ?: quota?.sevenDay?.observedAt,
                    noUsageAt = quota?.noUsageAt,
                ),
                authExclusion = AuthExclusionView(null, null, identity = identityOf(description.fields)),
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
        described: Map<String, AuthDescription>,
        joined: MutableMap<String, JoinedAccount>,
    ) {
        view.accounts.forEach { account ->
            if (kind == "client" && account.primary) return@forEach
            val fields = described[account.label]?.fields.orEmpty()
            if (fields["native_place"] != null) return@forEach
            merge(joined, fields["auth_path"] ?: "$headKey:${account.label}", headKey, account.label) {
                pooledAccount(kind, account, view, fields)
            }
        }
    }

    /** [fields] is the account's own [AuthDescription] fields: its credential path, and (V4-410) `refusal`
     *  for a credential splice will not load. That lives there, not on [HeadAccountView], whose width the
     *  constructor-width ratchet has recorded. */
    private fun pooledAccount(
        kind: String,
        account: HeadAccountView,
        view: HeadAccountPoolView,
        fields: Map<String, String>,
    ): JoinedAccount = JoinedAccount(
        credential = JoinedCredential(path = fields["auth_path"], kind = kind, singleLogin = false),
        label = account.label,
        primary = account.primary,
        quota = JoinedQuota(
            plan = account.plan,
            fiveHour = QuotaWindowView(
                account.quota.fiveHour.usedPercent,
                account.quota.fiveHour.resetEpochSeconds,
                account.quota.fiveHour.windowSeconds,
            ),
            sevenDay = QuotaWindowView(
                account.quota.sevenDay.usedPercent,
                account.quota.sevenDay.resetEpochSeconds,
                account.quota.sevenDay.windowSeconds,
            ),
            observedAtEpochSeconds = account.quota.observedAtEpochSeconds,
            sevenDayModels = account.quota.sevenDayModels,
            noUsageAt = account.quota.noUsageAtEpochSeconds,
        ),
        authExclusion = AuthExclusionView(
            account.credential.excludedUntilEpochMillis,
            account.credential.exclusionReason,
            fields[REFUSAL_FIELD],
            identityOf(fields),
            fields["display_name"],
        ),
        flags = AccountFlags(
            available = account.available,
            credentialPresent = account.credential.present,
            selected = account.selected,
            pinned = account.label == view.pinnedLabel,
            nextTarget = account.label == view.nextTargetLabel,
        ),
    )

    // The one join point: an existing row (another head sharing this credential path) only grows
    // its per-head selector map; a new key builds the row once, from whichever head reached it first. inline
    // (kt-no-lambda-seam exemption): a raw () -> JoinedAccount here would need a named fun
    // interface for one two-call-site builder.
    private inline fun merge(
        joined: MutableMap<String, JoinedAccount>,
        key: String,
        headKey: String,
        label: String? = null,
        build: () -> JoinedAccount,
    ) {
        val existing = joined[key]
        if (existing != null) {
            existing.labelsByHead[headKey] = label
        } else {
            joined[key] = build().also { it.labelsByHead[headKey] = label }
        }
    }

    private fun write(
        into: JsonObjectBuilder,
        row: JoinedAccount,
        nowSeconds: Long,
        providers: Map<String, String>,
    ) {
        extras.write(into, row.labelsByHead, providers)
        val first = first(row)
        val name = row.authExclusion.displayName ?: first?.name ?: row.label ?: "Primary"
        management.write(into, name, row.authExclusion.identity, management.edit(row.label, row.primary, first))
        writeQuota(into, row, nowSeconds)
        val answer = row.labelsByHead.mapNotNull { (head, label) -> heads[head]?.answers?.answer(label) }
            .maxByOrNull { it.observedAtEpochMs }
        AccountAnswerJson.write(into, answer)
    }

    /** The account a credential proves it signed in as, when its description names one: a Claude login's, or a
     *  ChatGPT sign-in's user id and email. */
    private fun identityOf(fields: Map<String, String>): ClaudeAccountIdentity? =
        fields["account_uuid"]?.takeIf(String::isNotBlank)?.let { ClaudeAccountIdentity(it, fields["account_email"]) }

    /** A first OAuth account's name and whether it may be removed; null for every other row. */
    private fun first(row: JoinedAccount): FirstAccountEdit? {
        val path = row.credential.path?.takeIf { row.primary && AuthKindRegistry.isOAuth(row.credential.kind) }
        val facts = firsts ?: return null
        val kind = row.credential.kind
        return path?.let { FirstAccountEdit(facts.name(kind, it), !facts.shared(kind, it)) }
    }

    private fun writeQuota(into: JsonObjectBuilder, row: JoinedAccount, nowSeconds: Long) {
        row.quota.writeInto(into, nowSeconds)
        into.put("credential_path", row.credential.path)
        into.put("kind", row.credential.kind)
        into.put("label", row.label)
        into.put("primary", row.primary)
        into.put("single_login", row.credential.singleLogin)
        into.put("available", row.flags.available)
        into.put("credential_present", row.flags.credentialPresent)
        into.put("auth_excluded_until_epoch_millis", row.authExclusion.untilEpochMillis)
        into.put("auth_exclusion_reason", row.authExclusion.reason)
        into.put("refusal", row.authExclusion.refusal)
        into.put("selected", row.flags.selected)
        into.put("pinned", row.flags.pinned)
        into.put("next_target", row.flags.nextTarget)
        into.putJsonArray("heads") { row.heads.sorted().forEach { add(it) } }
    }
}

/** Why a pooled or single-login account cannot be selected right now, or all null when it can. [refusal]
 *  (V4-410) is the permanent kind: splice will not load that credential at all, so it is not a renewal. */
private data class AuthExclusionView(
    val untilEpochMillis: Long?,
    val reason: String?,
    val refusal: String? = null,
    val identity: ClaudeAccountIdentity? = null,
    val displayName: String? = null,
)

/** The five yes/no/unknown facts a console row renders as booleans — grouped for the same
 *  constructor-width reason as [QuotaWindowView]. */
private data class AccountFlags(
    val available: Boolean?,
    val credentialPresent: Boolean,
    val selected: Boolean?,
    val pinned: Boolean?,
    val nextTarget: Boolean?,
)

/** The credential a joined row stands for: where it lives, which auth kind owns it, and whether it is a head's
 *  single login rather than a pooled account. */
private data class JoinedCredential(val path: String?, val kind: String, val singleLogin: Boolean)

/** One joined row: an OAuth account (or a single-login head with none) plus every head riding it.
 *  [merge] retains each head's own selector in [labelsByHead] as it joins the same credential path. */
private data class JoinedAccount(
    val credential: JoinedCredential,
    val label: String?,
    val primary: Boolean,
    val quota: JoinedQuota,
    val authExclusion: AuthExclusionView,
    val flags: AccountFlags,
    val labelsByHead: MutableMap<String, String?> = sortedMapOf(),
) {
    val heads: Set<String> get() = labelsByHead.keys
}
