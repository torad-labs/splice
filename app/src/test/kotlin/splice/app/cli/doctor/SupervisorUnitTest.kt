// The supervision requirement is a NAME THE OPERATOR SUPPLIES: splice restarts into a systemd user unit
// it does not own, and a box whose packager called the unit something else must not look permanently
// unsupervised, because `POST /api/daemon/restart` refuses to drain an unsupervised daemon.
package splice.app.cli.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.app.cli.AdminSupport
import splice.core.util.EnvReader

class SupervisorUnitTest {

    @Test
    fun `a blank unit name falls back to the default rather than asking about nothing`() {
        val unit = AdminSupport.supervisorUnit(EnvReader { name -> if (name == "SPLICE_SUPERVISOR_UNIT") "" else null })

        assertEquals("splice.service", unit)
    }

    @Test
    fun `an environment-supplied unit name is what splice uses`() {
        val unit = AdminSupport.supervisorUnit(
            EnvReader { name -> if (name == "SPLICE_SUPERVISOR_UNIT") "my-splice.service" else null },
        )

        assertEquals("my-splice.service", unit)
    }
}
