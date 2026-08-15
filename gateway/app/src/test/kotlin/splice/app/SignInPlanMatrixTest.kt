// WALLS for /login across EVERY head kind (operator request, 2026-08-01). Nothing tested the
// auth.kind -> /login shape mapping before, which is how two regressions landed in one week:
//
//  1. api-key heads promised "a masked terminal prompt is asking for your key" while spawning a
//     DETACHED login that has no TTY and could never prompt — a dead end nothing caught;
//  2. a fix for (1) then removed /login from api-key heads without a capture pattern, deleting
//     working behaviour, because no test asserted that EVERY head keeps /login.
//
// THE INVARIANT THIS FILE HOLDS: every head in the topology has a sign-in path — that is what
// being in the topology means. /login is therefore wired for ALL of them. What varies is only
// the WORDING and whether a browser is spawned.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig

private fun head(provider: String, command: String) = HeadConfig(
    provider = provider,
    port = 3100,
    discoveryPrefix = "claude-$provider--",
    pinnedModel = "m",
    claude = ClaudeWrapperConfig(command = command),
)

private fun providerCfg(authKind: String, envVar: String? = null) = ProviderConfig(
    dialect = Dialect.OPENAI_RESPONSES,
    baseUrl = "https://example.invalid",
    auth = AuthConfig(kind = authKind, env = envVar),
)

class SignInPlanMatrixTest {

    /** THE LOAD-BEARING ONE. Every supported head kind gets a /login command. A blank command is
     *  what makes LoginInterception.wire() skip the hook entirely, so a blank here means the head
     *  silently loses /login — the exact regression this pins against. */
    @Test
    fun `EVERY head kind gets a login command — none is silently skipped`() {
        val cases = listOf(
            "chatgpt-oauth" to head("codex", "claudex"),
            "grok-oauth" to head("xai", "claude-grok"),
            "kimi-oauth" to head("kimi", "claude-kimi"),
            API_KEY to head("openrouter", "claude-openrouter"), // known token shape
            API_KEY to head("fireworks", "claude-fireworks"), // NO known token shape
        )
        for ((kind, h) in cases) {
            val plan = signInPlan(providerCfg(kind), h, h.provider)
            assertTrue(
                plan.command.isNotBlank(),
                "$kind (${h.provider}) lost its /login command — wire() would skip the hook entirely",
            )
            assertEquals("${h.claude.command} login", plan.command, "the command must name the head's wrapper")
            assertTrue(plan.label.isNotBlank(), "$kind needs a label for the /login wording")
        }
    }

    /** Browser heads open a browser; api-key heads must NOT, because a detached login has no TTY
     *  and the prompt it promises can never appear. */
    @Test
    fun `only browser-based kinds are marked viaBrowser`() {
        assertTrue(signInPlan(providerCfg("chatgpt-oauth"), head("codex", "claudex"), "codex").viaBrowser)
        assertTrue(signInPlan(providerCfg("grok-oauth"), head("xai", "claude-grok"), "xai").viaBrowser)
        assertTrue(signInPlan(providerCfg("kimi-oauth"), head("kimi", "claude-kimi"), "kimi").viaBrowser)
        assertFalse(
            signInPlan(providerCfg(API_KEY), head("openrouter", "claude-openrouter"), "openrouter").viaBrowser,
            "an api-key head has no browser flow — claiming one is what produced the dead-end prompt",
        )
    }

    /** ONE PROVIDER AT A TIME: capture is opt-in per vendor whose token shape splice actually
     *  knows. This scopes CAPTURE only — never whether /login exists (asserted above). */
    @Test
    fun `token capture is enabled only for vendors whose token shape splice knows`() {
        val openrouter = signInPlan(providerCfg(API_KEY), head("openrouter", "claude-openrouter"), "openrouter")
        assertNotNull(openrouter.tokenCapture, "OpenRouter's sk-or- prefix is unambiguous — capture is safe")
        assertEquals("OPENROUTER_API_KEY", openrouter.tokenCapture?.envVar)
        assertTrue(openrouter.tokenCapture!!.tokenPattern.startsWith("sk-or-"))

        for (vendor in listOf("fireworks", "openai", "moonshot")) {
            assertNull(
                signInPlan(providerCfg(API_KEY), head(vendor, "claude-$vendor"), vendor).tokenCapture,
                "$vendor has no pinned token shape — guessing one risks capturing ordinary prose",
            )
        }
    }

    /** An OAuth head never captures pastes: its secret never appears in the prompt box at all. */
    @Test
    fun `oauth kinds never enable paste capture`() {
        for (kind in listOf("chatgpt-oauth", "grok-oauth", "kimi-oauth")) {
            assertNull(
                signInPlan(providerCfg(kind), head("p", "claude-p"), "p").tokenCapture,
                "$kind signs in through the browser — there is no token to paste",
            )
        }
    }

    /** An UNKNOWN auth kind is the one case with no sign-in path, and it must stay blank so
     *  wire() skips /login rather than advertising something that cannot work. */
    @Test
    fun `an unknown auth kind gets no login command`() {
        val plan = signInPlan(providerCfg("some-future-kind"), head("x", "claude-x"), "x")
        assertEquals("", plan.command, "no known flow => no /login, rather than a broken one")
        assertNull(plan.tokenCapture)
    }

    /** The env var the capture hook writes must be the one the auth provider READS, or a captured
     *  key lands somewhere nothing looks. Pinned for the explicit-env case too. */
    @Test
    fun `capture writes the same env var the provider resolves`() {
        val explicit = signInPlan(
            providerCfg(API_KEY, envVar = "CUSTOM_OR_KEY"),
            head("openrouter", "claude-openrouter"),
            "openrouter",
        )
        assertEquals(
            "CUSTOM_OR_KEY",
            explicit.tokenCapture?.envVar,
            "an explicit auth.env must win, or the key is stored under a name nothing reads",
        )
    }

    /** THE INVERSE of the load-bearing case above, and it is load-bearing for the opposite reason.
     *  A client-auth head is the ONE head that keeps the client's own /login enabled (the launcher
     *  does not set DISABLE_LOGIN_COMMAND for it). A non-blank command here would make
     *  LoginInterception.wire() plant splice's OWN /login into that head's config dir, competing
     *  with the door that actually works — and its flow ends at `<command> login`, which for this
     *  kind prints "no browser login for that kind" and fails forever. Blank is the fix, and the
     *  same blank drops TurnDriver's "— run: <command>" clause from a 401 so the upstream's own
     *  message stands instead of an instruction that cannot succeed. */
    @Test
    fun `a client-auth head gets NO splice login command — the client's own door stays the only one`() {
        val plan = signInPlan(providerCfg("client"), head("anthropic", "claude-splice"), "anthropic")
        assertTrue(
            plan.command.isBlank(),
            "a non-blank command makes wire() plant a /login that can never succeed: '${plan.command}'",
        )
        assertFalse(plan.viaBrowser, "splice runs no browser flow for this kind")
        assertEquals(null, plan.tokenCapture, "there is no token for splice to capture")
    }
}
