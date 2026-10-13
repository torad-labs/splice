// NEW: Oct 13, 2026 (gaps 461/462) — which dialect reads each [providers.X.quirks] key, so Models offers a command only
// the quirks its dialect reads. QuirksConfig declares the keys for every dialect at once (its header says why it lives in
// :core); the reading is split by QuirksOverlay into each dialect's profile and by the provider arms behind the dialect
// switch. A key no live code reads (effort_ceiling, the retired compact_effort) is in no list, so no page offers it.
package splice.core.topology

/** The dialects that read each quirk, by its splice.toml name. */
public object QuirkReaders {
    private val responses = setOf(Dialect.OPENAI_RESPONSES)
    private val chat = setOf(Dialect.OPENAI_CHAT)
    private val passthrough = setOf(Dialect.ANTHROPIC_PASSTHROUGH)

    private val byQuirk: Map<String, Set<Dialect>> = buildMap {
        words(
            "store account_id_header cache_key summary_field tool_choice reasoning_cache parallel_tool_calls",
            "websocket summary_delivery code_mode code_mode_workers code_mode_timeout_ms code_mode_heap",
            "code_mode_memory_mb code_mode_models tool_surface",
        ).forEach { put(it, responses) }
        words("reasoning_effort chat_effort_vocabulary stream_usage slot_affinity").forEach { put(it, chat) }
        words(
            "mfjs block_allowlist strip_cache_control synthesize_signatures map_thinking_adaptive",
            "strip_sampling_params reanchor_prefill",
        ).forEach { put(it, passthrough) }
        // Muse's responses arm caps tool names as the passthrough does; the upstream client compresses for all
        put("tool_name_cap", responses + passthrough)
        put("zstd_request_body", Dialect.entries.toSet())
    }

    private fun words(vararg lines: String): List<String> = lines.flatMap { it.split(' ') }

    /** The dialects that read [quirk]; none for a key no live code reads. */
    public fun of(quirk: String): Set<Dialect> = byQuirk[quirk].orEmpty()
}
