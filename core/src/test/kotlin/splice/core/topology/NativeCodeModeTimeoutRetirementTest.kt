package splice.core.topology

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeCodeModeTimeoutRetirementTest {
    @Test
    fun `an ordinary provider configuration keeps the absent timeout setting valid`() {
        assertNull(Json.decodeFromString<QuirksConfig>("{}").codeModeTimeoutMs)
    }

    @Test
    fun `a retained legacy timeout reports its key and the native observation migration`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString<QuirksConfig>("""{"code_mode_timeout_ms":5000}""")
            Unit
        }
        val message = failure.message.orEmpty()
        assertTrue(message.contains("code_mode_timeout_ms"), message)
        assertTrue(message.contains("remove the key"), message)
        assertTrue(message.contains("yield_time_ms"), message)
        assertTrue(message.contains("never cancel"), message)
    }
}
