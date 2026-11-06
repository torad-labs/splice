package splice.diagnostics.doctor

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DoctorPerfRegistryProjectionTest {
    @Test
    fun `new registered numeric metrics survive support projection while content and nonfinite values do not`(
        @TempDir directory: Path,
    ) {
        val fixture = DoctorReportFixture(directory)
        val env = fixture.plant()
        Files.writeString(
            directory.resolve("state/codex-perf.jsonl"),
            """{"ts":10,"outcome":"ok","arrival_to_terminal_ms":12.5,"tool_duration_ms":1e999,""" +
                """"prompt":"private text","unregistered_counter":123}""" + "\n",
        )
        val row = fixture.report(
            env,
            false,
        ).getValue("perf").jsonObject.getValue("codex").jsonObject.getValue("rows").jsonArray.single().jsonObject
        assertEquals("12.5", row["arrival_to_terminal_ms"].toString())
        assertFalse("tool_duration_ms" in row)
        assertFalse("unregistered_counter" in row)
        assertFalse(row.toString().contains("private text"))
    }
}
