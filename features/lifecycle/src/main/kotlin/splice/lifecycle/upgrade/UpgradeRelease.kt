// NEW: v0.4.0 FEATURES.md §5 — fetch + verify a release EXACTLY as install.sh does — sha256 of
// every asset against the published sha256sums.txt, then GitHub build-provenance attestation for a
// remote base whenever gh is installed and signed in (a file:// base is an acceptance fixture and
// skips it; without a signed-in gh the stage proceeds on the sha256 match and says how to verify
// later, V4-217), then the candidate must answer `version` like a splice jar and run its own
// doctor. Everything lands in a staging directory; a refusal at any step leaves nothing activated.
// Oct 13: on Linux x86-64, the MCP hub's holder and front too, when the sums list both (UpgradeHub).
// Nov 10: each asset streams into staging, hashed on the way, and lands only on its published sum (UpgradeTransfer).
// Network and processes go through two seams so the command is tested without a socket or a gh.
package splice.lifecycle.upgrade

import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.terminal.YELLOW
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

internal const val SUMS_ASSET = "sha256sums.txt"

// why: the GitHub owner/name slug the release workflow attests builds under. Named GITHUB_REPO
// rather than the bare noun: a different value of the same bare name lives in
// McpDispositionReasons, and const-single-source matches bare names across namespaces because
// one name carrying two meanings is a reading hazard whether or not the compiler minds.
private const val GITHUB_REPO = "torad-labs/splice"

/** Where releases are downloaded from since Oct 31, 2026 (Marcos: the release download belongs at Cloudflare, not
 *  GitHub): /latest/ follows the newest stable release, /v<version>/ holds each release, /releases.json lists them. */
internal const val RELEASE_SITE = "https://splice.torad.ai/download"
private const val SHIM_MODE = "rwxr-xr-x"

/** The exit code of the candidate jar's `check-config` when its boot findings refuse the person's splice.toml: a wire contract with another process, read here and declared there. */
private const val CANDIDATE_CONFIG_REFUSAL_EXIT = 3

private const val NO_ANSWER = "no answer"

/** `doctor --json` shipped in 0.4.0. */
private const val JSON_DOCTOR_MINOR = 4

/** A release file by the name it is published under and the name it takes in staging. Only the jar has two: a
 *  platform's own jar lands as the one jar every later step reads. */
private data class Published(val name: String, val lands: String = name)

/** A validated candidate. [provenanceGap] says why its attestation was not checked ("is not
 *  installed", "is not signed in"), or is null when it was checked or the base is a local mirror. */
internal data class Staged(val version: String, val provenanceGap: String?)

internal class UpgradeRelease(
    private val output: TerminalOutput,
    private val fetch: UpgradeFetch,
    private val process: UpgradeProcess,
    private val java: String,
    private val platform: PlatformJarName = PlatformJarName(),
) {
    /** The line of the published launch shim that names its release; the workflow tags the release from the same line. */
    private val launchVersion = Regex("SPLICE_GATEWAY_VERSION=\"(\\d+\\.\\d+\\.\\d+[-+.0-9A-Za-z]*)\"")

    /** The release base: SPLICE_RELEASE_BASE_URL wins (the installer's override), else the tag or latest.
     *  Tags are `v<version>` (release.yml); `--to 0.4.0` and `--to v0.4.0` name the same one. */
    fun base(to: String?, override: String?): String =
        override?.trim()?.takeIf { it.isNotEmpty() }?.trimEnd('/')
            ?: if (to == null) "$RELEASE_SITE/latest" else "$RELEASE_SITE/v${to.removePrefix("v")}"

    /** Fetch, verify and validate the release into [staging]. [attest] false is the daemon updater (UpgradeAuto): a beta
     *  built while Actions is locked has no attestation, so that run checks every asset against the release sums alone,
     *  whether or not gh is signed in, and says so per asset (splice-lead, Oct 13). */
    fun stage(base: String, staging: Path, attest: Boolean = true): Upgraded<Staged> {
        val remote = !base.startsWith("file:")
        val gap = if (remote && attest) attestationGap() else null
        return fetched(base, SUMS_ASSET).then { sums ->
            Files.createDirectories(staging)
            val fetching = Fetching(base, staging, String(sums), remote, gap, attest)
            stageAssets(fetching).then {
                listOf(SHIM_ASSET, HUB_HOLDER, HUB_FRONT).map(staging::resolve).filter { Files.exists(it) }
                    .forEach { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString(SHIM_MODE)) }
                validate(staging.resolve(JAR_ASSET)).then { version -> Upgraded.Ok(Staged(version, gap)) }
            }
        }
    }

    /** The newest published version, learned the way a stage learns anything: the published sums, then the launch
     *  shim checked against them (and attested when gh can), whose SPLICE_GATEWAY_VERSION line the release workflow
     *  derives its tag from. No jar is run and nothing is activated; [staging] holds one small file the caller deletes. */
    fun latest(base: String, staging: Path): Upgraded<String> {
        val remote = !base.startsWith("file:")
        val gap = if (remote) attestationGap() else null
        return fetched(base, SUMS_ASSET).then { sums ->
            Files.createDirectories(staging)
            stageAsset(Fetching(base, staging, String(sums), remote, gap), Published(SHIM_ASSET)).then {
                val version = Files.readAllLines(staging.resolve(SHIM_ASSET))
                    .firstNotNullOfOrNull { launchVersion.matchEntire(it)?.groupValues?.get(1) }
                version?.let { Upgraded.Ok(it) } ?: Upgraded.Refused("no version line in the published $SHIM_ASSET")
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
        val attest: Boolean = true,
    ) {
        /** A remote asset is attested when the run asks for it and gh can check it. */
        private val asked: Boolean get() = remote && attest
        val attests: Boolean get() = asked && gap == null

        /** Whether the release's sums list [name]. */
        fun lists(name: String): Boolean = sums.lineSequence().any { it.trim().endsWith(" $name") }
    }

    /** The jar and shim, then the MCP hub's holder and front the way install.sh gets them: on Linux x86-64 only,
     *  and only when the release's sums list both, each checked against them and attested like the jar. A release
     *  without the pair stages neither, and the installed pair stays as it is (UpgradeHub).
     *
     *  THE JAR IS THIS PLATFORM'S OWN WHEN THE RELEASE LISTS ONE (Nov 12, 2026): about 210 MB where splice.jar, which
     *  holds the code-mode isolate of all four platforms, is about 476 MB. A release before that, and a platform no
     *  jar is cut for, take splice.jar as every upgrade did. */
    private fun stageAssets(fetching: Fetching): Upgraded<Unit> {
        val hub = listOf(HUB_HOLDER, HUB_FRONT)
        val os = System.getProperty("os.name").orEmpty()
        val arch = System.getProperty("os.arch").orEmpty()
        val onPlatform = os == "Linux" && arch in setOf("amd64", "x86_64")
        val listed = hub.all(fetching::lists)
        if (onPlatform && !listed) {
            val label = "mcp hub".padEnd(UPGRADE_PAD)
            output.line("  $YELLOW!$RESET $label not in this release; the installed pair stays as it is")
        }
        val jar = Published(platform.jar()?.takeIf(fetching::lists) ?: JAR_ASSET, lands = JAR_ASSET)
        val rest = listOf(SHIM_ASSET) + hub.takeIf { onPlatform && listed }.orEmpty()
        for (asset in listOf(jar) + rest.map { Published(it) }) {
            val refused = stageAsset(fetching, asset)
            if (refused is Upgraded.Refused) return refused
        }
        return Upgraded.Ok(Unit)
    }

    private fun stageAsset(fetching: Fetching, published: Published): Upgraded<Unit> =
        downloaded(fetching, published).then { file ->
            val asset = published.name
            val attested = if (fetching.attests) {
                attest(file, asset)
            } else {
                Upgraded.Ok(Unit)
            }
            attested.then {
                val how = when {
                    !fetching.remote -> "sha256 ok (local release base, no attestation)"
                    !fetching.attest -> "sha256 ok (auto-update checks the release sums only)"
                    fetching.gap == null -> "sha256 ok, attestation ok"
                    else -> "sha256 ok, provenance not checked (gh ${fetching.gap})"
                }
                output.line("  $GREEN✓$RESET ${asset.padEnd(UPGRADE_PAD)} $how")
                Upgraded.Ok(Unit)
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

    /** The asset's published sum, then the asset in staging, hashed as it streamed in: bytes whose sha256 is not that
     *  sum never land there. Absent and failed are different refusals, as for [fetched]. */
    private fun downloaded(fetching: Fetching, published: Published): Upgraded<Path> {
        val asset = published.name
        val expected = fetching.sums.lineSequence().map { it.trim() }.firstOrNull { it.endsWith(" $asset") }
            ?.substringBefore(' ')
            ?: return Upgraded.Refused("no $asset entry in $SUMS_ASSET")
        val target = fetching.staging.resolve(published.lands)
        return try {
            when (val got = fetch.download("${fetching.base}/$asset", target, expected, ProgressLines(output, asset))) {
                Downloaded.Landed -> Upgraded.Ok(target)
                Downloaded.Absent -> Upgraded.Refused("no $asset at ${fetching.base}")
                is Downloaded.Mismatch ->
                    Upgraded.Refused("sha256 verification FAILED for $asset (expected $expected, got ${got.actual})")
            }
        } catch (failed: UpgradeFetchFailed) {
            Upgraded.Refused("fetching $asset from ${fetching.base} failed: ${failed.why}")
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
     *  findings has no such verb at all. A candidate that old never gets here (validate skips its preflight with a
     *  line); a rollback to one does, and is told its config is unchecked, then goes on: rollback is the emergency
     *  door. */
    fun configRefusal(jar: Path, version: String): Upgraded.Refused? {
        if (predatesJsonDoctor(version)) {
            val label = "config".padEnd(UPGRADE_PAD)
            output.line("  $YELLOW!$RESET $label $version has no check-config, so it can't check this splice.toml;")
            output.line("  ${"".padEnd(UPGRADE_PAD)}   a 0.4 config may stop it from booting")
            return null
        }
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
