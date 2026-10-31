package splice.core.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.nio.file.Files
import java.nio.file.Path

private const val FIXTURE_LIST = "splice.lawReadGuardFixtureList"

class LawReadGuardTest {

    private fun run(emptySet: Path, fixture: Class<*> = FixtureLaws::class.java): SummaryGeneratingListener {
        val listener = SummaryGeneratingListener()
        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectClass(fixture))
            .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
            .build()
        System.setProperty(FIXTURE_LIST, emptySet.toString())
        try {
            LauncherFactory.create().execute(request, listener)
        } finally {
            System.clearProperty(FIXTURE_LIST)
        }
        return listener
    }

    @Test
    fun `RED a law that swallows the refusal fails through the guard, and a clean law does not`(@TempDir dir: Path) {
        val summary = run(Files.writeString(dir.resolve("empty-read-set.txt"), "")).summary

        assertEquals(1, summary.testsFailedCount, "only the law that swallowed the refusal fails")
        assertEquals(1, summary.testsSucceededCount)
        val failure = summary.failures.single()
        val name = failure.testIdentifier.displayName
        assertTrue(name.contains("swallows the refusal"), name)
        val message = failure.exception.message.orEmpty()
        assertTrue(message.contains("tools/undeclared.sh"), message)
    }

    @Test
    fun `RED a refusal swallowed in AfterAll fails the class`(@TempDir dir: Path) {
        val list = Files.writeString(dir.resolve("empty-read-set.txt"), "")
        val summary = run(list, TaggedCleanupLaw::class.java).summary

        assertEquals(1, summary.containersFailedCount, "the class fails after its own AfterAll")
        val message = summary.failures.single().exception.message.orEmpty()
        assertTrue(message.contains("tools/cleanup-undeclared.sh"), message)
    }

    @Test
    fun `the guard forgets a refusal once it has failed the test that caused it`(@TempDir dir: Path) {
        run(Files.writeString(dir.resolve("empty-read-set.txt"), ""))

        assertEquals(emptyList<String>(), UndeclaredReads.drain())
    }
}
