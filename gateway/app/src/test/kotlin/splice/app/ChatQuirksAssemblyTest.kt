// NEW (DR-155): the WIRING arm. The dialect suites prove the floor works; this proves the live
// claude-grok head actually gets one, and that nothing else does.
//
// The denominator here is the auth kinds this arm can be handed, not a list of suspects: grok-oauth
// takes the floor, and every other kind reaching the chat dialect (api-key, custom, anything
// unregistered) must keep null — because null is what makes those heads decode nothing and ship
// byte-identical requests. A quirk that exists but is never set is the shape of defect this row's
// whole repair would otherwise have.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.app.provider.QuirksOverlay
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.QuirksConfig
import splice.provider.codex.CodexQuirks
import splice.provider.grok.GrokQuirks
import splice.provider.openai.OpenAiQuirks

class ChatQuirksAssemblyTest {

    private val overlay = QuirksOverlay()

    private fun chat(authKind: String, quirks: QuirksConfig = QuirksConfig()) = overlay.chatQuirks(
        ProviderConfig(
            dialect = Dialect.OPENAI_CHAT,
            baseUrl = "https://api.x.ai/v1",
            auth = AuthConfig(authKind),
            quirks = quirks,
        ),
        key = "claude-grok",
        label = "grok",
    )

    // xAI's verbatim rejection is "Both width and height must be at least 8 pixels." The number is
    // the vendor's, and it has to arrive at the head that talks to that vendor.
    @Test
    fun `the grok-oauth chat profile carries xAI's 8px floor - DR-155`() {
        assertEquals(8, chat("grok-oauth").minImageEdgePx)
    }

    // Mutant: set the floor on the base profile instead of the grok branch. Every unregistered
    // vendor on this dialect would then start decoding and dropping images on a rule its backend
    // never stated.
    @Test
    fun `every other auth kind on this dialect keeps a null floor - DR-155`() {
        for (kind in listOf("api-key", "custom", "client", "unregistered-vendor")) {
            assertNull(chat(kind).minImageEdgePx, "$kind must not inherit a vendor's floor")
        }
    }

    // The floor is a fact about a backend, not an operator preference, so it survives the TOML
    // overlay rather than being reachable from it. Mutant: route it through withReasoningEffortToml
    // or add it to QuirksConfig — either would let a splice.toml typo silently delete images.
    @Test
    fun `the TOML overlay cannot move the floor in either direction - DR-155`() {
        assertEquals(8, chat("grok-oauth", QuirksConfig(reasoningEffort = false)).minImageEdgePx)
        assertNull(chat("api-key", QuirksConfig(reasoningEffort = true)).minImageEdgePx)
    }

    // The rest of the grok-oauth profile is unchanged by DR-155 — the arm exists so a future edit
    // to this branch cannot quietly drop the caching or usage-frame wiring while adding a knob.
    @Test
    fun `the grok-oauth profile keeps its cache prefix and usage frames - DR-155 control`() {
        val q = chat("grok-oauth")
        assertEquals("grok", q.sessionCacheKeyPrefix)
        assertEquals(true, q.emitUsageInStream)
        assertEquals("claude-grok", q.providerTag)
    }

    // The OTHER dialect's denominator, enumerated from the source rather than from suspicion: three
    // classes construct a ResponsesQuirks profile in this tree, and every one of them gets a
    // disposition here. :app is the only module that can see all three at once, which is why they
    // are asserted from here rather than one arm per provider suite.
    //
    // No grok head rides the Responses dialect today, so its floor is dormant — but a dormant knob
    // that is silently wrong is exactly what surfaces the day a head moves, and codex is the
    // highest-traffic head in the fleet: a floor appearing on that profile would start deleting
    // real screenshots against a rule its backend never stated.
    @Test
    fun `every responses profile states its floor, and only grok has one - DR-155`() {
        assertEquals(8, GrokQuirks().defaultQuirks().minImageEdgePx, "xAI enforces a minimum")
        assertNull(CodexQuirks().defaultQuirks().minImageEdgePx, "the ChatGPT backend states none")
        assertNull(OpenAiQuirks().defaultQuirks().minImageEdgePx, "openai-platform states none")
    }
}
