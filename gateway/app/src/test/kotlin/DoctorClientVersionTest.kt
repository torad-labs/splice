import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.app.cli.CheckStatus
import splice.app.cli.DoctorClientVersion
import splice.app.cli.HealthView

class DoctorClientVersionTest {
    @Test
    fun `doctor projects one aggregate warning from daemon health`() {
        val warning =
            "Claude Code 2.1.258 is newer than the version splice 0.3.2 was tested with (2.1.257)"
        val check = DoctorClientVersion().check(health(warning))

        assertEquals("Claude Code", check?.name)
        assertEquals(CheckStatus.WARN, check?.status)
        assertEquals(warning, check?.detail)
    }

    @Test
    fun `doctor stays silent when daemon health has no version warning`() {
        assertNull(DoctorClientVersion().check(health(null)))
        assertNull(DoctorClientVersion().check(null))
    }

    private fun health(warning: String?) = HealthView(
        version = "0.3.2",
        heads = 1,
        readyHeads = 1,
        failedHeads = 0,
        clientVersionWarning = warning,
    )
}
