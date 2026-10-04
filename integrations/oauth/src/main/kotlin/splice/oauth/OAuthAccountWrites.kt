// NEW: v0.4.0 FEATURES.md §11 — secure account writes and token-derived collision allocation.
// Split from OAuthAccountFiles: discovery/planning do not own persistence or destination retries.
package splice.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import splice.core.topology.AuthKind
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal class OAuthAccountWrites(private val json: Json, private val validation: OAuthAccountValidation) {
    private val archiveStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    fun decorated(kind: AuthKind.OAuth, label: String, providerJson: JsonObject): JsonObject {
        validation.requireLabel(label)
        return buildJsonObject {
            providerJson.forEach { (key, value) -> put(key, value) }
            put(FIELD_KIND, JsonPrimitive(kind.wire))
            put(FIELD_LABEL, JsonPrimitive(label))
        }
    }

    fun writeLabeled(kind: AuthKind.OAuth, dir: Path, label: String, providerJson: JsonObject): Path =
        writeLabeledRenewal(kind, dir, label, providerJson).file

    /** A quota without a regular credential has no owner proof. Move its own entry aside before
     *  writing: the new identity reads no old usage, including when the orphan was a symlink. */
    fun writeLabeledRenewal(
        kind: AuthKind.OAuth,
        dir: Path,
        label: String,
        providerJson: JsonObject,
    ): OAuthAccountWrite {
        validation.requireLabel(label)
        Files.createDirectories(dir)
        val target = dir.resolve("$label.json")
        val orphan = retainedQuota(dir, label)
        if (orphan != null && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw OAuthAccountRefused(
                "linked credential cannot claim retained usage for '$label'; " +
                    "sign in as '${unusedLabel(dir, label)}' instead",
            )
        }
        val archive = orphan?.let { quota ->
            val stamped = "$label-quota.json.orphaned-${archiveStamp.format(Instant.now())}"
            val destination = generateSequence(1, Int::inc).map { ordinal ->
                dir.resolve(if (ordinal == 1) stamped else "$stamped-$ordinal")
            }.first { !Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            Files.move(quota, destination, LinkOption.NOFOLLOW_LINKS)
        }
        // CREDENTIAL-WRITE-EXEMPT[2026-09-26]: splice's own labeled account file in the pool dir OAuthAccountFiles.poolDir names (<parent>/<kind>/<primary name>/<label>.json), which no vendor CLI reads or writes; a sign-in under a label is that identity's whole record, so there is nothing foreign on disk to keep (V4-298).
        SecureFile.writeAtomic0600(
            target,
            json.encodeToString(JsonObject.serializer(), decorated(kind, label, providerJson)),
        )
        return OAuthAccountWrite(target, setAsideQuota = archive)
    }

    /** A token-derived label is unknowable before exchange. Reuse a prior repair for the same
     *  identity, or choose a suffix beside orphaned quota. These are occupancy checks, not a
     *  cross-process reservation; secure atomic writes retain their existing concurrency contract. */
    fun writeTokenDerived(
        kind: AuthKind.OAuth,
        dir: Path,
        label: String,
        providerJson: JsonObject,
        identity: OAuthAccountIdentity? = null,
    ): OAuthAccountWrite {
        validation.requireLabel(label)
        val retainedQuota = retainedQuota(dir, label)
        val match = identity?.invoke(providerJson)?.let { ExistingIdentity(kind, identity, it, json) }
        val destination = reusableLabel(dir, label, match) ?: if (retainedQuota == null) {
            label
        } else {
            unusedLabel(dir, label)
        }
        return OAuthAccountWrite(writeLabeled(kind, dir, destination, providerJson), retainedQuota)
    }

    /** Both entries are checked without following links. A linked credential cannot claim the
     *  quota at this label belongs to it; only an in-place regular credential may re-login. */
    fun retainedQuota(dir: Path, label: String): Path? {
        val credentialPresent = Files.isRegularFile(dir.resolve("$label.json"), LinkOption.NOFOLLOW_LINKS)
        val quota = dir.resolve("$label-quota.json")
        return quota.takeIf { !credentialPresent && Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
    }

    private fun reusableLabel(dir: Path, base: String, match: ExistingIdentity?): String? {
        if (match == null || !Files.isDirectory(dir)) return null
        return Files.list(dir).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { it.fileName.toString().endsWith(".json") }
                .map { it.fileName.toString().removeSuffix(".json") }
                .filter { candidate ->
                    val ordinal = candidate.substringAfterLast('-').toIntOrNull()
                    ordinal != null && ordinal >= 2 && candidate == suffixedLabel(base, ordinal)
                }
                .sorted()
                .filter { candidate -> match.matches(dir.resolve("$candidate.json"), candidate) }
                .findFirst().orElse(null)
        }
    }

    private fun suffixedLabel(label: String, ordinal: Int): String {
        val suffix = "-$ordinal"
        return label.take(MAX_LABEL_CHARS - suffix.length) + suffix
    }

    private fun unusedLabel(dir: Path, label: String): String = generateSequence(2, Int::inc)
        .map { ordinal -> suffixedLabel(label, ordinal) }
        .first { candidate ->
            !Files.exists(dir.resolve("$candidate.json"), LinkOption.NOFOLLOW_LINKS) &&
                !Files.exists(dir.resolve("$candidate-quota.json"), LinkOption.NOFOLLOW_LINKS)
        }
}

/** Matching never prints provider identity or accepts a mislabeled credential as an existing repair. */
private class ExistingIdentity(
    private val kind: AuthKind.OAuth,
    private val identity: OAuthAccountIdentity,
    private val expected: String,
    private val json: Json,
) {
    fun matches(file: Path, label: String): Boolean {
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): a candidate file that does not parse cannot carry a matching identity, so false is the complete answer; this matcher is contractually silent (it must never print provider identity).
        val saved = Cancellables.runCatchingCancellable {
            json.parseToJsonElement(Files.readString(file)) as? JsonObject
        }.getOrNull() ?: return false
        return JsonScalars.str(saved, FIELD_KIND) == kind.wire &&
            JsonScalars.str(saved, FIELD_LABEL) == label && identity(saved) == expected
    }
}
