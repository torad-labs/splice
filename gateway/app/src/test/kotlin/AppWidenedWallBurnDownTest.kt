// NEW: V4-112 — the two BEHAVIOUR changes the widened-wall burn-down in :app makes, pinned so they
// cannot silently revert. Everything else in that row is a routing change (a failure that was
// dropped now reaches a log lane) or a reviewed exemption; these two change what the operator is
// told.
//   1. kt-json-scalars-single-source: `(x as? JsonPrimitive)?.content` reads a JSON `null` back as
//      the four characters n-u-l-l, so an endpoint whose /models row carries {"id": null} used to
//      publish a model literally named "null" into `splice add`'s listed-model check. JsonScalars
//      filters JsonNull, so that row is now absent instead of fictional.
//   2. kt-no-silent-result-collapse: doctor's `gh auth status` probe collapsed a spawn failure into
//      `false`, i.e. into "installed but not authenticated — gh auth login". A probe that could not
//      RUN is a different fact, and prescribing a login for an exec failure is the 2026-07-18
//      misdiagnosis shape this wall exists for.
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TopologyLoader
import splice.app.cli.AddChecks
import splice.app.cli.AddCommand
import splice.app.cli.AddHttp
import splice.app.cli.AddHttpReply
import splice.app.cli.CheckStatus
import splice.app.cli.DoctorInstallProbes
import splice.app.cli.DoctorProbes
import splice.core.util.EnvReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private const val HTTP_OK = 200

class AppWidenedWallBurnDownTest {

    private val env = EnvReader { name -> if (name == "FW_API_KEY") "k" else null }

    private fun <T> withHome(home: Path, block: () -> T): T {
        val previous = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        return try {
            block()
        } finally {
            System.setProperty("user.home", previous)
        }
    }

    private fun capture(block: () -> Unit): String {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    private fun command(routes: Map<String, String>): AddCommand {
        val http = AddHttp { method, url, _, _ -> routes["$method $url"]?.let { AddHttpReply(HTTP_OK, it) } }
        return AddCommand(
            checks = AddChecks(http),
            login = { _, _, _ -> true },
            install = { _, _ -> true },
            restart = { true },
            daemonUp = { false },
            prompt = { _, default -> default },
        )
    }

    @Test
    fun `a models row whose id is JSON null is absent, not a model called null`(@TempDir home: Path) =
        withHome(home) {
            val config = TopologyLoader.configPath(env)
            TopologyLoader.loadOrMaterialize(config)
            val before = Files.readString(config)
            val routes = mapOf(
                "GET http://localhost:1/v1" to "{}",
                "GET http://localhost:1/v1/models" to """{"data":[{"id":null},{"id":"other"}]}""",
            )
            val args = listOf(
                "api-key",
                "--name",
                "fw",
                "--base-url",
                "http://localhost:1/v1",
                "--model",
                "m:1000",
                "--yes",
            )
            val printed = capture {
                assertFalse(
                    runBlocking { command(routes).add(args, env) },
                    "the requested model is not listed, so nothing is written",
                )
            }
            // The endpoint's list is echoed on the refusal. Before JsonScalars it read `[null, other]`.
            assertTrue(printed.contains("it lists [other]"), printed)
            assertFalse(printed.contains("it lists [null"), printed)
            assertEquals(before, Files.readString(config))
        }

    @Test
    fun `a gh whose auth probe cannot even run is not reported as unauthenticated`(@TempDir tmp: Path) {
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val gh = bin.resolve("gh")
        // A shebang naming an interpreter that does not exist: binaryOnPath still FINDS it (the x
        // bit is set), and execve fails with ENOENT before any interpreter runs — a spawn failure
        // (start() throws), not a non-zero exit from a real gh. An execute-only script does NOT
        // produce this shape on Linux: the kernel still reads the shebang, /bin/sh starts, then
        // exits non-zero when it cannot read the body — which the code correctly renders as
        // "not authenticated", so that shape cannot be the pin for this branch.
        Files.writeString(gh, "#!/nonexistent/interpreter/splice-test\n")
        Files.setPosixFilePermissions(gh, PosixFilePermissions.fromString("rwx------"))
        val pathEnv = EnvReader { name -> if (name == "PATH") bin.toString() else null }
        val check = DoctorInstallProbes(DoctorProbes()).ghCheck(pathEnv)
        assertEquals(CheckStatus.WARN, check.status)
        assertTrue(check.detail.contains("could not be run"), check.detail)
        assertFalse(check.detail.contains("not authenticated"), check.detail)
    }
}
