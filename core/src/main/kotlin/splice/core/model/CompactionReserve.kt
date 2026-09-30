// NEW: V4-446 Phase 2 — empirical reserve behind the Codex client's compaction threshold.
package splice.core.model

private const val CALIBRATION_RESOURCE = "/compaction-reserve-p99.tsv"
private const val CALIBRATION_HEADER = "model\tgrowth_and_prompt_p99\tgeneration_p99"

// why: each calibration row has its model id and two independent p99 measurements.
private const val CALIBRATION_COLUMNS = 3

// why: Claude Code subtracts at most this much output budget from its effective context.
private const val CLIENT_MAX_OUTPUT_RESERVE = 20_000L

// why: the launch plants this auto-compact threshold override for every wrapped client.
private const val CLIENT_COMPACT_PERCENT = 85L

// why: the override is a percentage of the effective client context window.
private const val PERCENT_DENOMINATOR = 100L

// why: the installed client also holds this many tokens beyond its percentage threshold.
private const val CLIENT_TAIL_RESERVE = 13_000L

/** P99 context growth through the compaction prompt plus P99 generated compaction output.
 * Input/cache buckets are cumulative, so a reserve is subtracted from the real model window once. */
public data class CompactionReserve(val growthAndPromptP99: Long, val generationP99: Long) {
    public val totalTokens: Long get() = growthAndPromptP99.coerceAtLeast(0) + generationP99
}

/** A row's overridable total reserve and the independently calibrated generation allowance. */
public data class CompactionBudget(val totalTokens: Long, val generationTokens: Long)

/** Resolves the row override and empirical default once, for both usage scaling and preflight. */
public object CompactionBudgets {
    public fun forRow(catalog: ModelCatalog, id: String): CompactionBudget? {
        val current = catalog.live()
        val raw = current.unwrap(id)
        val canonical = current.stripSuffixes(id)
        val row = current.models.firstOrNull { it.id == raw }
            ?: current.models.firstOrNull { current.stripSuffixes(it.id) == canonical }
        val calibrated = current.compactionReserveDefaults?.forRow(canonical, current.contextWindowFor(id))
        val total = row?.compactionReserveTokens ?: calibrated?.totalTokens ?: return null
        return CompactionBudget(total, calibrated?.generationP99 ?: 0)
    }
}

/** Replaceable calibration source; a null answer keeps a non-Codex row's existing scaling. */
public fun interface CompactionReserveDefaults {
    public fun forRow(model: String, window: Long): CompactionReserve?
}

/** Numeric-only audit captures/v4-446-compaction-audit-20260929.json, sha256 68a5dab1...62ad7.
 * The bundled TSV freezes clean-pair nearest-rank p99 growth and successful-generation p99.
 * A growth pair is consecutive within its session, same model, predecessor finished before the
 * compaction, both inputs within the current declared W, and token delta within added request
 * bytes. No historical effective W is retained, so an unobserved config change remains unknown.
 * GPT-5.5 has no own sample: its growth is the median over 111 clean GPT-5
 * predecessors and its generation is the pooled nearest-rank p99 over 832 GPT-5 successes.
 * Other unsampled model ids keep their old scaling until measured; no worst-case family transfer.
 * Sparse samples are empirical, not output caps; preflight guards new deltas. */
public object CodexCompactionReserves : CompactionReserveDefaults {
    private val samples: Map<String, CompactionReserve> by lazy {
        val resource = checkNotNull(javaClass.getResourceAsStream(CALIBRATION_RESOURCE)) {
            "missing $CALIBRATION_RESOURCE"
        }
        val lines = resource.bufferedReader().use { it.readLines() }
        check(lines.firstOrNull() == CALIBRATION_HEADER) { "unexpected $CALIBRATION_RESOURCE header" }
        val rows = lines.drop(1).filter(String::isNotBlank)
        val parsed = rows.associate { line ->
            val cells = line.split('\t')
            check(cells.size == CALIBRATION_COLUMNS) { "invalid $CALIBRATION_RESOURCE row" }
            cells[0] to CompactionReserve(cells[1].toLong(), cells[2].toLong())
        }
        check(parsed.size == rows.size) { "duplicate $CALIBRATION_RESOURCE model" }
        parsed
    }

    override fun forRow(model: String, window: Long): CompactionReserve? {
        if (window <= 0) return null
        return samples[ModelTierSuffix.strip(model)]
    }
}

/** Installed Claude Code's effective context and auto-compact threshold (2.1.285 audit).
 * The launch sets the 85% override; the model selector supplies [clientWindow]. */
internal object ClaudeCodeCompactThreshold {
    public fun tokens(clientWindow: Long, outputLimit: Long = CLIENT_MAX_OUTPUT_RESERVE): Long {
        val effective = (clientWindow - minOf(outputLimit, CLIENT_MAX_OUTPUT_RESERVE)).coerceAtLeast(0)
        return minOf(effective * CLIENT_COMPACT_PERCENT / PERCENT_DENOMINATOR, effective - CLIENT_TAIL_RESERVE)
            .coerceAtLeast(0)
    }
}
