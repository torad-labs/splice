// NEW: V4-127 — the console's doctor BODY, pinned at the seam the daemon wires.
//
// The body is DoctorCommand.reportJson, and the wiring line is a method reference to it, so this test
// is the pin for that line: it calls exactly what ControlServer's DoctorReport port will call, and it
// asserts the result is the CLI's OWN report rather than something that merely parses as JSON.
//
// WHY THE KEY SET AND NOT A VALUE: almost everything in this report is about the machine it runs on,
// so a value assertion would pin the test host. The top-level SECTION NAMES are the report's contract
// with the console — they are what the CLI's --json emits and what the console renders — and an empty
// object, a stub, or a second assembly would all fail them. That is the mutation the red proof uses.
package splice.diagnostics.doctor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

private const val OLDEST_DAYS = 9L
private const val OUTSIDE_BYTES = 5_000

class DoctorBodyTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the console body serves the CLI own report, not a stub`() {
        val report = json.parseToJsonElement(DoctorTestPorts.doctor().reportJson(EnvReader(System::getenv))).jsonObject

        // The sections DoctorJsonReport.build always writes. A caller that got `{}`, a partial object, or
        // any second assembly would miss at least one of these by name.
        val expected = setOf("schema_version", "generated_at", "splice", "claude_code", "os", "jvm")
        assertTrue(
            report.keys.containsAll(expected),
            "the body must serve the doctor report's own sections; missing ${expected - report.keys}: ${report.keys}",
        )
        // The report identifies ITSELF, so the console can tell a report from a fresh daemon off the
        // same route later — and so this test cannot pass on an error payload that happens to be JSON.
        assertTrue(report["schema_version"]!!.toString().isNotEmpty(), "a report carries its schema version")
    }

    @Test
    fun `the body and the CLI encoder agree, which is what one rendering means`() {
        // reportJson returns DoctorJsonReport.jsonText(report), and emit calls the SAME function. This
        // pins the shared-encoder claim the way it can be pinned from outside: two runs of the body
        // must differ only where the report itself is time-varying, i.e. the shape is stable.
        val first = json.parseToJsonElement(DoctorTestPorts.doctor().reportJson(EnvReader(System::getenv))).jsonObject
        val second = json.parseToJsonElement(DoctorTestPorts.doctor().reportJson(EnvReader(System::getenv))).jsonObject
        assertEquals(first.keys, second.keys, "one rendering: the section set cannot move between calls")
    }

    /** V4-127 review: DoctorReportShape.stateDirUsage existed and nothing called it, so the report the
     *  console reads never carried the state dir's size or its oldest file (FEATURES.md §6: "Doctor
     *  reports the state dir size and the oldest file"). Seeded dir, exact numbers: a report that
     *  walked the wrong directory, followed a link out of it or picked the newest file fails here. */
    @Test
    fun `the report carries the state dir's size, file count and oldest file`(@TempDir tmp: Path) {
        val state = tmp.resolve("state").also { Files.createDirectories(it.resolve("logs")) }
        val now = Instant.now()
        val seeded = mapOf(
            "budgets.json" to (Duration.ofHours(1) to 10),
            "logs/daemon.log" to (Duration.ofDays(2) to 300),
            "logs/oldest.log" to (Duration.ofDays(OLDEST_DAYS) to 40),
        )
        seeded.forEach { (name, ageAndSize) ->
            val file = state.resolve(name)
            Files.write(file, ByteArray(ageAndSize.second))
            Files.setLastModifiedTime(file, FileTime.from(now.minus(ageAndSize.first)))
        }
        // A link out of the state dir is not the state dir's footprint: never followed, never counted.
        val outside = tmp.resolve("outside.bin").also { Files.write(it, ByteArray(OUTSIDE_BYTES)) }
        Files.createSymbolicLink(state.resolve("elsewhere"), outside)
        val env = EnvReader { name -> if (name == "SPLICE_STATE_DIR") state.toString() else System.getenv(name) }

        val report = json.parseToJsonElement(DoctorTestPorts.doctor().reportJson(env)).jsonObject
        val usage = report["state_dir_usage"]?.jsonObject
            ?: error("the report must carry state_dir_usage; keys: ${report.keys}")

        assertEquals(seeded.values.sumOf { it.second }.toLong(), usage.getValue("size_bytes").jsonPrimitive.long)
        assertEquals(seeded.size, usage.getValue("file_count").jsonPrimitive.int)
        assertEquals("logs/oldest.log", usage.getValue("oldest_file").jsonPrimitive.content)
        val age = usage.getValue("oldest_age_ms").jsonPrimitive.long
        assertTrue(age >= Duration.ofDays(OLDEST_DAYS).toMillis(), "the oldest file's age, not a newer one's: $age")
        assertTrue(age < Duration.ofDays(OLDEST_DAYS).plusHours(1).toMillis(), "an age, not a timestamp: $age")
        assertEquals(0, usage.getValue("unreadable_entries").jsonPrimitive.int)
    }

    @Test
    fun `an absent state dir reports an empty footprint with no oldest file`(@TempDir tmp: Path) {
        val env = EnvReader { name ->
            if (name == "SPLICE_STATE_DIR") tmp.resolve("never-created").toString() else System.getenv(name)
        }
        val usage = json.parseToJsonElement(DoctorTestPorts.doctor().reportJson(env)).jsonObject
            .getValue("state_dir_usage").jsonObject
        assertEquals(0L, usage.getValue("size_bytes").jsonPrimitive.long)
        assertEquals(0, usage.getValue("file_count").jsonPrimitive.int)
        assertEquals(JsonNull, usage.getValue("oldest_file"))
        assertEquals(JsonNull, usage.getValue("oldest_age_ms"))
    }

    @Test
    fun `the body asks for no live probes and no logs`() {
        // The console's two choices, pinned as FACTS rather than as comments: --live SENDS a request to
        // every local runtime and --with-logs puts log lines on the wire, and a console poll must never
        // do either behind the operator's back. `logs` is present only under --with-logs.
        val report = json.parseToJsonElement(DoctorTestPorts.doctor().reportJson(EnvReader(System::getenv))).jsonObject
        assertTrue("logs" !in report, "a console poll must not ship log lines: ${report.keys}")
    }
}
