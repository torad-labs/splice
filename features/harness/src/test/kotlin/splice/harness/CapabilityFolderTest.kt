// NEW: what a capability that sends the session's folder is posted (docs/specs/harness.md, decisions 4 and 11): the
// folder comes from the turn, through the harness's port, as the head resolves it for the session.
package splice.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.capability.CapabilityField
import java.nio.file.Files
import java.nio.file.Path

class CapabilityFolderTest(@param:TempDir private val tmp: Path) {
    private val rig = CapabilityRig(tmp)

    @AfterEach
    fun close() = rig.close()

    private fun sendingFolder() = rig.capability("brief").let {
        it.copy(work = it.work.copy(send = listOf(CapabilityField.FOLDER)))
    }

    @Test
    fun `the worker is posted the folder the session works in`() = runBlocking {
        val folder = Files.createDirectories(tmp.resolve("work"))
        val head = rig.head(listOf(sendingFolder()), folder = folder)

        rig.send(head, Turn("s1", opening("Fix the bug")))

        assertEquals(folder.toString(), rig.asked.single().fields.getValue("folder").jsonPrimitive.content)
    }

    @Test
    fun `the worker is posted a null folder for a session the head cannot place`() = runBlocking {
        val head = rig.head(listOf(sendingFolder()))

        rig.send(head, Turn("s1", opening("Fix the bug")))

        assertEquals(JsonNull, rig.asked.single().fields.getValue("folder"))
    }
}
