package splice.app.cli.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountQuota
import splice.accounts.pool.HeadAccountSwitchView
import splice.accounts.pool.HeadAccountView
import splice.accounts.pool.HeadAccountWindow
import splice.core.GATEWAY_VERSION
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.daemonclient.DaemonProbe
import splice.diagnostics.doctor.AccountPoolRead
import splice.diagnostics.doctor.AccountPoolsRead
import splice.upstream.transport.LocalHttp
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/** Spec section 11: `splice status` names the account a head is on, its last switch and the reason. */
class StatusAccountsLineTest {
    private val threeMinutesMs = 180_000L
    private val noHeads = DaemonProbe.HealthHeads(total = 0, ready = 0, failed = 0)

    @Test
    fun `status prints the selected account and the last switch with its reason`(@TempDir home: Path) {
        val view = HeadAccountPoolView(
            selectedLabel = "work",
            accounts = listOf(
                HeadAccountView("primary", primary = true, selected = false, available = false, plan = "plus"),
                HeadAccountView(
                    "work",
                    primary = false,
                    selected = true,
                    available = true,
                    plan = "pro",
                    quota = HeadAccountQuota(HeadAccountWindow(12.4), HeadAccountWindow(40.0)),
                ),
            ),
            lastSwitch = HeadAccountSwitchView(
                "primary",
                "work",
                "7d window exhausted",
                System.currentTimeMillis() - threeMinutesMs,
            ),
        )
        val status = StatusCommand(
            healthProbe = HealthProbe { DaemonProbe.HealthView(GATEWAY_VERSION, heads = noHeads) },
            accountPools = AccountPoolRead { _, _ -> AccountPoolsRead.Read(mapOf("claudex" to view)) },
            localRuntimes = LocalRuntimeReach(LocalHttp { _, _, _ -> null }),
        )

        val printed = UserHome.within(home) { printedBy { status.status(EnvReader { null }) } }

        val line = printed.lines().single { it.contains("accounts") && it.contains("claudex") }
        assertTrue(line.contains("on work (1 of 2 open)"), line)
        assertTrue(line.contains("switched from primary to work 3m ago: 7d window exhausted"), line)
        assertEquals(1, printed.lines().count { it.contains("accounts") }, "one line per pooled head")
    }

    private fun printedBy(block: () -> Unit): String {
        val saved = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(saved)
        }
        return captured.toString(Charsets.UTF_8)
    }
}
