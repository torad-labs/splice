// NEW: v0.4.0 V4-13 — the bash that finds and cancels a sign-in still waiting for its browser
// callback, split from LoginHookScripts (that object sits at detekt's function ceiling).
//
// OWNERSHIP BOUNDARY: the ONE invocation bin/splice-launch emits for a login, and nothing broader.
// The shim runs `exec java -jar "$JAR" login "$HEAD" ...` with JAR resolved as
// ${SPLICE_JAR:-${SPLICE_SHARE_DIR:-$HOME/.local/share/splice}/splice.jar}. A pending sign-in is
// therefore a process that, read from /proc on Linux, satisfies all of: (1) its executable
// (/proc/PID/exe) is a binary named java; (2) argv[1] is -jar and argv[2] is that same resolved
// jar path, resolved here by the same expression in the same environment the hook will spawn the
// next login from; (3) argv[3] is login and argv[4] is a head word — the wrapper
// word the shim passes, or the topology key `splice login <key>` accepts. Fixed positions, no search:
// a JVM launched in class mode, or a JVM whose own application arguments carry `-jar ... login
// <head>`, is not ours. argv[0] is caller-supplied and is not consulted. pgrep only pre-filters.
// The liveness re-check is the same identity read, so a zombie (empty cmdline) counts as gone.
// Without /proc nothing is found and the login is spawned as before 0.4.0.
//
// ORIGIN: the hook cancels only what a hook started. Every login the hook spawns carries
// SPLICE_LOGIN_ORIGIN=hook in its environment (exec preserves it through the shim into the JVM),
// and /proc/PID/environ is the same-uid read that proves it. A matching process WITHOUT the marker
// is a sign-in the user started in a terminal: it is named and left alone (review 2026-09-14).
package splice.client.login

/** Characters that mean something in a POSIX ERE (pgrep -f): escaped when the head lands in one. */
private val PENDING_ERE_META = Regex("[^A-Za-z0-9_-]")

internal object LoginHookPending {

    /** The environment assignment every hook-started login carries, as it appears in a command line. */
    const val ORIGIN_MARKER: String = "SPLICE_LOGIN_ORIGIN=hook"

    private fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Defines `login_pids <hook|other>`, prints [foreignDecision] and exits when a sign-in the hook
     *  did not start is waiting, else cancels the hook's own (TERM, up to 2 s, then KILL, up to
     *  1 s); sets `restarted=1` when something was cancelled, or prints [stuckDecision] (a complete
     *  `printf` line) and exits when it would not die. Two-space indented: it lands inside an `if`. */
    fun cancelBlock(headWords: List<String>, stuckDecision: String, foreignDecision: String): String {
        val d = "$"
        val words = headWords.filter { it.isNotBlank() }.distinct()
        val heads = words.joinToString("|") { w -> w.replace(PENDING_ERE_META) { "\\" + it.value } }
        val prefilter = shellSingleQuote("-jar .+ login ($heads)( |$)")
        val isHead = words.joinToString(" || ") { "[ \"$d{argv[4]}\" = ${shellSingleQuote(it)} ]" }
        val alive = "[ -n \"$d(login_pids hook)\" ]"
        return buildString {
            appendLine("  login_pids() {")
            appendLine("    local pid exe argv jar origin")
            appendLine("    jar=\"$d{SPLICE_JAR:-$d{SPLICE_SHARE_DIR:-${d}HOME/.local/share/splice}/splice.jar}\"")
            appendLine("    for pid in $d(pgrep -u \"$d(id -u)\" -f -- $prefilter 2>/dev/null); do")
            appendLine("      [ \"${d}pid\" = \"$d$d\" ] && continue")
            appendLine("      mapfile -t -d '' argv < \"/proc/${d}pid/cmdline\" 2>/dev/null || continue")
            appendLine("      exe=$d(readlink \"/proc/${d}pid/exe\" 2>/dev/null) || continue")
            appendLine("      [ \"$d{exe##*/}\" = java ] || continue")
            appendLine("      [ \"$d{#argv[@]}\" -ge 5 ] || continue")
            appendLine("      [ \"$d{argv[1]}\" = -jar ] && [ \"$d{argv[2]}\" = \"${d}jar\" ] || continue")
            appendLine("      [ \"$d{argv[3]}\" = login ] && { $isHead; } || continue")
            appendLine("      origin=other")
            appendLine("      grep -qzx '$ORIGIN_MARKER' \"/proc/${d}pid/environ\" 2>/dev/null && origin=hook")
            appendLine("      [ \"${d}origin\" = \"$d{1}\" ] && printf '%s\\n' \"${d}pid\"")
            appendLine("    done")
            appendLine("  }")
            appendLine("  restarted=''")
            appendLine("  if [ -n \"$d(login_pids other)\" ]; then")
            appendLine("    $foreignDecision")
            appendLine("    exit 0")
            appendLine("  fi")
            appendLine("  pending=\"$d(login_pids hook)\"")
            appendLine("  if [ -n \"${d}pending\" ]; then")
            appendLine("    kill -TERM ${d}pending 2>/dev/null")
            appendLine("    for _ in 1 2 3 4 5 6 7 8 9 10; do $alive || break; sleep 0.2; done")
            appendLine("    if $alive; then")
            appendLine("      kill -KILL ${d}pending 2>/dev/null")
            appendLine("      for _ in 1 2 3 4 5; do $alive || break; sleep 0.2; done")
            appendLine("    fi")
            appendLine("    if $alive; then")
            appendLine("      $stuckDecision")
            appendLine("      exit 0")
            appendLine("    fi")
            appendLine("    restarted=1")
            appendLine("  fi")
        }
    }
}
