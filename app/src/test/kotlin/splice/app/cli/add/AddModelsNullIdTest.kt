// NEW: V4-112 — one of the two BEHAVIOUR changes the widened-wall burn-down in :app made, pinned so
// it cannot silently revert (the doctor half lives in cli/doctor/DoctorAuthProbeFailureTest.kt).
//   kt-json-scalars-single-source: `(x as? JsonPrimitive)?.content` reads a JSON `null` back as the
//   four characters n-u-l-l, so an endpoint whose /models row carries {"id": null} used to publish a
//   model literally named "null" into `splice add`'s listed-model check. JsonScalars filters JsonNull,
//   so that row is now absent instead of fictional.
package splice.app.cli.add

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import splice.topology.TopologyLoader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

private const val HTTP_OK = 200

class AddModelsNullIdTest {

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
}
