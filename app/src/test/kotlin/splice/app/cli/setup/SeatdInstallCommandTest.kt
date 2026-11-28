package splice.app.cli.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.capability.CapabilityAftercare
import splice.core.install.seatdPipeRepository
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.lifecycle.seatd.SeatdRehearsal
import splice.terminal.ConfirmPrompt
import splice.terminal.WizardFrame
import java.nio.file.Files
import java.nio.file.Path

class SeatdInstallCommandTest(@param:TempDir private val home: Path) {
    private val share = home.resolve("share")
    private val state = home.resolve("state")
    private val env = EnvReader { key ->
        when (key) {
            "HOME" -> home.toString()
            "SPLICE_CONFIG" -> home.resolve("splice.toml").toString()
            "SPLICE_STATE_DIR" -> state.toString()
            "SPLICE_SHARE_DIR" -> share.toString()
            else -> null
        }
    }
    private val fixture = SeatdRehearsal(env)
    private val said = StringBuilder()

    @Test
    fun `one shell approval authorizes the existing install to refresh on upgrade`() {
        assertEquals(0, offer(answer = true))
        assertEquals(1, said.lines().count { "Install seatd-pipe?" in it })
        assertTrue(seatdPipeRepository in said && "open-source" in said, said.toString())
        assertTrue("opted_in" in Files.readString(state.resolve("seatd.json")))
        assertTrue(upgrade())
        assertTrue(Files.isExecutable(share.resolve("seatd/0.2.0/seatd-pipe")))
    }

    @Test
    fun `shell No records the decline and leaves an older binary alone after upgrading`() {
        assertEquals(0, offer(answer = true))
        assertEquals(0, offer(answer = false, version = "0.2.0"))
        assertTrue("declined" in Files.readString(state.resolve("seatd.json")))
        assertTrue(upgrade())
        assertFalse(Files.exists(share.resolve("seatd/0.2.0/seatd-pipe")))
    }

    @Test
    fun `a nonterminal shell shows the same information without making a decision`() {
        assertEquals(0, offer(answer = true, showOnly = true))
        assertEquals(0, offer(answer = null))
        assertTrue(seatdPipeRepository in said, said.toString())
        assertTrue("splice setup --update" in said, said.toString())
        assertFalse("Install seatd-pipe?" in said, said.toString())
        assertFalse(Files.exists(state.resolve("seatd.json")))
        assertFalse(Files.exists(share))
    }

    private fun offer(answer: Boolean?, version: String = "0.1.0", showOnly: Boolean = false): Int {
        val prompts = SetupPrompts(
            frame = WizardFrame(
                out = said,
                ask = ConfirmPrompt { question, default ->
                    said.appendLine(question)
                    assertFalse(default)
                    checkNotNull(answer)
                },
            ),
            hasConsole = { answer != null },
        )
        return SeatdInstallCommand(env, prompts, fixture.installer(version)).run(showOnly)
    }

    private fun upgrade(): Boolean =
        CapabilityAftercare(env, hub = { emptyList() }, seatd = fixture.installer("0.2.0"))
            .aftercare(TerminalOutput { said.appendLine(it) }).run(auto = true)
}
