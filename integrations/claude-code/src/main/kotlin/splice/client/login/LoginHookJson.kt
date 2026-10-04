// NEW: v0.4.0 V4-13 — the bash that reads the TOP-LEVEL "prompt" string out of the hook's JSON
// input, decoding it. The hook used to pattern-match the raw JSON, which is not the prompt: field
// order, nesting and escapes (a JSON-escaped tab, or a backslash-solidus spelling of /login) all
// changed what it saw. This scanner walks the bytes once, tracks depth, decodes string escapes
// (\n \t \r \b \f and the quoted pair; \uXXXX is kept literal, it can spell neither /login nor a
// label) and returns the value of the depth-1 "prompt" key, whatever comes before or after it. No
// jq, no python: the hook's own no-dependency rule. Byte-wise under LC_ALL=C so a long paste costs
// one pass; the caller skips the scan unless the raw input mentions /login or the sentinel at all.
package splice.client.login

/** The bash text with `%D%` standing for `$`, so the Kotlin raw string carries no templates. */
private val SCANNER = """
    |json_prompt() {
    |  local LC_ALL=C
    |  local s=%D%input n c depth=0 instr='' esc='' key='' cur='' expect=key i=0
    |  n=%D%{#s}
    |  while (( i < n )); do
    |    c=%D%{s:i:1}
    |    if [ -n "%D%instr" ]; then
    |      if [ -n "%D%esc" ]; then
    |        case %D%c in
    |          n) cur+=%D%'\n' ;;
    |          t) cur+=%D%'\t' ;;
    |          r) cur+=%D%'\r' ;;
    |          b) cur+=%D%'\b' ;;
    |          f) cur+=%D%'\f' ;;
    |          u) cur+="\\u%D%{s:i+1:4}"; i=%D%((i + 4)) ;;
    |          *) cur+=%D%c ;;
    |        esac
    |        esc=''
    |      elif [ "%D%c" = '\' ]; then esc=1
    |      elif [ "%D%c" = '"' ]; then
    |        instr=''
    |        if [ "%D%depth" = 1 ]; then
    |          if [ "%D%expect" = key ]; then key=%D%cur
    |          elif [ "%D%key" = prompt ]; then prompt=%D%cur; return 0
    |          fi
    |        fi
    |      else cur+=%D%c
    |      fi
    |    else
    |      case %D%c in
    |        '"') instr=1 cur='' ;;
    |        '{' | '[') depth=%D%((depth + 1)) ;;
    |        '}' | ']') depth=%D%((depth - 1)) ;;
    |        ':') [ "%D%depth" = 1 ] && expect=value ;;
    |        ',') [ "%D%depth" = 1 ] && expect=key ;;
    |      esac
    |    fi
    |    i=%D%((i + 1))
    |  done
    |  return 1
    |}
""".trimMargin() + "\n"

internal object LoginHookJson {
    /** Defines `json_prompt`: reads `$input`, sets `$prompt` and returns 0 when a top-level prompt
     *  string exists, 1 otherwise. */
    fun scanner(): String = SCANNER.replace("%D%", "$")
}
