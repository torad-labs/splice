// AdminSupport.selfJar: which archive this build runs from, located by its own class resource. The
// cold-start arms (DEFAULT_JVM_OPTS, the bound-port refusal, the argv and the JW-01 boot-log redirect)
// moved to features/lifecycle's DaemonLaunchTest with the cold start (LAYOUT-01).
package splice.app.cli

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AdminSupportTest {

    // HD-18 review: `java.class.path` describes how the JVM was LAUNCHED, not which jar this class
    // came out of, and a single `.jar` entry is not proof of `java -jar splice.jar` — a pathing jar
    // (an IDE/JUnit long-classpath wrapper whose manifest carries the real Class-Path) is one entry
    // too, and it is not this build. selfJar() must ignore the property entirely and locate itself,
    // so a daemon cold start can never become `java -jar <someone-else's>.jar daemon` and doctor can
    // never report OK on a jar that has no splice Main-Class.
    @Test
    fun `selfJar ignores a single-entry launcher classpath`(@TempDir tempDir: Path) {
        val launcher = tempDir.resolve("launcher.jar")
        Files.writeString(launcher, "not a splice build")
        val saved = System.getProperty("java.class.path")
        try {
            System.setProperty("java.class.path", launcher.toString())
            assertFalse(
                AdminSupport.selfJar() == launcher,
                "a single-entry launcher classpath must never be mistaken for this build",
            )
        } finally {
            System.setProperty("java.class.path", saved)
        }
    }
}
