package splice.app.control.mount

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import java.nio.file.Files

class ServedConsoleTest {
    private val console = ServedConsole(
        { "" },
        MgmtKey(StatePaths(baseOverride = Files.createTempDirectory("served-console").resolve("state")), log = {}),
    )

    @Test
    fun `the key is the first element of the head, whatever the head tag carries`() {
        assertEquals(
            "<html><head><meta name=\"splice-mgmt-key\" content=\"ab12\"><title>t</title></head></html>",
            console.withKey("<html><head><title>t</title></head></html>", "ab12"),
        )
        assertEquals(
            "<HEAD lang=\"en\"><meta name=\"splice-mgmt-key\" content=\"ab12\"></HEAD>",
            console.withKey("<HEAD lang=\"en\"></HEAD>", "ab12"),
        )
    }

    @Test
    fun `a page with no head is served as it is and the console falls back to its gate`() {
        val placeholder = "<!doctype html><title>splice</title><p>dashboard build missing</p>"
        assertEquals(placeholder, console.withKey(placeholder, "ab12"))
    }

    @Test
    fun `whatever the key file holds is printed as an attribute value, never as markup`() {
        assertEquals(
            "<head><meta name=\"splice-mgmt-key\" content=\"&quot;&gt;&lt;script&gt;&amp;\">",
            console.withKey("<head>", "\"><script>&"),
        )
    }
}
