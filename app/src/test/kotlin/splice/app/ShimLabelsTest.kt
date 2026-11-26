package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ShimLabelsTest(@param:TempDir private val dir: Path) {
    private fun form(env: Map<String, String>): List<String> {
        val script = """
            SPLICE_LAUNCH_FUNCTIONS_ONLY=1 . "${'$'}SHIM"
            read_key() { printf 'fixture'; }
            curl() {
              printf '%s\000' "${'$'}@" > "${'$'}FORM"
              printf 'splice-launch 1\nSPLICE_HTTP_STATUS=200\n'
            }
            request_launch -p --resume conversation
        """.trimIndent()
        val form = dir.resolve("form")
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).apply {
            environment().clear()
            environment().putAll(env)
            environment()["SHIM"] = requireNotNull(System.getProperty("splice.launchShim"))
            environment()["FORM"] = form.toString()
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return Files.readString(form).split('\u0000')
            .filter { it.startsWith("label=") || it.startsWith("modsOff=") }
            .sorted()
    }

    @Test
    fun `a resumed headless launch sends its labels and off list as form values`() {
        val env = mapOf(
            "SPLICE_LABEL_ROLE" to "worker",
            "SPLICE_LABEL_CAMPAIGN" to "c=1 & 2",
            "SPLICE_MODS_OFF" to "a,b",
            "OTHER_LABEL" to "not sent",
        )
        assertEquals(listOf("label=CAMPAIGN=c=1 & 2", "label=ROLE=worker", "modsOff=a,b"), form(env))
    }

    @Test
    fun `a launch without labels or an off switch sends neither`() {
        assertEquals(emptyList<String>(), form(emptyMap()))
    }
}
