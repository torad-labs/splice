// V4-218: a pasted PATH fix must name the same home that InstallPaths resolved, even when HOME is
// unset or blank and the resolver fell back to the JVM's user.home.
package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.wrap.WrappedHead
import splice.core.config.RunningJar
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.diagnostics.doctor.report.DoctorRedaction
import java.nio.file.Path

class DoctorPathCheckTest {

    @Test
    fun `the PATH remedy uses an absolute fallback when HOME is blank`(@TempDir tmp: Path) {
        val home = tmp.resolve("passwd-home")
        val bin = home.resolve(".local/bin")
        val pathCheck = DoctorPathCheck(DoctorProbes(RunningJar { null }, WrappedHead(home)))
        UserHome.within(home) {
            for (value in listOf(null, "", "  ")) {
                val env = EnvReader { name -> if (name == "HOME") value else null }
                val fix = pathCheck.check(bin, env).fix
                val expected = "add to your shell rc: export PATH=\"" +
                    "\$(node -p 'require(\"node:os\").userInfo().homedir')/.local/bin:\$PATH\""
                assertEquals(expected, fix, "HOME=$value must not appear in the pasted command")
                assertEquals(expected, DoctorRedaction(home).text(checkNotNull(fix)))
            }
            val explicit = EnvReader { name -> if (name == "HOME") home.toString() else null }
            assertEquals(
                "add to your shell rc: export PATH=\"\$HOME/.local/bin:\$PATH\"",
                pathCheck.check(bin, explicit).fix,
            )
        }
    }
}
