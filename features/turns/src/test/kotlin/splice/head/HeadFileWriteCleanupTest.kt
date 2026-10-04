// NEW: module-default temporary-root deletion refuses a live writer and reports its actual path.
package splice.head

import org.junit.jupiter.api.Test

class HeadFileWriteCleanupTest {
    @Test
    fun `an unannotated child inherits the bounded writer cleanup and names its pending file`() {
        HeadFileWriteCleanupProbe.verifyConfiguration()
    }
}
