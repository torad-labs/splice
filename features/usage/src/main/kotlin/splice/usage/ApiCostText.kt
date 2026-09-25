// NEW: V4-240, the one place splice spells a dollar figure, so none leaves without its basis.
//
// Every figure splice prints is priced from a rate card: the vendor's published API price declared on
// the model row, or Claude Code's own Anthropic card on a head whose upstream is Anthropic. None is a
// charge. On a subscription head nothing is billed per token at all, so a bare "$0.85" reads as money
// spent that was not spent. The console says "Estimated API cost" beside every such figure (#281);
// these are the same words, in a short form for the status line and a long one for sentences.
//
// kt-dollar-figure-single-source makes this file the only main source that glues a `$` to an amount,
// so a new surface cannot print a bare figure without reaching past the wall.
package splice.usage

import java.util.Locale

internal object ApiCostText {
    /** What a model with no rate card shows where its figure would be: words, never a blank. */
    const val NO_RATE_CARD = "no rate card"

    /** The status line's form, `API est. $0.85`. [quiet] and [loud] (the line's dim and reset codes)
     *  wrap the basis and the sign so the amount is what stands out, and [marker] (the `≥` of a
     *  figure that may be short) sits between the basis and the figure. */
    fun short(usd: Double, quiet: String = "", loud: String = "", marker: String = ""): String =
        quiet + SHORT_BASIS + loud + " " + marker + quiet + SIGN + loud + amount(usd)

    /** In a sentence: `an estimated $0.85 in API cost`. */
    fun sentence(usd: Double): String = "an estimated " + SIGN + amount(usd) + " in API cost"

    /** The operator's own budget LIMIT: a number they typed, not a price, so it carries no basis. */
    fun limit(usd: Double): String = SIGN + amount(usd)

    /** Two decimals in Locale.ROOT, never the locale's separator, so a figure reads the same in the
     *  status line, a refusal, the log and the perf row (V4-133). */
    fun amount(usd: Double): String = String.format(Locale.ROOT, "%.2f", usd)
}

private const val SHORT_BASIS = "API est."
private const val SIGN = "$"
