// NEW: v0.4.0 FEATURES.md §11 — durable OAuth account discovery and safe labels.
package splice.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.topology.AuthKind
import splice.core.util.Cancellables
import splice.core.util.LogSink
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal const val FIELD_KIND = "splice_auth_kind"
internal const val FIELD_LABEL = "splice_account_label"
internal const val PRIMARY = "primary"
internal const val AUTO = "auto"
private const val HASH_CHARS = 8
internal const val JSON_SUFFIX = ".json"
internal const val MAX_LABEL_CHARS = 48

/** One credential's non-secret durable identity. [refusal] is set only for an entry splice will not load,
 *  in words for the operator. */
public data class OAuthAccountFile(
    public val label: String,
    public val credentialFile: Path,
    public val quotaFile: Path,
    public val primary: Boolean,
    public val credentialPresent: Boolean,
    public val refusal: String? = null,
)

/** Provider-specific resolver for a safe default label. Ordinal providers can resolve before
 *  OAuth; token-derived providers return null until the shaped response is supplied. */
public fun interface OAuthAccountLabel {
    public operator fun invoke(authJson: JsonObject?): String?
}

/** Stable provider identity used only to recognize an existing credential, never printed. */
public fun interface OAuthAccountIdentity {
    public operator fun invoke(authJson: JsonObject): String?
}

/**
 * One login's already-validated credential destination, and the OWNER of that login's mutable
 * handoff state: the ordinal reservation lease it holds until the credential is persisted, and the
 * label that was actually written.
 *
 * NOT a `data class`, and V4-114 (kt-no-atomic-in-data-class) is why. A `data class` is a value you
 * compare and copy; this is a LEASE HOLDER — `holdReservation`'s `check(compareAndSet(null, lease))`
 * is an invariant about ONE instance, and exactly one instance is threaded from [OAuthAccountFiles.
 * loginAccount] through the login spec to [releaseReservation]. The generated members lied about
 * both halves: `equals`/`hashCode` compared the two atomics by reference, so two accounts with
 * identical fields were unequal; and `copy()` does not carry a body property at all, so a copied
 * account had silently dropped the lease its own uniqueness check relies on. Identity equality is
 * the truth here, and [copy] below is now an explicit, reviewed operation that says so.
 */
public class OAuthLoginAccount(
    public val kind: AuthKind.OAuth,
    public val primary: Boolean,
    public val label: String?,
    public val defaultLabel: OAuthAccountLabel? = null,
    /** Only token-derived labels may move after exchange; ordinal labels bind device identity. */
    public val tokenDerivedLabel: Boolean = false,
    public val identity: OAuthAccountIdentity? = null,
) {
    private val reservation = AtomicReference<OAuthLoginReservation.Lease?>(null)
    private val writtenLabel = AtomicReference<String?>(null)
    private val setAsideQuota = AtomicReference<Path?>(null)
    private val refusal = AtomicReference<String?>(null)
    private val refusedOnDisk = AtomicBoolean(false)

    /** A RE-PLANNED destination for a login that has not started, now bound to a reserved [label]: the label is
     *  final, so the default and the token-derived move are dropped. The reservation and the persisted label are
     *  deliberately NOT carried, because a lease belongs to exactly one account and the caller holds it at this
     *  point (LoginKimi/LoginMuse reserve, re-plan, THEN [holdReservation]). Hand-written rather than generated so
     *  that "carries no lease" is a documented decision instead of a `data class` side effect (V4-114). */
    public fun withReservedLabel(label: String?): OAuthLoginAccount {
        check(reservation.get() == null) { "re-plan an OAuth login account BEFORE it holds a reservation" }
        return OAuthLoginAccount(kind, primary, label, tokenDerivedLabel = false, identity = identity)
    }

    public fun resolvedLabel(authJson: JsonObject? = null): String? = label ?: defaultLabel?.invoke(authJson)

    internal fun holdReservation(lease: OAuthLoginReservation.Lease) {
        check(reservation.compareAndSet(null, lease)) { "OAuth login account already owns a reservation" }
    }

    internal fun recordPersistedLabel(label: String) {
        writtenLabel.set(label)
    }

    public fun persistedLabel(): String? = writtenLabel.get()

    public fun setAsideQuota(): Path? = setAsideQuota.get()

    /** Authored refusal, never a provider body. The console uses the same words as the CLI. */
    public fun refusal(): String? = refusal.get()

    /** Whether [refusal] is the credential failing to reach disk, which fails again until the disk is fixed, rather
     *  than a refusal of the account itself. */
    public fun refusedOnDisk(): Boolean = refusedOnDisk.get()

    internal fun recordSetAsideQuota(path: Path?) {
        setAsideQuota.set(path)
    }

    internal fun recordRefusal(reason: String, onDisk: Boolean) {
        refusal.set(reason)
        refusedOnDisk.set(onDisk)
    }

    internal fun releaseReservation() {
        val lease = reservation.getAndSet(null) ?: return
        Cancellables.discard(
            Cancellables.runCatchingCleanup(lease::close),
            "an OAuth ordinal lease is process-local cleanup after its login completes",
        )
    }
}

/** The persisted destination and any retained quota that required a different token-derived label. */
public data class OAuthAccountWrite(
    public val file: Path,
    public val retainedQuota: Path? = null,
    public val setAsideQuota: Path? = null,
)

/** Authored operator-facing refusal; [reason] contains no credential material. */
public class OAuthAccountRefused(public val reason: String) : IllegalArgumentException(reason)

/** Finds the in-place legacy primary and validated labeled accounts for one OAuth kind. */
public class OAuthAccountFiles(private val json: Json = Json { ignoreUnknownKeys = true }) {
    private val validation = OAuthAccountValidation(json)
    private val writes = OAuthAccountWrites(json, validation)
    private val unloaded = OAuthPoolListing(validation)

    /** Every account of [primaryFile]'s pool, the primary first. [log] receives each pool file skipped
     *  as not a credential: the daemon hands in the head's log, so the line reaches /mgmt/logs. It
     *  was a constructor default of `System.err::print`, which reached stderr only (LAYOUT-01).
     *
     *  A label is named by any `.json` entry, credential or quota, so an account whose credential is
     *  gone still lists (V4-405, [OAuthPoolListing.orphanedQuota]) and a linked credential lists as refused
     *  ([OAuthPoolListing.refusedLink]) instead of vanishing after a restart. Entries sort by their
     *  credential file name, as they always did. */
    public fun discover(kind: AuthKind.OAuth, primaryFile: Path, log: LogSink): List<OAuthAccountFile> {
        val poolDir = poolDir(kind, primaryFile)
        val found = mutableListOf(account(PRIMARY, primaryFile, poolDir, primary = true))
        occupiedLabels(kind, primaryFile).sortedBy { "$it$JSON_SUFFIX" }.forEach { label ->
            listing(kind, label, poolDir, log)?.let(found::add)
        }
        require(found.map(OAuthAccountFile::label).distinct().size == found.size) {
            "duplicate OAuth account label for ${kind.wire}"
        }
        return found
    }

    /** Labeled entries use the writer's NOFOLLOW policy; legacy primary resolution is unchanged. */
    private fun listing(kind: AuthKind.OAuth, label: String, poolDir: Path, log: LogSink): OAuthAccountFile? {
        val credential = poolDir.resolve("$label$JSON_SUFFIX")
        return when {
            Files.isRegularFile(credential, LinkOption.NOFOLLOW_LINKS) ->
                validation.validatedLabel(kind, credential, log)?.let { account(it, credential, poolDir, false) }
            Files.isSymbolicLink(credential) -> unloaded.refusedLink(label, poolDir)
            !Files.exists(credential, LinkOption.NOFOLLOW_LINKS) -> unloaded.orphanedQuota(label, poolDir)
            else -> null
        }
    }

    /** Resolves the destination before OAuth starts, so an invalid label never burns a login. */
    public fun loginAccount(
        kind: AuthKind.OAuth,
        primaryFile: Path,
        requestedLabel: String?,
        defaultLabel: OAuthAccountLabel? = null,
        identity: OAuthAccountIdentity? = null,
    ): OAuthLoginAccount {
        // Signing the primary account in again is allowed under its own label; a NEW account never takes it.
        val primaryAgain = requestedLabel == PRIMARY && Files.isRegularFile(primaryFile)
        if (requestedLabel == null || primaryAgain) return OAuthLoginAccount(kind, primary = true, label = null)
        if (requestedLabel == PRIMARY) {
            refuse("the primary account keeps its reserved label; sign in without --label to create it")
        }
        if (requestedLabel != AUTO) validation.requireLabel(requestedLabel)
        val primaryExists = Files.isRegularFile(primaryFile)
        if (!primaryExists) {
            refuse("sign in without --label first to create the primary ${kind.wire} account")
        }
        if (requestedLabel == AUTO) {
            val resolver = defaultLabel ?: ordinalLabel(kind, primaryFile)
            return OAuthLoginAccount(
                kind,
                primary = false,
                label = null,
                defaultLabel = resolver,
                tokenDerivedLabel = resolver(null) == null,
                identity = identity,
            )
        }
        return OAuthLoginAccount(kind, primary = false, label = requestedLabel)
    }

    /** The pool of [primaryFile]: `<dir>/<kind>/<primary file name>/`. Keyed by the PRIMARY FILE, not
     *  the kind alone, so two same-kind heads whose credentials sit in one directory (codex.json and
     *  codex-work.json) never discover each other's labeled accounts or share a quota file; two heads
     *  sharing one credential file share its pool, which is the same account. The whole file name,
     *  extension included, so `codex` and `codex.json` cannot collide (review 2026-09-14). */
    public fun poolDir(kind: AuthKind.OAuth, primaryFile: Path): Path {
        val parent = requireNotNull(primaryFile.parent) { "OAuth credential path has no parent: $primaryFile" }
        return parent.resolve(kind.wire).resolve(primaryFile.fileName.toString())
    }

    /** Adds splice metadata without changing any provider-native credential field. */
    public fun decorated(kind: AuthKind.OAuth, label: String, providerJson: JsonObject): JsonObject =
        writes.decorated(kind, label, providerJson)

    public fun writeLabeled(kind: AuthKind.OAuth, primaryFile: Path, label: String, providerJson: JsonObject): Path =
        writes.writeLabeled(kind, poolDir(kind, primaryFile), label, providerJson)

    public fun writeLabeledRenewal(
        kind: AuthKind.OAuth,
        primaryFile: Path,
        label: String,
        providerJson: JsonObject,
    ): OAuthAccountWrite =
        writes.writeLabeledRenewal(kind, poolDir(kind, primaryFile), label, providerJson)

    /** Post-exchange collision handling is only for labels that cannot be resolved before OAuth. */
    public fun writeTokenDerived(
        kind: AuthKind.OAuth,
        primaryFile: Path,
        label: String,
        providerJson: JsonObject,
        identity: OAuthAccountIdentity? = null,
    ): OAuthAccountWrite = writes.writeTokenDerived(kind, poolDir(kind, primaryFile), label, providerJson, identity)

    /** DELETE /api/auth/{head}/accounts/{label} (FEATURES.md §6): the credential and any retained
     *  quota file. True when a credential existed to remove. The primary is refused — it is not a
     *  labeled file under [poolDir], there is nothing here to delete for it. */
    public fun remove(kind: AuthKind.OAuth, primaryFile: Path, label: String): Boolean {
        if (label == PRIMARY) refuse("the primary account cannot be removed; delete its credential file directly")
        val dir = poolDir(kind, primaryFile)
        val existed = Files.deleteIfExists(dir.resolve("$label$JSON_SUFFIX"))
        Files.deleteIfExists(dir.resolve("$label-quota$JSON_SUFFIX"))
        return existed
    }

    /** PATCH /api/auth/{head}/accounts/{label} (FEATURES.md §6): renames a labeled account's
     *  credential (and any retained quota) in place, re-embedding the new label — [validatedLabel]
     *  requires the file's `splice_account_label` to match its own filename, so a rename that only
     *  moved the file would make discovery throw on the very next boot. Reuses [decorated] (via
     *  [writes]) for that re-embedding and its label-syntax check, rather than re-deriving them. */
    public fun relabel(kind: AuthKind.OAuth, primaryFile: Path, label: String, newLabel: String): Path {
        if (label == PRIMARY) refuse("the primary account cannot be relabeled")
        val dir = poolDir(kind, primaryFile)
        val credential = dir.resolve("$label$JSON_SUFFIX")
        if (!Files.isRegularFile(credential, LinkOption.NOFOLLOW_LINKS)) {
            refuse("no OAuth account labeled '$label'")
        }
        // writeLabeled's own retainedQuota guard only refuses an ORPHANED quota with no credential
        // at the destination; a destination that already has its OWN credential would otherwise be
        // silently overwritten, so that collision is checked here first.
        if (label != newLabel && Files.exists(dir.resolve("$newLabel$JSON_SUFFIX"), LinkOption.NOFOLLOW_LINKS)) {
            refuse("an OAuth account is already labeled '$newLabel'")
        }
        // Unreadable and unparseable both refuse below by the same name.
        val parsed = validation.jsonObjectOrNull(credential) ?: refuse("credential for '$label' is not valid JSON")
        val destination = writes.writeLabeled(kind, dir, newLabel, parsed)
        if (destination != credential) Files.delete(credential)
        val quota = dir.resolve("$label-quota$JSON_SUFFIX")
        if (label != newLabel && Files.isRegularFile(quota, LinkOption.NOFOLLOW_LINKS)) {
            Files.move(quota, dir.resolve("$newLabel-quota$JSON_SUFFIX"))
        }
        return destination
    }

    private fun account(label: String, path: Path, poolDir: Path, primary: Boolean): OAuthAccountFile =
        OAuthAccountFile(
            label,
            path,
            poolDir.resolve("$label-quota.json"),
            primary,
            Files.isRegularFile(path),
        )

    private fun ordinalLabel(kind: AuthKind.OAuth, primaryFile: Path): OAuthAccountLabel {
        // Silent on purpose: occupiedLabels below reserves every .json name, skipped or not, so a
        // skipped stray file cannot move this ordinal; the daemon's discovery reports it at boot.
        val used = discover(kind, primaryFile, LogSink {}).mapTo(mutableSetOf(), OAuthAccountFile::label)
        used += occupiedLabels(kind, primaryFile)
        val resolved = OAuthAccountLabels.ordinal(kind, used)
        return OAuthAccountLabel { resolved }
    }

    private fun occupiedLabels(kind: AuthKind.OAuth, primaryFile: Path): Set<String> {
        val dir = poolDir(kind, primaryFile)
        if (!Files.isDirectory(dir)) return emptySet()
        val labels = mutableSetOf<String>()
        Files.list(dir).use { paths ->
            // Occupied credentials and quota keep their ordinal even when discovery excludes a link.
            paths.map { it.fileName.toString() }
                .filter { it.endsWith(JSON_SUFFIX) }
                .forEach { labels += it.removeSuffix("-quota.json").removeSuffix(JSON_SUFFIX) }
        }
        return labels
    }

    private fun refuse(reason: String): Nothing = throw OAuthAccountRefused(reason)
}

/** Non-PII defaults used when login was not given --label. */
internal object OAuthAccountLabels {
    fun chatGpt(plan: String?, accountId: String): String {
        val safePlan = plan.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "chatgpt" }
        val digest = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(StandardCharsets.UTF_8))
        val shortHash = digest.joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
        return "$safePlan-$shortHash".take(MAX_LABEL_CHARS)
    }

    fun ordinal(kind: AuthKind.OAuth, used: Set<String>): String {
        val base = kind.wire.removeSuffix("-oauth")
        val ordinal = generateSequence(2, Int::inc).first { "$base-$it" !in used }
        return "$base-$ordinal"
    }
}
