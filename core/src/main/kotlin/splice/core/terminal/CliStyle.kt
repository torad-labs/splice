// PORT-OF: app/cli/CliStyle.kt — the shared ANSI palette, with unchanged bytes across CLI features.
package splice.core.terminal

public const val RESET: String = "\u001B[0m"
public const val DIM: String = "\u001B[2m"
public const val BOLD: String = "\u001B[1m"
public const val GREEN: String = "\u001B[32m"
public const val YELLOW: String = "\u001B[33m"
public const val RED: String = "\u001B[31m"
public const val CYAN: String = "\u001B[36m"
public const val BLACK: String = "\u001B[30m"
public const val BG_CYAN: String = "\u001B[46m"
