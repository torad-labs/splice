// AdminSupport.selfJar: which archive this build runs from, located by its own class resource. The
// cold-start arms (DEFAULT_JVM_OPTS, the bound-port refusal, the argv and the JW-01 boot-log redirect)
// moved to features/lifecycle's DaemonLaunchTest with the cold start (LAYOUT-01).
package splice.app.cli

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class AdminSupportTest {

    // HD-18 review: `java.class.path` describes how the JVM was LAUNCHED, not which jar this class
    // came out of, and a single `.jar` entry is not proof of `java -jar splice.jar` — a pathing jar
    // (an IDE/JUnit long-classpath wrapper whose manifest carries the real Class-Path) is one entry
    // too, and it is not this build. selfJar() must ignore the property entirely and locate itself,
    // so a daemon cold start can never become `java -jar <someone-else's>.jar daemon` and doctor can
    // never report OK on a jar that has no splice Main-Class.
    @Test
    fun `selfJar ignores a single-entry launcher classpath`() {
        val launcher = Files.createTempDirectory("selfjar").resolve("launcher.jar")
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

    // selfJar() locates itself by a resource NAME, a string nothing links or renames with. This
    // pins the literal to the class it is meant to name (reflection is fine here — test sources are
    // exempt from kt-no-reflection-in-production, and checking the literal is the whole point).
    @Test
    fun `the self-locating resource name matches AdminSupport's own class file`() {
        val expected = AdminSupport::class.java.name.replace('.', '/') + ".class"
        assertTrue(expected == "splice/app/cli/AdminSupport.class", "was: $expected")
        assertTrue(
            AdminSupport::class.java.classLoader.getResource(expected) != null,
            "$expected must resolve on the loader that holds this build",
        )
    }
}
