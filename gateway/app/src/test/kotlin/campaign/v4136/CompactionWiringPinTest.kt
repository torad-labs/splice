// NEW: V4-136 — the wiring pin, and it exists because the type system cannot do this one.
//
// The ruling forbade making `compaction` a ControlServer constructor parameter (the class sits at
// the width ratchet's ceiling), so the value arrives by ASSIGNMENT after construction. That means
// nothing in the compiler connects the daemon's resolver to the route: drop the assignment line and
// everything still builds, the route still answers, and it answers its named 5xx forever while the
// daemon happily compacts with rules. Same shape as the V4-103 hookExec pin, for the same reason —
// a wiring that only exists at one call site needs a test standing where the compiler cannot.
//
// The assertion is on the SOURCE, which is the honest instrument here: the property is public and
// could in principle be set from anywhere, so there is no runtime observation that distinguishes
// "ControlPlane set it" from "something set it", and the route's own test covers what the route does
// when it is set and when it is not.
package campaign.v4136

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class CompactionWiringPinTest {

    @Test
    fun `the control plane hands the daemon compaction resolver to the control server`() {
        val source = controlPlaneSource()
        assertTrue(
            source.contains("srv.ports.compaction = compactionInstructions"),
            "ControlPlane must assign ControlServer.compaction, or /api/compaction/instructions " +
                "answers its unwired 5xx in production while the daemon compacts with rules",
        )
    }

    @Test
    fun `the daemon builds one resolver and shares it, rather than a second copy for the route`() {
        val daemon = daemonSource()
        val constructions = Regex("CompactionInstructions\\(").findAll(daemon).count()
        assertTrue(
            constructions == 1,
            "the daemon must construct exactly ONE CompactionInstructions and share it — a second " +
                "instance would agree with the first only by luck, and its file cache would be " +
                "separate. Found $constructions constructions.",
        )
        assertTrue(
            daemon.contains("CompactionTail(compactionInstructions"),
            "CompactionTail must receive the shared instance, not build its own",
        )
    }

    private fun controlPlaneSource(): String = source("gateway/app/src/main/kotlin/splice/app/ControlPlane.kt")

    private fun daemonSource(): String = source("gateway/app/src/main/kotlin/splice/app/Daemon.kt")

    /** Found by walking up from the working directory: under Gradle the cwd is the module dir and
     *  from an IDE it is the repo root, so neither is assumed. */
    private fun source(relative: String): String {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(relative)
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent
        }
        error("$relative not found above ${Paths.get("").toAbsolutePath()}")
    }
}
