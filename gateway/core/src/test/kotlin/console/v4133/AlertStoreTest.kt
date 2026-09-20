// NEW: V4-133 — AlertStore: OFF is the default every install starts in, a replace round-trips a
// 0600 file, webhook_url is validated (blank refused, only http(s) accepted, null clears it), and a
// file that will not parse degrades GET to OFF rather than replacing it.
package console.v4133

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.alert.AlertRefusal
import splice.core.alert.AlertSettings
import splice.core.alert.AlertStore
import splice.core.alert.defaultAlertSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class AlertStoreTest {

    @Test
    fun `a fresh store answers OFF, and a replace round-trips a 0600 file`(@TempDir tmp: Path) {
        val file = tmp.resolve("alerts.json")
        val store = AlertStore(file)
        assertEquals(defaultAlertSettings, store.settings())
        assertFalse(store.settings().desktop)
        assertNull(store.settings().webhookUrl)

        val saved = store.replace(AlertSettings(desktop = true, webhookUrl = "https://hooks.example/x"))
        assertEquals(saved, store.settings())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `a blank webhook_url is refused, only http(s) is accepted, and null clears it`(@TempDir tmp: Path) {
        val store = AlertStore(tmp.resolve("alerts.json"))
        val blank = assertThrows(AlertRefusal::class.java) {
            store.replace(AlertSettings(webhookUrl = "  "))
        }
        assertEquals("webhook_url must be null to clear it, not blank", blank.message)

        val badScheme = assertThrows(AlertRefusal::class.java) {
            store.replace(AlertSettings(webhookUrl = "ftp://hooks.example/x"))
        }
        assertEquals("webhook_url must be an http(s) URL, was 'ftp://hooks.example/x'", badScheme.message)

        store.replace(AlertSettings(desktop = true, webhookUrl = "https://hooks.example/x"))
        val cleared = store.replace(AlertSettings(desktop = true, webhookUrl = null))
        assertNull(cleared.webhookUrl)
        assertNull(store.settings().webhookUrl)
    }

    @Test
    fun `a file that does not parse degrades GET to OFF, and a write recovers it`(@TempDir tmp: Path) {
        val file = tmp.resolve("alerts.json")
        Files.writeString(file, "{ not json")
        val store = AlertStore(file)
        assertEquals(defaultAlertSettings, store.settings(), "GET must always answer something")

        val recovered = store.replace(AlertSettings(desktop = true))
        assertEquals(recovered, store.settings())
    }
}
