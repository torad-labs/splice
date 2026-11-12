// The exec recipe in the line form the POSIX launch shim reads with `read`, no JSON parser and no eval: one record per
// line, a letter, a space, then the value. A value's backslash, newline and carriage return are written \\, \n and \r,
// so any argv word or environment value is one line; the shim undoes exactly those three.
package splice.launch.recipe

import splice.launch.LaunchRecipe

internal object LaunchRecipeLines {
    /** The media type a shim names in Accept to be answered in this form. */
    const val RECIPE_MEDIA_TYPE = "text/x-splice-launch"

    /** The first line of every answer in this form, so a shim can tell it from an error body or another listener's. */
    const val RECIPE_HEADER = "splice-launch 1"

    /** The last line, so a recipe cut short is refused instead of exec'd with words missing. */
    const val RECIPE_END = "end"

    fun render(recipe: LaunchRecipe): String = buildString {
        append(RECIPE_HEADER).append('\n')
        recipe.unset.forEach { append("U ").append(it).append('\n') }
        recipe.env.forEach { (name, value) -> append("E ").append(name).append('=').append(escape(value)).append('\n') }
        recipe.argv.forEach { append("A ").append(escape(it)).append('\n') }
        recipe.warning?.let { append("W ").append(escape(it)).append('\n') }
        append(RECIPE_END).append('\n')
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
}
