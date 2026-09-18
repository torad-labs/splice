// NEW: V4-127 — the wiring pin for the console's read ports, and it exists for the reason the
// V4-136 compaction pin exists: the type system cannot do this one.
//
// All four values arrive by ASSIGNMENT after ControlServer is constructed, because that constructor
// sits at the width ratchet's ceiling. So nothing in the compiler connects the daemon to these
// routes: delete an assignment and everything still builds, the route still answers, and it answers
// its named 5xx forever.
//
// THE UPGRADE ONE IS THE WORST AND IS WHY THIS FILE IS NOT OPTIONAL. An unwired doctor or models
// route answers a 503 an operator can see. An unwired upgrade port answers a payload that reads as
// MEASURED and says nothing is newer — which tells an operator they are up to date when nothing has
// ever looked. That is a did-not-run wearing a legitimate answer, and the route cannot tell the
// difference from the inside.
//
// The assertions are on the SOURCE, which is the honest instrument here for the same reason V4-136
// gave: the properties are public and settable from anywhere, so no runtime observation
// distinguishes "ControlPlane set it" from "something set it". Each route's own test already covers
// what it does when the port is set and when it is not; this file covers only that production wires
// it. The builder's route tests set the ports directly on their own ControlServer, so they do NOT
// exercise these lines — that gap is exactly what this file closes.
package campaign.v4127

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ConsoleWiringPinTest {

    @Test
    fun `the control plane wires every console read port to the control server`() {
        val source = controlPlaneSource()
        listOf(
            "srv.declaredHeads = declaredHeads" to
                "the models page would group by a provider nobody reported and show no declared tiers",
            "srv.doctor = DoctorReport(" to
                "/api/doctor would answer its unwired 5xx while the daemon is healthy and reportable",
            "srv.upgrade = UpgradeStatus(" to
                "/api/upgrade would report MEASURED with nothing newer, which tells an operator they " +
                    "are up to date when nothing ever looked",
        ).forEach { (line, harm) ->
            assertTrue(
                source.contains(line),
                "ControlPlane must assign `$line`, or $harm",
            )
        }
    }

    @Test
    fun `the daemon builds the declared-head roster from the topology it already holds`() {
        val daemon = daemonSource()
        assertTrue(
            daemon.contains("declaredHeads = DeclaredHeads {"),
            "Daemon must build DeclaredHeads — it is the only place holding the Topology object, " +
                "and ControlPlane carries only the digest and the path",
        )
        assertTrue(
            daemon.contains("topology.heads.mapValues"),
            "the roster must come from the topology this daemon booted, not a second read of the " +
                "file, which can diverge from the heads that were actually built",
        )
        assertTrue(
            daemon.contains("DeclaredHead(head.provider, head.models)"),
            "each entry carries BOTH the provider key and the declared model list: the models page " +
                "groups by the first and reports missing tiers from the second",
        )
    }

    /** The roster is a MAP so an absent key and a present-key-null stay different facts — a head the
     *  wiring never named versus a head whose operator declared no tiers. Collapsing them is what a
     *  per-head nullable list would have done, and the page exists to show the second. */
    @Test
    fun `the roster keeps unwired and declared-nothing apart`() {
        assertTrue(
            controlPlaneSource().contains("DeclaredHeads { emptyMap() }"),
            "the ControlPlane default must be an EMPTY ROSTER, never a null port: a daemon that " +
                "genuinely knows about no heads is a different answer from one nobody wired",
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
