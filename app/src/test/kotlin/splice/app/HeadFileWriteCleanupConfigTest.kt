// NEW: app's temporary head stores inherit the same bounded writer cleanup as the turns module.
package splice.app

import org.junit.jupiter.api.Test
import splice.head.HeadFileWriteCleanupProbe

class HeadFileWriteCleanupConfigTest {
    @Test
    fun `app inherits refusal for tracked and delayed temporary-root writers`() {
        HeadFileWriteCleanupProbe.verifyConfiguration()
    }
}
