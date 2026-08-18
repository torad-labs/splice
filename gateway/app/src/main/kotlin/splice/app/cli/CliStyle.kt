// NEW: (split from DoctorCommand.kt, which sits at detekt's 14-function file budget) the seven
// ANSI escapes the CLI renders with, declared once. DoctorCommand.kt, StatusCommand.kt and
// SetupCommand.kt each carried a private copy — 18 declarations of 7 values — so the palette could
// drift twice without anything noticing. `private` is FILE-private in Kotlin, so sharing them means
// `internal`; they are constants, which kt-no-top-level-functions exempts in as many words
// ("Top-level `val`/`const val` are untouched — this rule is about behaviour, not constants").
package splice.app.cli

internal const val RESET = "\u001B[0m"
internal const val DIM = "\u001B[2m"
internal const val BOLD = "\u001B[1m"
internal const val GREEN = "\u001B[32m"
internal const val YELLOW = "\u001B[33m"
internal const val RED = "\u001B[31m"
internal const val CYAN = "\u001B[36m"
