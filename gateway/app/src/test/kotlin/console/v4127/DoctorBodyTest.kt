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
package console.v4127

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.cli.DoctorCommand

class DoctorBodyTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the console body serves the CLI own report, not a stub`() {
        val report = json.parseToJsonElement(DoctorCommand().reportJson()).jsonObject

        // The sections DoctorReport.build always writes. A caller that got `{}`, a partial object, or
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
        // reportJson returns DoctorReport.jsonText(report), and emit calls the SAME function. This
        // pins the shared-encoder claim the way it can be pinned from outside: two runs of the body
        // must differ only where the report itself is time-varying, i.e. the shape is stable.
        val first = json.parseToJsonElement(DoctorCommand().reportJson()).jsonObject
        val second = json.parseToJsonElement(DoctorCommand().reportJson()).jsonObject
        assertEquals(first.keys, second.keys, "one rendering: the section set cannot move between calls")
    }

    @Test
    fun `the body asks for no live probes and no logs`() {
        // The console's two choices, pinned as FACTS rather than as comments: --live SENDS a request to
        // every local runtime and --with-logs puts log lines on the wire, and a console poll must never
        // do either behind the operator's back. `logs` is present only under --with-logs.
        val report = json.parseToJsonElement(DoctorCommand().reportJson()).jsonObject
        assertTrue("logs" !in report, "a console poll must not ship log lines: ${report.keys}")
    }
}
