// NEW: which refusal the console's accounts port gives a head it cannot edit. The distinction is load-bearing: the
// `client` arm (ClaudeAccountsArm) dispatches on UnsupportedAuthKind, so if this port answers UnknownHead for a
// configured Claude head, every add and remove on that head goes back to 404 on a head the console has drawn, with
// the arm's own tests still green because they stub this port out.
package splice.app.auth

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.signin.AccountMutation
import splice.accounts.signin.HeadRestart
import splice.accounts.signin.LoginStart
import splice.core.auth.CLIENT_AUTH_KIND
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import java.nio.file.Path

private const val CLAUDE_HEAD = "claude-splice"
private const val UNKNOWN_HEAD = "not-configured"

class ConsoleAccountsRefusalTest {
    @TempDir
    lateinit var tmp: Path

    /** One configured head on the `client` kind: the shape a Claude command has, whose added accounts live in
     *  folders splice owns rather than in the OAuth account file this port edits. */
    private fun port(): ConsoleAccountsImpl = ConsoleAccountsImpl(
        topologies = ConsoleAccountsTopology {
            Topology(
                providers = mapOf(
                    "anthropic" to ProviderConfig(
                        dialect = Dialect.ANTHROPIC_PASSTHROUGH,
                        baseUrl = "https://example.invalid",
                        auth = AuthConfig(kind = CLIENT_AUTH_KIND),
                    ),
                ),
                heads = mapOf(
                    CLAUDE_HEAD to HeadConfig(
                        provider = "anthropic",
                        port = 3100,
                        discoveryPrefix = "claude-splice--",
                        claude = ClaudeWrapperConfig(command = CLAUDE_HEAD, configDir = "$tmp/cfg"),
                    ),
                ),
            )
        },
    )

    @Test
    fun `a configured head whose accounts are not in the OAuth file is refused BY KIND, never as unknown`() =
        runBlocking {
            val port = port()

            val removed = port.removeAccount(CLAUDE_HEAD, "work")
            val relabelled = port.relabelAccount(CLAUDE_HEAD, "work", "personal")
            val started = port.startLogin(CLAUDE_HEAD, "work", HeadRestart { })

            assertEquals(AccountMutation.UnsupportedAuthKind(CLIENT_AUTH_KIND), removed)
            assertEquals(AccountMutation.UnsupportedAuthKind(CLIENT_AUTH_KIND), relabelled)
            assertEquals(LoginStart.UnsupportedAuthKind(CLIENT_AUTH_KIND), started, "the add already did this")
        }

    @Test
    fun `a head that is not configured at all stays unknown`() = runBlocking {
        val port = port()

        assertEquals(AccountMutation.UnknownHead, port.removeAccount(UNKNOWN_HEAD, "work"))
        assertEquals(AccountMutation.UnknownHead, port.relabelAccount(UNKNOWN_HEAD, "work", "personal"))
        assertEquals(LoginStart.UnknownHead, port.startLogin(UNKNOWN_HEAD, "work", HeadRestart { }))
    }
}
