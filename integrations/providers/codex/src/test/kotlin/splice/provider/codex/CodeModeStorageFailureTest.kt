package splice.provider.codex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.CodeModeStorageFault
import splice.core.failure.FailureCause
import splice.core.failure.HeadVoice
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.ProtocolFault
import splice.core.failure.RequestFacts
import splice.core.memory.HeapCapacityException
import java.io.IOException

class CodeModeStorageFailureTest {
    @Test
    fun `wrapping an existing restore refusal preserves the storage cause and operation`() {
        val limit = CodeModeStorageException.Limit(4096, 1024)
        val restore = CodeModePersistenceException(limit, false, CodeModeStorageOperation.RESTORE)
        val wrapped = CodeModePersistenceException(restore, false)
        assertSame(limit, wrapped.cause?.cause)
        assertEquals(restore.message, wrapped.message)
        assertEquals(restore.outcome(), wrapped.outcome())
        assertTrue("restored" in wrapped.message)
        assertTrue(wrapped.outcome().cause.deterministic)
    }

    @Test
    fun `unclassified I O names the safe operation without echoing exception content`() {
        val failure =
            CodeModePersistenceException(IOException("PRIVATE file content"), false, CodeModeStorageOperation.RESTORE)
        val outcome = failure.outcome()
        val voice = HeadVoice("claudex", null).turn(RequestFacts(false, false, repeated = true))
        val line = voice.ending(outcome.cause, outcome.at).text
        assertTrue("storage I/O failed" in failure.message)
        assertTrue("restored" in failure.message)
        assertTrue("storage I/O failed" in line)
        assertFalse("PRIVATE" in failure.message)
        assertFalse("PRIVATE" in line)
        assertFalse("runtime did not start" in line)
        assertTrue("Check the state storage" in line)
    }

    @Test
    fun `temporary heap and disk failures keep their policy and identify the resource`() {
        val cases = listOf(
            Triple(CodeModePersistenceException(HeapCapacityException(), false), CodeModeStorageFault.HEAP, "memory"),
            Triple(
                CodeModePersistenceException(IOException("PRIVATE"), true),
                CodeModeStorageFault.DISK_FULL,
                "disk is full",
            ),
        )
        val at = Position(Moment.BEFORE_COMMIT, streamBegan = false)
        for ((failure, resource, text) in cases) {
            val outcome = failure.outcome()
            val cause = outcome.cause as FailureCause.Splice.Protocol
            val voice = HeadVoice("claudex", null).anyTurn
            assertEquals(resource, cause.storage)
            assertEquals(ProtocolFault.CODE_MODE_START, cause.fault)
            assertTrue(voice.answer(cause, at).clientResends)
            assertTrue(text in voice.ending(cause, at).text)
        }
    }
}
