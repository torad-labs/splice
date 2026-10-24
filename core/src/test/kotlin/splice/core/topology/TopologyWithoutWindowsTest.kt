// NEW: V4-162 — the comparison that decides whether an edit to splice.toml needs a restart.
//
// WHAT IT PINS: every kind of window edit (a model row's context_window, the head-level
// context_window, extra_windows, window_rules, default_context_window) compares EQUAL once the
// windows are taken out, so the running daemon applies it live; an edit to anything else (a label,
// a port, a quirk, the pinned model) still compares different, so the daemon keeps calling it stale.
package splice.core.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import splice.core.model.ExtraWindow
import splice.core.model.ModelEntry
import splice.core.model.WindowRule

class TopologyWithoutWindowsTest {

    private val provider = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "http://127.0.0.1:8099/v1",
        auth = AuthConfig(kind = "api-key", env = "BONSAI_API_KEY"),
        models = listOf(
            ModelEntry(id = "bonsai-27b", label = "Bonsai 27B", contextWindow = 131_072),
            ModelEntry(id = "bonsai-9b", label = "Bonsai 9B", contextWindow = 65_536),
        ),
    )

    private val head = HeadConfig(
        provider = "bonsai",
        port = 3110,
        discoveryPrefix = "claude-bonsai--",
        pinnedModel = "bonsai-27b",
    )

    private val boot = Topology(providers = mapOf("bonsai" to provider), heads = mapOf("bonsai" to head))

    private fun withProvider(edit: ProviderConfig): Topology = boot.copy(providers = mapOf("bonsai" to edit))

    private fun withHead(edit: HeadConfig): Topology = boot.copy(heads = mapOf("bonsai" to edit))

    @Test
    fun `every window edit compares equal once the windows are out`() {
        val edits = listOf(
            withProvider(provider.copy(models = provider.models.map { it.copy(contextWindow = 245_760) })),
            withProvider(provider.copy(extraWindows = listOf(ExtraWindow("bonsai-aux", 32_768)))),
            withProvider(provider.copy(windowRules = listOf(WindowRule("bonsai-", 16_384)))),
            withProvider(provider.copy(defaultContextWindow = 245_760)),
            withHead(head.copy(contextWindow = 245_760)),
        )

        for (edited in edits) {
            assertNotEquals(boot, edited)
            assertEquals(boot.withoutWindows(), edited.withoutWindows())
        }
    }

    @Test
    fun `anything else still differs`() {
        val edits = listOf(
            withProvider(provider.copy(models = provider.models.map { it.copy(label = "renamed") })),
            withProvider(provider.copy(models = provider.models.take(1))),
            withProvider(provider.copy(quirks = QuirksConfig(reasoningEffort = false))),
            withHead(head.copy(port = 3111)),
            withHead(head.copy(pinnedModel = "bonsai-9b")),
        )

        for (edited in edits) {
            assertNotEquals(boot.withoutWindows(), edited.withoutWindows())
        }
    }
}
