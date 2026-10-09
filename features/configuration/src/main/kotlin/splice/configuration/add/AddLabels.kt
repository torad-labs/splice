package splice.configuration.add

/** How a profile describes itself: the [summary] line the wizard lists and the [origin] its TOML comment and the
 *  verb's title name. */
internal data class AddLabels(
    val summary: String,
    /** What added the row; null means a catalogue row, added by `splice add <name>`. A runtime-described row
     *  (RuntimeHeadAdd) is not in this catalogue, so naming a verb that cannot reproduce it would send the
     *  operator to a command that fails. */
    val origin: String? = null,
)
