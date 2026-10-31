// NEW: transport cuts are retryable worker loss; complete malformed frames remain protocol failures.
package splice.codemode

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream

internal class CodeModeWireFailureTest {
    @Test
    fun `a cut while writing a frame is worker loss`() {
        val broken = object : OutputStream() {
            override fun write(value: Int) = throw IOException("synthetic broken pipe")
        }
        assertThrows(CodeModeWorkerLostException::class.java) {
            CodeModeWire.write(DataOutputStream(broken), CodeModeWire.readyFrame())
        }
    }

    @Test
    fun `a cut before the header or inside the payload is worker loss`() {
        val partial = ByteArrayOutputStream()
        DataOutputStream(partial).writeInt(10)
        for (bytes in listOf(byteArrayOf(), partial.toByteArray())) {
            assertThrows(CodeModeWorkerLostException::class.java) {
                CodeModeWire.read(DataInputStream(ByteArrayInputStream(bytes)))
            }
        }
    }

    @Test
    fun `invalid complete lengths JSON and object shapes remain protocol failures`() {
        val malformed = listOf(frame(-1), frame(1, "{"), frame(2, "[]"))
        for (bytes in malformed) {
            val error = assertThrows(IOException::class.java) {
                CodeModeWire.read(DataInputStream(ByteArrayInputStream(bytes)))
            }
            assertFalse(error is CodeModeWorkerLostException)
        }
    }

    private fun frame(length: Int, payload: String = ""): ByteArray =
        ByteArrayOutputStream().also {
            DataOutputStream(it).use { output ->
                output.writeInt(length)
                output.write(payload.toByteArray())
            }
        }.toByteArray()
}
