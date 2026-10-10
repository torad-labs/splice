// NEW: v0.4.0 FEATURES.md §5 — fetch + verify a release EXACTLY as install.sh does — sha256 of
// every asset against the published sha256sums.txt, then GitHub build-provenance attestation for a
// remote base whenever gh is installed and signed in (a file:// base is an acceptance fixture and
// skips it; without a signed-in gh the stage proceeds on the sha256 match and says how to verify
// later, V4-217), then the candidate must answer `version` like a splice jar and run its own
// doctor. Everything lands in a staging directory; a refusal at any step leaves nothing activated.
// Network and processes go through two seams so the command is tested without a socket or a gh.
package splice.lifecycle.upgrade

import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

internal const val SUMS_ASSET = "sha256sums.txt"

// why: the GitHub owner/name slug releases and attestations are fetched from. Named GITHUB_REPO
// rather than the bare noun: a different value of the same bare name lives in
// McpDispositionReasons, and const-single-source matches bare names across namespaces because
// one name carrying two meanings is a reading hazard whether or not the compiler minds.
private const val GITHUB_REPO = "torad-labs/splice"
private const val RELEASES = "https://github.com/$GITHUB_REPO/releases"
private const val SHIM_MODE = "rwxr-xr-x"

/** The exit code of the candidate jar's `check-config` when its boot findings refuse the person's splice.toml: a wire contract with another process, read here and declared there. */
private const val CANDIDATE_CONFIG_REFUSAL_EXIT = 3

private const val NO_ANSWER = "no answer"

/** `doctor --json` shipped in 0.4.0. */
private const val JSON_DOCTOR_MINOR = 4

/** A validated candidate. [provenanceGap] says why its attestation was not checked ("is not
 *  installed", "is not signed in"), or is null when it was checked or the base is a local mirror. */
internal data class Staged(val version: String, val provenanceGap: String?)

internal class UpgradeRelease(
    private val output: TerminalOutput,
    private val fetch: UpgradeFetch,
    private val process: UpgradeProcess,
    private val java: String,
) {
    /** The release base: SPLICE_RELEASE_BASE_URL wins (the installer's override), else the tag or latest.
     *  Tags are `v<version>` (release.yml); `--to 0.4.0` and `--to v0.4.0` name the same one. */
    fun base(to: String?, override: String?): String =
        override?.trim()?.takeIf { it.isNotEmpty() }?.trimEnd('/')
            ?: if (to == null) "$RELEASES/latest/download" else "$RELEASES/download/v${to.removePrefix("v")}"

    /** Fetch, verify and validate the release into [staging]. */
    fun stage(base: String, staging: Path): Upgraded<Staged> {
        val remote = !base.startsWith("file:")
        val gap = if (remote) attestationGap() else null
        return fetched(base, SUMS_ASSET).then { sums ->
            Files.createDirectories(staging)
            stageAssets(Fetching(base, staging, String(sums), remote, gap)).then {
                Files.setPosixFilePermissions(staging.resolve(SHIM_ASSET), PosixFilePermissions.fromString(SHIM_MODE))
                validate(staging.resolve(JAR_ASSET)).then { version -> Upgraded.Ok(Staged(version, gap)) }
            }
        }
    }

    /** What one stage reads and decides: where from, where to, the published sums, and whether provenance applies. */
    private data class Fetching(
        val base: String,
        val staging: Path,
        val sums: String,
        val remote: Boolean,
        val gap: String?,
    )

    private fun stageAssets(fetching: Fetching): Upgraded<Unit> {
        for (asset in listOf(JAR_ASSET, SHIM_ASSET)) {
            val refused = stageAsset(fetching, asset)
            if (refused is Upgraded.Refused) return refused
        }
        return Upgraded.Ok(Unit)
    }

    private fun stageAsset(fetching: Fetching, asset: String): Upgraded<Unit> =
        fetched(fetching.base, asset).then { bytes ->
            verifySum(asset, bytes, fetching.sums).then {
                val file = Files.write(fetching.staging.resolve(asset), bytes)
                val attested = if (fetching.remote && fetching.gap == null) attest(file, asset) else Upgraded.Ok(Unit)
                attested.then {
                    val how = when {
                        !fetching.remote -> "sha256 ok (local release base, no attestation)"
                        fetching.gap == null -> "sha256 ok, attestation ok"
                        else -> "sha256 ok, provenance not checked (gh ${fetching.gap})"
                    }
                    output.line("  $GREEN✓$RESET ${asset.padEnd(UPGRADE_PAD)} $how")
                    Upgraded.Ok(Unit)
                }
            }
        }

    /** The command that checks an installed asset's build provenance, for a stage that could not. */
    fun verifyLater(file: Path): String = "gh attestation verify $file --repo $GITHUB_REPO"

    /** Absent (null) and failed (a status class, a transport class) are different refusals: a 403
     *  or a DNS failure is not "no asset", and the operator's next step differs (review 2026-09-14). */
    private fun fetched(base: String, asset: String): Upgraded<ByteArray> = try {
        fetch("$base/$asset")?.let { Upgraded.Ok(it) } ?: Upgraded.Refused("no $asset at $base")
    } catch (failed: UpgradeFetchFailed) {
        Upgraded.Refused("fetching $asset from $base failed: ${failed.why}")
    }

    /** Null when gh can verify an attestation, else why not. An attestation that gh checks and
     *  rejects is still a refusal ([attest]); only the absence of a verifier is let through. */
    private fun attestationGap(): String? = when (process(listOf("gh", "auth", "status"), false).code) {
        0 -> null
        NO_SUCH_COMMAND -> "is not installed"
        TIMED_OUT -> "did not answer `gh auth status`"
        else -> "is not signed in"
    }

    private fun verifySum(asset: String, bytes: ByteArray, sums: String): Upgraded<Unit> {
        val expected = sums.lineSequence().map { it.trim() }.firstOrNull { it.endsWith(" $asset") }
            ?.substringBefore(' ')
            ?: return Upgraded.Refused("no $asset entry in $SUMS_ASSET")
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return if (expected.equals(actual, ignoreCase = true)) {
            Upgraded.Ok(Unit)
        } else {
            Upgraded.Refused("sha256 verification FAILED for $asset (expected $expected, got $actual)")
        }
    }

    private fun attest(file: Path, asset: String): Upgraded<Unit> {
        val command = listOf("gh", "attestation", "verify", file.toString(), "--repo", GITHUB_REPO)
        return if (process(command, false).code == 0) {
            Upgraded.Ok(Unit)
        } else {
            Upgraded.Refused("attestation verification FAILED for $asset")
        }
    }

    /** The candidate answers `version` like a splice jar and its doctor RUNS (findings are next
     *  steps, exactly as install.sh treats them; a crash or garbage is a refusal). A candidate older
     *  than 0.4.0 has no `--json`: its verb table ignored the flag and ran the full text doctor —
     *  write probes and daemon calls against the live install — only to be refused for not printing
     *  JSON (review 2026-09-14). Such a candidate skips the preflight, and says so. */
    private fun validate(jar: Path): Upgraded<String> {
        val version = process(listOf(java, "-jar", jar.toString(), "version"), false)
        val line = version.stdout.trim()
        if (version.code != 0 || !line.startsWith("splice ")) {
            return Upgraded.Refused("candidate jar failed validation: ${line.ifEmpty { "<empty>" }}")
        }
        val candidate = line.removePrefix("splice ").trim()
        val refusal = if (predatesJsonDoctor(candidate)) {
            output.line("  ${"doctor".padEnd(UPGRADE_PAD)} $candidate predates doctor --json; preflight skipped")
            null
        } else {
            doctorRefusal(jar) ?: configRefusal(jar, candidate)
        }
        return refusal ?: Upgraded.Ok(candidate)
    }

    /** The refusal when the candidate's `doctor --json` does not answer with a report; null when it does. */
    private fun doctorRefusal(jar: Path): Upgraded.Refused? {
        val doctor = process(listOf(java, "-jar", jar.toString(), "doctor", "--json"), false)
        val answered = doctor.code in 0..1 && doctor.stdout.trimStart().startsWith("{")
        if (answered) return null
        return Upgraded.Refused("candidate jar's doctor --json did not answer with a report (exit ${doctor.code})")
    }

    /** Fail-closed boot, before anything is activated or stopped: the release's own `check-config` reads the person's
     *  splice.toml with the findings that release will boot with. ONLY exit 0 permits the replacement. Exit 3 is its
     *  refusal, with every finding on stdout; any other answer (an older jar's unknown verb, a crash, a timeout, a java
     *  that would not start) is no verdict, and no verdict keeps the running daemon exactly as a refusal does, because
     *  the daemon that is serving is worth more than an unchecked swap. A release that predates the config
     *  findings has no such verb at all; it is skipped with a line saying so, as the doctor preflight is. */
    fun configRefusal(jar: Path, version: String): Upgraded.Refused? {
        if (predatesJsonDoctor(version)) return null
        val check = process(listOf(java, "-jar", jar.toString(), "check-config"), false)
        return when (check.code) {
            0 -> null
            CANDIDATE_CONFIG_REFUSAL_EXIT -> Upgraded.Refused(
                "$version would refuse to boot on your splice.toml, so the running daemon was not touched:\n" +
                    check.stdout.trim(),
            )
            else -> Upgraded.Refused(
                "$version could not check your splice.toml (exit ${check.code}: " +
                    "${check.stdout.trim().ifEmpty { NO_ANSWER }}), so the running daemon was not touched; " +
                    "nothing is replaced until the check can run",
            )
        }
    }

    private fun predatesJsonDoctor(version: String): Boolean {
        val parts = version.substringBefore('-').substringBefore('+').split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return major == 0 && minor < JSON_DOCTOR_MINOR
    }
}
