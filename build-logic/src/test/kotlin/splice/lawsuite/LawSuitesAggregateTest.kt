// The red-green proof that `lawSuites` schedules a law task nobody listed: a fixture project that applies splice.law-suite is
// in the aggregate's dependencies whichever script ran first, and a project that does not apply it is not.
package splice.lawsuite

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LawSuitesAggregateTest {
    @TempDir
    lateinit var dir: Path

    private fun root(): Project = ProjectBuilder.builder().withProjectDir(dir.resolve("root").toFile().apply { mkdirs() }).build()

    private fun module(root: Project, name: String): Project =
        ProjectBuilder.builder().withName(name).withParent(root).withProjectDir(dir.resolve(name).toFile().apply { mkdirs() }).build()
            .also { it.pluginManager.apply("java") }

    private fun scheduled(root: Project): List<String> {
        val aggregate = root.tasks.getByName("lawSuites")
        return aggregate.taskDependencies.getDependencies(aggregate).map { it.path }.sorted()
    }

    @Test
    fun `RED a project that applies the plugin after the aggregate exists is scheduled with no list edited`() {
        val root = root()
        root.tasks.register("lawSuites")
        val first = module(root, "first")
        val second = module(root, "second")

        first.pluginManager.apply("splice.law-suite")
        second.pluginManager.apply("splice.law-suite")

        assertEquals(listOf(":first:lawTest", ":second:lawTest"), scheduled(root))
    }

    @Test
    fun `a project that applied the plugin before the aggregate was registered is scheduled too`() {
        val root = root()
        val early = module(root, "early")
        early.pluginManager.apply("splice.law-suite")

        root.tasks.register("lawSuites")

        assertEquals(listOf(":early:lawTest"), scheduled(root))
    }

    @Test
    fun `a project that does not apply the plugin is not scheduled`() {
        val root = root()
        root.tasks.register("lawSuites")
        module(root, "plain")
        module(root, "guarded").pluginManager.apply("splice.law-suite")

        assertEquals(listOf(":guarded:lawTest"), scheduled(root))
    }
}
