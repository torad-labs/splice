package splice.app.desktop.setup

/** File paths are shell words in both scripts and hook commands, never shell source. */
internal object DesktopShell {
    fun word(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun script(body: String, shell: String, variables: Map<String, String>): String = buildString {
        appendLine("#!$shell")
        appendLine("set +x")
        variables.forEach { (name, value) -> appendLine("$name=${word(value)}") }
        append(checkNotNull(Thread.currentThread().contextClassLoader.getResource(body)).readText())
    }
}
