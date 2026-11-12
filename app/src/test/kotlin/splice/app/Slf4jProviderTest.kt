// NEW: a library's logging never reaches a user's terminal. The jar carries slf4j-api (Ktor brings it
// in), and with no provider beside it SLF4J prints three warning lines to stderr the first time
// anything logs: every `splice add` sign-in showed them. codeModePackagedTest reruns this with the
// shipped jar as the classpath, because a provider lost from the fat jar's merged service files
// brings the lines back with every classpath test still green.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader

class Slf4jProviderTest {
    private val classpath: String = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @Test
    fun `SLF4J starts on the shipped classpath without a word on stderr`() {
        val urls = classpath.split(File.pathSeparator).filter(String::isNotBlank)
            .map { File(it).toURI().toURL() }
            .toTypedArray()
        val stderr = ByteArrayOutputStream()
        val original = System.err
        // The platform loader as parent: SLF4J initializes from THIS classpath alone, never from a copy
        // another test in this JVM already started.
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
            try {
                loader.loadClass("org.slf4j.LoggerFactory").getMethod("getILoggerFactory").invoke(null)
            } finally {
                System.setErr(original)
            }
        }
        assertEquals("", stderr.toString(Charsets.UTF_8), "SLF4J wrote to stderr on first use")
    }
}
