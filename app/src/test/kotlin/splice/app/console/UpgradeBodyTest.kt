// NEW: V4-127 — the console's upgrade BODY, pinned on the discipline rather than on the host.
//
// These assertions are about what the payload REFUSES to say. The strongest one is that `latest` is
// null while `installed` is a real version: the failure this row exists to prevent is a payload that
// answers "you are up to date" when nothing ever checked, and the surest way to produce it is to fill
// `latest` from `installed`. That is exactly the mutation the red proof uses.
//
// Nothing here pins a filesystem: the release layout is read from the real one, so the installed
// version and the presence of a previous release differ between hosts, and asserting their values
// would pin this machine. What is host-independent is the SHAPE and the honesty.
package splice.app.console

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpgradeBodyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun payload() = json.parseToJsonElement(ConsoleUpgradeStatus().json()).jsonObject

    @Test
    fun `latest is never filled in from installed`() {
        val report = payload()
        val installed = report["installed"]!!.jsonPrimitive.content
        assertTrue(installed.isNotEmpty(), "the installed version is always answerable")
        assertEquals(
            JsonNull,
            report["latest"],
            "nothing checked, so nothing is claimed: filling this from installed is the confident " +
                "false negative this payload exists to refuse",
        )
        assertEquals(LATEST_NOT_CHECKED, report["latest_unavailable_reason"]!!.jsonPrimitive.content)
        assertEquals("unavailable", report["latest_basis"]!!.jsonPrimitive.content)
        // The reason must say WHY, not restate the absence, or an operator reads a bare null as a bug.
        assertTrue(report["latest_unavailable_reason"]!!.jsonPrimitive.content.length > 20)
    }

    @Test
    fun `a null rollback target is MEASURED, which is not the same as unable to look`() {
        val report = payload()
        // The layout read succeeds here, so the basis is measured whatever the value: a null target
        // then means "no previous release exists", a real answer. Reporting it unavailable would turn
        // a fact into a shrug; reporting an unreadable layout as measured would do the reverse. Both
        // halves of that distinction are pinned, here and in the body's rollbackTarget().
        assertEquals("measured", report["rollback_basis"]!!.jsonPrimitive.content)
        assertEquals(
            JsonNull,
            report["rollback_unavailable_reason"],
            "a measured fact carries no excuse, whether its value is a version or null",
        )
    }

    @Test
    fun `the payload carries the six states and an absolute check time`() {
        val keys = payload().keys
        val named = setOf(
            "installed",
            "latest",
            "latest_basis",
            "latest_unavailable_reason",
            "rollback_target",
            "rollback_basis",
            "rollback_unavailable_reason",
            "checked_at_epoch_millis",
        )
        assertTrue(keys.containsAll(named), "missing ${named - keys}")
        // ABSOLUTE AND NULL, never zero and never the epoch: either would date "nothing has looked" to
        // 1970 and read as a measurement taken then.
        assertEquals(JsonNull, payload()["checked_at_epoch_millis"])
    }
}
