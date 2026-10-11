// NEW: v0.4.0 V4-13 — the bash that finds and cancels a sign-in still waiting for its browser
// callback, split from LoginHookScripts (that object sits at detekt's function ceiling).
//
// OWNERSHIP BOUNDARY: the ONE invocation app/src/main/dist/bin/splice-launch emits for a login, and nothing broader.
// The shim runs `exec java <launcher -D properties> -jar "$JAR" login "$HEAD" ...` with JAR resolved as
// ${SPLICE_JAR:-${SPLICE_SHARE_DIR:-$HOME/.local/share/splice}/splice.jar}. A pending sign-in is
// therefore a process that, read from /proc on Linux, satisfies all of: (1) its executable
// (/proc/PID/exe) is a binary named java; (2) argv[1] is -jar and argv[2] is that same resolved
// jar path after only launcher JVM properties, resolved in the same environment the hook uses;
// (3) login follows that jar, with one head word and optional --label/--discard in either order.
// The head is the wrapper word or topology key. Anchored JVM prefix, never an application-argv search:
// a JVM launched in class mode, or a JVM whose own application arguments carry `-jar ... login
// <head>`, is not ours. argv[0] is caller-supplied and is not consulted. pgrep only pre-filters.
// The liveness re-check is the same identity read, so a zombie (empty cmdline) counts as gone.
// On macOS a narrow CLI helper inspects ProcessHandle argv and validates launcher ownership records.
// An unknown origin is foreign, never permission to cancel. A failed helper refuses a duplicate login.
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
        val prefilter = shellSingleQuote("-jar .+ login .*($heads)( |$)")
        val isHead = words.joinToString(" || ") { "[ \"${d}head\" = ${shellSingleQuote(it)} ]" }
        val alive = "[ -n \"$d(login_pids hook)\" ]"
        return buildString {
            append(portableBlock(words, stuckDecision, foreignDecision))
            appendLine("  else")
            append(linuxPids(prefilter, isHead))
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
            appendLine("  fi")
        }
    }

    private fun linuxPids(prefilter: String, isHead: String): String {
        val d = "$"
        return """
          login_pids() {
            local pid exe argv jar origin word head skip
            jar="$d{SPLICE_JAR:-$d{SPLICE_SHARE_DIR:-${d}HOME/.local/share/splice}/splice.jar}"
            for pid in $d(pgrep -u "$d(id -u)" -f -- $prefilter 2>/dev/null); do
              [ "${d}pid" = "$d$d" ] && continue
              argv=()
              [ -r "/proc/${d}pid/cmdline" ] || continue
              while IFS= read -r -d '' word; do argv+=("${d}word"); done < "/proc/${d}pid/cmdline" 2>/dev/null
              while [ "$d{argv[1]:-}" != -jar ] && [[ "$d{argv[1]:-}" = -D* ]]; do
                argv=("$d{argv[0]}" "$d{argv[@]:2}")
              done
              exe=$d(readlink "/proc/${d}pid/exe" 2>/dev/null) || continue
              [ "$d{exe##*/}" = java ] || continue
              [ "$d{#argv[@]}" -ge 5 ] || continue
              [ "$d{argv[1]}" = -jar ] && [ "$d{argv[2]}" = "${d}jar" ] || continue
              [ "$d{argv[3]}" = login ] || continue
              head=''; skip=''
              for word in "$d{argv[@]:4}"; do
                if [ -n "${d}skip" ]; then skip=''; continue; fi
                case "${d}word" in
                  --label) skip=1 ;;
                  --discard) ;;
                  *) [ -z "${d}head" ] || { head=''; break; }; head="${d}word" ;;
                esac
              done
              { $isHead; } || continue
              origin=other
              grep -qzx '$ORIGIN_MARKER' "/proc/${d}pid/environ" 2>/dev/null && origin=hook
              [ "${d}origin" = "$d{1}" ] && printf '%s\n' "${d}pid"
            done
          }
        """.trimIndent() + "\n"
    }

    private fun portableBlock(words: List<String>, stuckDecision: String, foreignDecision: String): String {
        val d = "$"
        val heads = words.joinToString(" ", transform = ::shellSingleQuote)
        return """
          if [ "$d(uname -s)" = Darwin ]; then
            restarted=''
            jar="$d{SPLICE_JAR:-$d{SPLICE_SHARE_DIR:-${d}HOME/.local/share/splice}/splice.jar}"
            pending="$d(java -jar "${d}jar" pending-login $heads)" || pending=Stuck
            case "${d}pending" in
              Clear) ;;
              Restarted) restarted=1 ;;
              Foreign) $foreignDecision; exit 0 ;;
              *) $stuckDecision; exit 0 ;;
            esac
        """.trimIndent() + "\n"
    }
}
