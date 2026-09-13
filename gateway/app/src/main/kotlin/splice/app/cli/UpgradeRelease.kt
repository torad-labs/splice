// NEW (v0.4.0, FEATURES.md §5): fetch + verify a release EXACTLY as install.sh does — sha256 of
// every asset against the published sha256sums.txt, then GitHub build-provenance attestation via an
// authenticated gh for a remote base (a file:// base is an acceptance fixture and skips it), then
// the candidate must answer `version` like a splice jar and run its own doctor. Everything lands in
// a staging directory; a refusal at any step leaves nothing activated. Network and processes go
// through two seams so the command is tested without a socket or a gh.
package splice.app.cli

import splice.core.util.Cancellables
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration

internal const val SUMS_ASSET = "sha256sums.txt"
private const val REPO = "torad-labs/splice"
private const val RELEASES = "https://github.com/$REPO/releases"
private const val HTTP_OK = 200
private const val FETCH_TIMEOUT_S = 300L
private const val NO_SUCH_COMMAND = 127
private const val SHIM_MODE = "rwxr-xr-x"

internal data class UpgradeExit(val code: Int, val stdout: String)

/** Run a command; [inherit] streams its output to the operator instead of capturing it. */
internal fun interface UpgradeProcess {
    operator fun invoke(command: List<String>, inherit: Boolean): UpgradeExit
}

/** GET a URL (https or file://), or null when nothing answers. */
internal fun interface UpgradeFetch {
    operator fun invoke(url: String): ByteArray?
}

internal class JdkUpgradeProcess : UpgradeProcess {
    override fun invoke(command: List<String>, inherit: Boolean): UpgradeExit = try {
        val builder = ProcessBuilder(command).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.INHERIT)
        if (inherit) builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        val process = builder.start()
        val out = if (inherit) "" else process.inputStream.bufferedReader().readText()
        UpgradeExit(process.waitFor(), out)
    } catch (e: IOException) {
        UpgradeExit(NO_SUCH_COMMAND, e.message.orEmpty())
    }
}

internal class JdkUpgradeFetch(
    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(),
) : UpgradeFetch {
    override fun invoke(url: String): ByteArray? = Cancellables.runCatchingCancellable {
        if (url.startsWith("file:")) {
            Files.readAllBytes(Paths.get(URI(url)))
        } else {
            val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(FETCH_TIMEOUT_S)).GET().build()
            val reply = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            reply.body().takeIf { reply.statusCode() == HTTP_OK }
        }
    }.getOrNull()
}

/** A refusal decided before anything was activated; its message is the whole explanation. */
internal class UpgradeRefused(message: String) : RuntimeException(message)

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
