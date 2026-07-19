// NEW (G25): idle heap uncommit — DEFAULT_JVM_OPTS must carry -XX:G1PeriodicGCInterval=60000
// alongside the pre-existing G10 flags (-Xmx1024m, -XX:+UseStringDeduplication), since both
// cold-start paths (AdminSupport.spawnDaemon and bin/splice-launch) are meant to agree.
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.cli.AdminSupport

class AdminSupportTest {

    @Test
    fun `DEFAULT_JVM_OPTS carries the G1 periodic GC interval flag`() {
        assertTrue(AdminSupport.DEFAULT_JVM_OPTS.contains("-XX:G1PeriodicGCInterval=60000"))
    }

    @Test
    fun `DEFAULT_JVM_OPTS keeps the pre-existing heap cap and string-dedup flags`() {
        assertTrue(AdminSupport.DEFAULT_JVM_OPTS.contains("-Xmx1024m"))
        assertTrue(AdminSupport.DEFAULT_JVM_OPTS.contains("-XX:+UseStringDeduplication"))
        assertTrue(AdminSupport.DEFAULT_JVM_OPTS.contains("-XX:G1PeriodicGCInterval=60000"))
    }
}
