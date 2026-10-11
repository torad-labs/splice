// v0.4.0 FEATURES.md §2: the daemon's aggregate Claude Code drift warning reaches doctor as one WARN row, and a
// daemon that says nothing (client older, equal, or no client seen) leaves doctor silent about it.
package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.daemonclient.DaemonProbe

private const val WARNING = "Claude Code 9.9.9 is newer than the version splice 0.4.0 was tested with (2.1.289)"

class DoctorClientVersionTest {

    private fun health(warning: String?) = DaemonProbe.HealthView(
        version = "0.4.0",
        heads = DaemonProbe.HealthHeads(total = 1, ready = 1, failed = 0),
        clientVersionWarning = warning,
    )

    @Test
    fun `a newer client is one warning row carrying the daemon's wording`() {
        val row = DoctorClientVersion().check(health(WARNING))

        assertEquals(CheckStatus.WARN, row?.status, row.toString())
        assertEquals(WARNING, row?.detail)
    }

    @Test
    fun `an older or equal client, or a daemon that is not running, adds no row`() {
        assertNull(DoctorClientVersion().check(health(null)))
        assertNull(DoctorClientVersion().check(null))
    }
}
