// NEW: v0.4.0 FEATURES.md §11 — durable OAuth account discovery and safe labels.
package splice.app.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.core.topology.AuthKind
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

internal const val FIELD_KIND = "splice_auth_kind"
internal const val FIELD_LABEL = "splice_account_label"
internal const val PRIMARY = "primary"
internal const val AUTO = "auto"
private const val HASH_CHARS = 8
private const val JSON_SUFFIX = ".json"
internal const val MAX_LABEL_CHARS = 48

/** One credential's non-secret durable identity. */
public data class OAuthAccountFile(
    val label: String,
    val credentialFile: Path,
    val quotaFile: Path,
    val primary: Boolean,
    val credentialPresent: Boolean,
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

/** One login's already-validated credential destination. */
public data class OAuthLoginAccount(
    val kind: AuthKind.OAuth,
    val primary: Boolean,
    val label: String?,
    val defaultLabel: OAuthAccountLabel? = null,
    /** Only token-derived labels may move after exchange; ordinal labels bind device identity. */
    val tokenDerivedLabel: Boolean = false,
    val identity: OAuthAccountIdentity? = null,
) {
    public fun resolvedLabel(authJson: JsonObject? = null): String? = label ?: defaultLabel?.invoke(authJson)
}

/** The persisted destination and any retained quota that required a different token-derived label. */
public data class OAuthAccountWrite(val file: Path, val retainedQuota: Path? = null)

/** Authored operator-facing refusal; [reason] contains no credential material. */
public class OAuthAccountRefused(public val reason: String) : IllegalArgumentException(reason)

/** Finds the in-place legacy primary and validated labeled accounts for one OAuth kind. */
public class OAuthAccountFiles(private val json: Json = Json { ignoreUnknownKeys = true }) {
    private val writes = OAuthAccountWrites(json)

    public fun discover(kind: AuthKind.OAuth, primaryFile: Path): List<OAuthAccountFile> {
        val poolDir = poolDir(kind, primaryFile)
        val found = mutableListOf(account(PRIMARY, primaryFile, poolDir, primary = true))
        if (!Files.isDirectory(poolDir)) return found
        Files.list(poolDir).use { paths ->
            // Labeled entries use the writer's NOFOLLOW policy; legacy primary resolution is unchanged.
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { it.fileName.toString().endsWith(JSON_SUFFIX) }
                .filter { !it.fileName.toString().endsWith("-quota.json") }
                .sorted()
                .forEach { path -> found += validated(kind, path, poolDir) }
        }
        require(found.map(OAuthAccountFile::label).distinct().size == found.size) {
            "duplicate OAuth account label for ${kind.wire}"
        }
        return found
    }

    /** Resolves the destination before OAuth starts, so an invalid label never burns a login. */
    public fun loginAccount(
        kind: AuthKind.OAuth,
        primaryFile: Path,
        requestedLabel: String?,
        defaultLabel: OAuthAccountLabel? = null,
        identity: OAuthAccountIdentity? = null,
    ): OAuthLoginAccount {
        if (requestedLabel == null) return OAuthLoginAccount(kind, primary = true, label = null)
        if (requestedLabel == PRIMARY) {
            refuse("the primary account keeps its reserved label; omit --label to sign in again")
        }
        if (requestedLabel != AUTO) requireLabel(requestedLabel)
        val primaryExists = Files.isRegularFile(primaryFile)
        if (!primaryExists) {
            refuse("sign in without --label first to create the primary ${kind.wire} account")
        }
        if (requestedLabel != AUTO && staleQuota(kind, primaryFile, requestedLabel)) {
            refuse("OAuth account label has retained quota state; choose another label")
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

    public fun poolDir(kind: AuthKind.OAuth, primaryFile: Path): Path =
        requireNotNull(primaryFile.parent) { "OAuth credential path has no parent: $primaryFile" }.resolve(kind.wire)

    /** Adds splice metadata without changing any provider-native credential field. */
    public fun decorated(kind: AuthKind.OAuth, label: String, providerJson: JsonObject): JsonObject =
        writes.decorated(kind, label, providerJson)

    public fun writeLabeled(kind: AuthKind.OAuth, primaryFile: Path, label: String, providerJson: JsonObject): Path =
        writes.writeLabeled(kind, poolDir(kind, primaryFile), label, providerJson)

    /** Post-exchange collision handling is only for labels that cannot be resolved before OAuth. */
    public fun writeTokenDerived(
        kind: AuthKind.OAuth,
        primaryFile: Path,
        label: String,
        providerJson: JsonObject,
        identity: OAuthAccountIdentity? = null,
    ): OAuthAccountWrite = writes.writeTokenDerived(kind, poolDir(kind, primaryFile), label, providerJson, identity)

    private fun validated(kind: AuthKind.OAuth, path: Path, poolDir: Path): OAuthAccountFile {
        val label = path.fileName.toString().removeSuffix(JSON_SUFFIX)
        requireLabel(label)
        val raw = runCatching { json.parseToJsonElement(Files.readString(path)).jsonObject }
            .getOrElse { throw IllegalArgumentException("invalid ${kind.wire} OAuth account file", it) }
        val fileKind = (raw[FIELD_KIND] as? JsonPrimitive)?.content
        val fileLabel = (raw[FIELD_LABEL] as? JsonPrimitive)?.content
        require(fileKind == kind.wire) { "pooled OAuth account file has the wrong auth kind" }
        require(fileLabel == label) { "pooled OAuth account file does not match its filename" }
        return account(label, path, poolDir, primary = false)
    }

    private fun account(label: String, path: Path, poolDir: Path, primary: Boolean): OAuthAccountFile =
        OAuthAccountFile(
            label,
            path,
            poolDir.resolve("$label-quota.json"),
            primary,
            Files.isRegularFile(path),
        )

    private fun staleQuota(kind: AuthKind.OAuth, primaryFile: Path, label: String): Boolean =
        writes.retainedQuota(poolDir(kind, primaryFile), label) != null

    private fun ordinalLabel(kind: AuthKind.OAuth, primaryFile: Path): OAuthAccountLabel {
        val used = discover(kind, primaryFile).mapTo(mutableSetOf(), OAuthAccountFile::label)
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

    private fun requireLabel(label: String) = writes.requireLabel(label)
}

/** Non-PII defaults used when login was not given --label. */
public object OAuthAccountLabels {
    public fun chatGpt(plan: String?, accountId: String): String {
        val safePlan = plan.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "chatgpt" }
        val digest = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(StandardCharsets.UTF_8))
        val shortHash = digest.joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
        return "$safePlan-$shortHash".take(MAX_LABEL_CHARS)
    }

    public fun ordinal(kind: AuthKind.OAuth, used: Set<String>): String {
        val base = kind.wire.removeSuffix("-oauth")
        val ordinal = generateSequence(2, Int::inc).first { "$base-$it" !in used }
        return "$base-$ordinal"
    }
}
