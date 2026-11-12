package splice.app.diagnostics

import java.io.OutputStream

/** JSONL output belongs to this diagnostic process, independently of daemon logs. */
internal object CodeModeParityText {
    fun write(output: OutputStream, line: String) {
        output.write((line + "\n").encodeToByteArray())
        output.flush()
    }
}
