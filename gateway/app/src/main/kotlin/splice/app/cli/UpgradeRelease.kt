// NEW (v0.4.0, FEATURES.md §5): fetch + verify a release EXACTLY as install.sh does — sha256 of
// every asset against the published sha256sums.txt, then GitHub build-provenance attestation via an
// authenticated gh for a remote base (a file:// base is an acceptance fixture and skips it), then
// the candidate must answer `version` like a splice jar and run its own doctor. Everything lands in
// a staging directory; a refusal at any step leaves nothing activated. Network and processes go
// through two seams so the command is tested without a socket or a gh.
package splice.app.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

internal const val SUMS_ASSET = "sha256sums.txt"
private const val REPO = "torad-labs/splice"
private const val RELEASES = "https://github.com/$REPO/releases"
private const val SHIM_MODE = "rwxr-xr-x"

/** A refusal decided before anything was activated. [reason] is text this command authored (a version, a
 *  path, a verdict), never bytes of a file it read — so it is printed as-is, not through SafeFailureText. */
internal class UpgradeRefused(val reason: String) : RuntimeException(reason)

internal class UpgradeRelease(
    private val fetch: UpgradeFetch,
    private val process: UpgradeProcess,
    private val java: String,
) {
    /** The release base: SPLICE_RELEASE_BASE_URL wins (the installer's override), else the tag or latest. */
    fun base(to: String?, override: String?): String =
        override?.trim()?.takeIf { it.isNotEmpty() }?.trimEnd('/')
            ?: if (to == null) "$RELEASES/latest/download" else "$RELEASES/download/$to"

    /** Fetch, verify and validate the release into [staging]; returns the candidate's version. */
    fun stage(base: String, staging: Path): String {
        val remote = !base.startsWith("file:")
        if (remote) requireAuthedGh()
        val sums = String(fetch("$base/$SUMS_ASSET") ?: refuse("no $SUMS_ASSET at $base"))
        Files.createDirectories(staging)
        for (asset in listOf(JAR_ASSET, SHIM_ASSET)) {
            val bytes = fetch("$base/$asset") ?: refuse("no $asset at $base")
            verifySum(asset, bytes, sums)
            val file = Files.write(staging.resolve(asset), bytes)
            if (remote) attest(file, asset)
            val how = if (remote) "sha256 ok, attestation ok" else "sha256 ok (local release base, no attestation)"
            println("  $GREEN✓$RESET ${asset.padEnd(UPGRADE_PAD)} $how")
        }
        Files.setPosixFilePermissions(staging.resolve(SHIM_ASSET), PosixFilePermissions.fromString(SHIM_MODE))
        return validate(staging.resolve(JAR_ASSET))
    }

    private fun requireAuthedGh() {
        if (process(listOf("gh", "auth", "status"), false).code != 0) {
            refuse("GitHub CLI (gh) must be installed and authenticated to verify release provenance: gh auth login")
        }
    }

    private fun verifySum(asset: String, bytes: ByteArray, sums: String) {
        val expected = sums.lineSequence().map { it.trim() }.firstOrNull { it.endsWith(" $asset") }
            ?.substringBefore(' ')
            ?: refuse("no $asset entry in $SUMS_ASSET")
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!expected.equals(actual, ignoreCase = true)) {
            refuse("sha256 verification FAILED for $asset (expected $expected, got $actual)")
        }
    }

    private fun attest(file: Path, asset: String) {
        val verify = process(listOf("gh", "attestation", "verify", file.toString(), "--repo", REPO), false)
        if (verify.code != 0) refuse("attestation verification FAILED for $asset")
    }

    /** The candidate answers `version` like a splice jar and its doctor RUNS (findings are next
     *  steps, exactly as install.sh treats them; a crash or garbage is a refusal). */
    private fun validate(jar: Path): String {
        val version = process(listOf(java, "-jar", jar.toString(), "version"), false)
        val line = version.stdout.trim()
        if (version.code != 0 || !line.startsWith("splice ")) {
            refuse("candidate jar failed validation: ${line.ifEmpty { "<empty>" }}")
        }
        val doctor = process(listOf(java, "-jar", jar.toString(), "doctor", "--json"), false)
        if (doctor.code !in 0..1 || !doctor.stdout.trimStart().startsWith("{")) {
            refuse("candidate jar's doctor did not run (exit ${doctor.code})")
        }
        return line.removePrefix("splice ").trim()
    }

    private fun refuse(reason: String): Nothing = throw UpgradeRefused(reason)
}
