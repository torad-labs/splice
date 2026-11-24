// The window the door scales a Desktop app-mode turn's counts to (DesktopAppWindow): Desktop's shipped pair
// by default, and splice.toml's `[desktop]` pair where the table states this install's windows.
package splice.client.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.topology.DesktopConfig

class DesktopAppWindowTest {
    private val app = "app"

    @Test
    fun `an app-mode request holds Desktop's shipped windows by default`() {
        val windows = DesktopAppWindow()
        assertEquals(200_000L, windows.of(app, null, "claude-opus-5-5"))
        assertEquals(1_000_000L, windows.of(app, "context-1m-2025-08-07", "claude-opus-5-5"))
        assertEquals(1_000_000L, windows.of(app, null, "gpt-6-sol[1m]"))
    }

    @Test
    fun `a configured pair is the window an app-mode request holds`() {
        val windows = DesktopAppWindow(DesktopConfig(codeWindow = 320_000, codeWindow1m = 640_000))
        assertEquals(320_000L, windows.of(app, "claude-code-20250219", "claude-opus-5-5"))
        assertEquals(640_000L, windows.of(app, "claude-code-20250219,context-1m-2025-08-07", "claude-opus-5-5"))
        assertEquals(640_000L, windows.of(app, null, "gpt-6-sol[1M]"))
    }

    @Test
    fun `a request that is not app mode's holds no window of Desktop's`() {
        val windows = DesktopAppWindow(DesktopConfig(codeWindow = 320_000, codeWindow1m = 640_000))
        assertNull(windows.of(null, "context-1m-2025-08-07", "gpt-6-sol[1m]"))
        assertNull(windows.of("terminal", null, "claude-opus-5-5"))
    }
}
