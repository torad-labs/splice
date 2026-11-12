package splice.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
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
    fun `a read outside every declared root throws, including a sibling sharing a root's name prefix`(
        @TempDir repo: File,
    ) {
        listOf("build-logic/src/main/kotlin/A.kt", "app/src/main-extra/A.kt").forEach { path ->
            val outside = source(repo, path)
            val read = Executable { declaredRead(outside, repo, listOf("app/src/main")) }
            assertThrows(IllegalStateException::class.java, read, path)
        }
    }
}
