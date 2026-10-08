package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DeclaredReadsTest {

    private fun source(repo: File, path: String): File {
        val file = repo.resolve(path)
        file.parentFile.mkdirs()
        file.writeText("x")
        return file
    }

    @Test
    fun `a read under a declared root passes`(@TempDir repo: File) {
        val inside = source(repo, "build-logic/src/main/kotlin/A.kt")

        assertEquals(inside, declaredRead(inside, repo, listOf("build-logic/src/main/kotlin")))
    }

    @Test
    fun `a read outside every declared root throws, naming the file`(@TempDir repo: File) {
        val outside = source(repo, "build-logic/src/main/kotlin/A.kt")

        val thrown = assertThrows(IllegalStateException::class.java) {
            declaredRead(outside, repo, listOf("app/src/main"))
        }

        assertTrue(thrown.message.orEmpty().contains("A.kt"))
    }

    @Test
    fun `a sibling directory sharing a root's name prefix is not under it`(@TempDir repo: File) {
        val sibling = source(repo, "app/src/main-extra/A.kt")

        assertThrows(IllegalStateException::class.java) {
            declaredRead(sibling, repo, listOf("app/src/main"))
        }
    }
}
