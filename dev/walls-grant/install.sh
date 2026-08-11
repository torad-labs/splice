#!/usr/bin/env bash
# Install the /grant wall-grant channel. RUN THIS YOURSELF, FROM YOUR SHELL.
#
# Why you and not the assistant: this installer edits .claude/hooks/, which is exactly what the
# wall gate blocks. That is the bootstrap — the only way to open the wall today is the env var,
# so the one-time install uses it, and after this you never need it again (/grant replaces it).
#
# Idempotent: re-running detects an existing install and re-verifies instead of double-patching.
#
#   bash dev/walls-grant/install.sh
#
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT="$PWD"
ORCH="$ROOT/.claude/hooks/orchestrator.py"
MODDIR="$ROOT/.claude/hooks/modules/userpromptsubmit"

echo "== splice /grant installer =="
echo "repo: $ROOT"

# ---------------------------------------------------------------- 1. the module
mkdir -p "$MODDIR"
cp "$ROOT/dev/walls-grant/03_grant_command.py" "$MODDIR/03_grant_command.py"
# The / menu surface. .claude/commands/ is gitignored in this repo (per-developer Claude Code
# config is deliberately not shipped), so the canonical copy lives here and is installed locally.
#
# Review 2026-07-27: because the installed copies are untracked AND outside WALL_PATHS, the file
# the operator's `/grant` keystroke actually executes could drift from the reviewed canonical one
# with no git trace and no gate. That does not enlarge what an agent can DO (Bash already covers
# direct action) — what it closes is SILENT divergence between what was reviewed and what runs.
# So: report drift LOUDLY before overwriting it, and assert the copy landed. The canonical tracked
# file is the reference; a recorded hash in .claude/state/ would be one more untracked file to
# trust, which is the same class of problem the grant record itself has.
mkdir -p "$ROOT/.claude/commands"
reconcile_menu_file() {  # <canonical> <installed>
  if [ -f "$2" ] && ! cmp -s "$1" "$2"; then
    echo "    ! MENU DRIFT: $(basename "$2") differs from its tracked canonical copy — overwriting."
    diff -u "$1" "$2" | sed 's/^/      /' || true
  fi
  cp "$1" "$2"
  cmp -s "$1" "$2" || { echo "    FAIL: could not reconcile $2 with $1"; exit 1; }
}
reconcile_menu_file "$ROOT/dev/walls-grant/grant.command.md" "$ROOT/.claude/commands/grant.md"
reconcile_menu_file "$ROOT/dev/walls-grant/install-walls.command.md" "$ROOT/.claude/commands/install-walls.md"
echo "  [1/5] installed $MODDIR/03_grant_command.py + .claude/commands/grant.md (menu files reconciled)"

# ------------------------------------------------------- 1b. the signing key
# Grants are HMAC-signed (review round 2: an unsigned record was forgeable in one Write, which
# made "operator-only" true of issuing and false of the thing the gate trusts). The key lives
# OUTSIDE the repo and is minted HERE and only here — never by the gate and never by the grant
# module. If the code that verifies a signature can also mint the key, the signature proves
# nothing: a forger writes a key and signs with it.
KEY=$(python3 -c "
import sys; sys.path.insert(0, '$ROOT/.claude/hooks')
from lib import walls_grant
print(walls_grant.create_key())
")
echo "  [1b/5] signing key at $KEY (0600, outside the repo, never committed)"

# ------------------------------------------------- 2. teach the gate about grants
# Patched with python + an explicit match assertion: a str.replace that matches nothing rewrites
# the file unchanged and exits 0, which would leave you with a /grant that appears installed and
# a gate that still blocks. Assert, never assume.
python3 - "$ORCH" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1]); s = p.read_text(encoding="utf-8")

# Idempotence must key on the SIGNED gate, not merely on "grant-aware" (2026-07-27). Keying on
# the function NAME meant a repo carrying the pre-signature helper — the one that trusts an
# unsigned walls-grant.json, forgeable in a single Write — was reported as already installed and
# silently left forgeable. An installer that skips an UPGRADE it was run to perform is the same
# failure as a str.replace that matches nothing: exits 0, changes nothing, looks installed.
if "walls_grant.active(ROOT)" in s:
    print("  [2/5] orchestrator already carries the SIGNED grant gate — skipping patch")
    sys.exit(0)

if "_walls_grant_active" in s:
    sys.exit(
        "  [2/5] FAIL: orchestrator.py carries the PRE-SIGNATURE grant helper.\n"
        "        That version trusts an UNSIGNED .claude/state/walls-grant.json, which any Write\n"
        "        can forge — the wall is open to whatever it was meant to stop.\n"
        "        Refusing to skip, and refusing to blind-replace a multi-line body. Replace\n"
        "        _walls_grant_active() by hand with the signed version (it delegates to\n"
        "        lib/walls_grant.active + .covers), add `from lib import walls_grant` and\n"
        "        NEVER_WRITABLE, then re-run this installer to prove it red/green."
    )

OLD_GUARD = '    if _is_wall_path(rel) and os.environ.get("SPLICE_WALLS_OK") != "1":'
NEW_GUARD = '''    if str(rel) in NEVER_WRITABLE:
        _emit_block(
            f"SPLICE WALLS: {rel} is the wall gate's OWN trust anchor — never a tool-write target.\\n\\n"
            "A grant is issued only from the prompt line (/grant <minutes> <reason>), which writes\\n"
            "this file itself and signs it. A tool write here would be a grant minting a grant."
        )
        return 0

    if _is_wall_path(rel) and os.environ.get("SPLICE_WALLS_OK") != "1" and not _walls_grant_active(rel):'''
assert s.count(OLD_GUARD) == 1, f"wall guard line matched {s.count(OLD_GUARD)}x, expected 1 — orchestrator.py changed shape; patch by hand"
s = s.replace(OLD_GUARD, NEW_GUARD)

ANCHOR = "def _is_wall_path(rel: Path) -> bool:"
assert s.count(ANCHOR) == 1, f"anchor matched {s.count(ANCHOR)}x, expected 1"
# KEEP THIS BODY IDENTICAL to .claude/hooks/orchestrator.py's. It only runs on a FRESH install
# (the patch is skipped once the orchestrator is already grant-aware), which is exactly why it
# rots unnoticed — a stale copy here would silently install the pre-signature, forgeable gate
# into the next repo that vendors this. Signature/expiry/scope logic itself is NOT duplicated:
# it lives once in .claude/hooks/lib/walls_grant.py and both callers delegate to it.
HELPER = '''def _walls_grant_active(rel: Path) -> bool:
    """True while a SIGNED, unexpired, in-scope operator grant covers [rel].

    A grant is issuable ONLY from a UserPromptSubmit hook, which fires only on text a human typed
    into the prompt box — an assistant emits tool calls, never a user prompt. That made ISSUING
    operator-only by construction. It said nothing about the record this gate TRUSTS: until
    2026-07-27 an assistant could Write .claude/state/walls-grant.json directly and open every
    wall without going near the module. The record is now HMAC-signed with a key outside the
    repo, and the signature is checked BEFORE `until` is read.

    Verification, expiry and scope live in lib/walls_grant so this gate and /grant's status
    report cannot drift apart. Any failure — no key, no file, bad signature, expired, out of
    scope — returns False, which keeps the wall SHUT.
    """
    grant = walls_grant.active(ROOT)
    return grant is not None and walls_grant.covers(grant, str(rel), WALL_PATHS)


'''
s = s.replace(ANCHOR, HELPER + ANCHOR)

# The gate needs the shared module, and the grant record must never be a tool-write target.
IMPORT_ANCHOR = "from pathlib import Path\n"
assert s.count(IMPORT_ANCHOR) == 1, f"import anchor matched {s.count(IMPORT_ANCHOR)}x, expected 1"
s = s.replace(
    IMPORT_ANCHOR,
    IMPORT_ANCHOR
    + "\nsys.path.insert(0, str(Path(__file__).resolve().parent))\n"
    + "from lib import walls_grant  # noqa: E402\n",
)
NW_ANCHOR = "SCAN_TIMEOUT = 20"
assert s.count(NW_ANCHOR) == 1, f"SCAN_TIMEOUT anchor matched {s.count(NW_ANCHOR)}x, expected 1"
s = s.replace(NW_ANCHOR, "NEVER_WRITABLE = (walls_grant.GRANT_REL,)\n" + NW_ANCHOR)

# The block message must name the new route, or the next blocked write sends someone off to
# relaunch the CLI for no reason.
OLD_MSG = '"SPLICE_WALLS_OK=1 — loud and never silent. Then re-run the gate\\n"'
if s.count(OLD_MSG) == 1:
    s = s.replace(OLD_MSG, '"/grant <minutes> <reason> from the prompt line (operator-only), or\\n"\n            "SPLICE_WALLS_OK=1 — loud and never silent. Then re-run the gate\\n"')
p.write_text(s, encoding="utf-8")
print("  [2/5] patched orchestrator.py (guard + _walls_grant_active + block message)")
PY

# ------------------------------------------------------------------ 3. ignore state
GI="$ROOT/.gitignore"
grep -qxF '.claude/state/walls-grant.json' "$GI" 2>/dev/null || {
  printf '\n# transient operator wall grant (/grant) — never committed\n.claude/state/walls-grant.json\n' >> "$GI"
}
echo "  [3/5] .gitignore covers the transient grant file"

# ------------------------------------------------------- 4. RED -> GREEN self-test
# Proves the gate actually changes behavior. Drives orchestrator.py's pretooluse lifecycle with a
# real hook event, exactly as Claude Code does.
#
# NB the oracle: this hook signals a deny as {"decision":"block"} JSON on STDOUT with EXIT 0 (the
# legacy path, not exit-2). An exit-code-based check therefore reports "allowed" for every blocked
# write and the whole self-test passes vacuously — caught while simulating this installer against a
# copy of orchestrator.py rather than trusting it.
echo "  [4/5] self-test:"
EVENT='{"tool_name":"Write","cwd":"'"$ROOT"'","tool_input":{"file_path":"'"$ROOT"'/.rules/rules/__grant_probe.yml","content":"id: x\nlanguage: yaml\nrule:\n  pattern: x\n"}}'
GRANT="$ROOT/.claude/state/walls-grant.json"

# PRESERVE A LIVE GRANT ACROSS THE SELF-TEST (2026-07-27). The arms below overwrite and then
# delete $GRANT, so re-running this installer mid-session silently REVOKED whatever the operator
# had issued — their next wall write just started failing with no explanation, and nothing said
# the installer had done it. Restoring is safe precisely because grants are signed: this can put
# back only the exact bytes the operator's own grant already had, never mint or extend one.
SAVED=""
if [ -f "$GRANT" ]; then
  SAVED="$(mktemp -t walls-grant-saved.XXXXXX)"
  cp "$GRANT" "$SAVED"
fi
restore_grant() {
  if [ -n "$SAVED" ]; then
    mkdir -p "$(dirname "$GRANT")"
    cp "$SAVED" "$GRANT"
    rm -f "$SAVED"
    echo "    (restored the grant that was active before this run)"
  else
    rm -f "$GRANT"
  fi
}
# ONE EXIT trap for the whole script: bash keeps only the LAST `trap ... EXIT`, so a second
# `trap` later would SILENTLY replace this one and stop restoring the operator's grant. Later
# steps register their cleanup here instead of declaring their own.
MENU_TMP=""
cleanup_on_exit() {
  restore_grant
  [ -n "$MENU_TMP" ] && rm -rf "$MENU_TMP"
  return 0
}
trap cleanup_on_exit EXIT

rm -f "$GRANT"

# Match the WALL block specifically, not "any block". The orchestrator can also fail closed with
# HOOK POLICY INCOMPLETE when the scan toolchain is broken, and a coarse '"decision": "block"'
# check reads that as "the wall held" — the ALLOW arm then fails for a reason that has nothing to
# do with grants. Three outcomes, named.
run_gate() {
  local out
  out="$(echo "$EVENT" | python3 "$ORCH" pretooluse 2>/dev/null)"
  if grep -q 'SPLICE WALLS' <<<"$out"; then echo BLOCK
  elif grep -q 'HOOK POLICY INCOMPLETE' <<<"$out"; then echo INFRA
  else echo ALLOW; fi
}

[ "$(run_gate)" = "BLOCK" ] || { echo "    FAIL: expected BLOCK with no grant"; exit 1; }
echo "    RED   ok — wall path blocked with no grant"

mkdir -p "$(dirname "$GRANT")"

# Writes a SIGNED grant record. `sign` is the same function the module uses; a self-test that
# hand-rolled the JSON would pass while the real channel was broken.
write_grant() {  # <seconds-from-now> <reason> [paths-csv]
  python3 -c "
import json, sys, time
sys.path.insert(0, '$ROOT/.claude/hooks')
from lib import walls_grant
payload = {'until': time.time() + float(sys.argv[2]), 'reason': sys.argv[3]}
if len(sys.argv) > 4 and sys.argv[4]:
    payload['paths'] = sys.argv[4].split(',')
open(sys.argv[1], 'w').write(json.dumps(walls_grant.sign(payload)))
" "$GRANT" "$1" "$2" "${3:-}"
}

write_grant 600 'installer self-test'
case "$(run_gate)" in
  ALLOW) echo "    GREEN ok — wall path allowed with an active SIGNED grant" ;;
  INFRA) echo "    FAIL: grant passed the wall, but the ast-grep scan failed closed."
         echo "          That is a toolchain problem, not a grant problem — is ast-grep on PATH?"
         rm -f "$GRANT"; exit 1 ;;
  *)     echo "    FAIL: expected ALLOW with an active grant"; rm -f "$GRANT"; exit 1 ;;
esac

write_grant -1 'expired'
[ "$(run_gate)" = "BLOCK" ] || { echo "    FAIL: an EXPIRED grant must not hold the wall open"; rm -f "$GRANT"; exit 1; }
echo "    RED   ok — expired grant does not hold the wall open"

# THE BLOCKER'S RED CASE (review round 2). Before signing, this exact write — an unsigned record
# with a future `until` — opened every wall. It is what an assistant could produce in ONE Write
# without ever reaching the operator-only module. If this arm ever goes green again, the whole
# grant channel is back to being policy rather than structure.
python3 -c "import json,time,sys; open(sys.argv[1],'w').write(json.dumps({'until': time.time()+600, 'reason':'FORGED — no signature'}))" "$GRANT"
[ "$(run_gate)" = "BLOCK" ] || { echo "    FAIL: an UNSIGNED grant opened the wall — the forgery hole is back"; rm -f "$GRANT"; exit 1; }
echo "    RED   ok — unsigned (forged) grant does not open the wall"

# …and a signed record whose payload was edited afterwards must fail the same way.
write_grant 600 'tamper base'
python3 -c "
import json, sys
p = sys.argv[1]; g = json.load(open(p)); g['until'] = g['until'] + 86400  # extend by hand
open(p, 'w').write(json.dumps(g))
" "$GRANT"
[ "$(run_gate)" = "BLOCK" ] || { echo "    FAIL: a hand-extended grant opened the wall — the signature is not being checked"; rm -f "$GRANT"; exit 1; }
echo "    RED   ok — hand-extended (tampered) grant does not open the wall"

# SCOPE: a grant naming only .claude/hooks must not open .rules (the probe target is .rules/).
write_grant 600 'scoped elsewhere' '.claude/hooks'
[ "$(run_gate)" = "BLOCK" ] || { echo "    FAIL: a grant scoped to .claude/hooks opened a .rules write"; rm -f "$GRANT"; exit 1; }
echo "    RED   ok — scoped grant does not open a wall path it did not name"
write_grant 600 'scoped correctly' '.rules'
[ "$(run_gate)" = "ALLOW" ] || { echo "    FAIL: a grant scoped to .rules did not open a .rules write"; rm -f "$GRANT"; exit 1; }
echo "    GREEN ok — scoped grant opens exactly the path it named"
rm -f "$GRANT"

# END-TO-END through the MODULE, not a hand-written grant file. The three checks above all write
# $GRANT directly, so they pass even if the module writes its grant somewhere else entirely —
# which it did (parents[3] resolved to .claude/, one level short of the repo root), reporting
# ACTIVE while the gate kept blocking. Only an issue-then-open assertion catches that.
python3 - "$MODDIR/03_grant_command.py" <<'PY' >/dev/null
import importlib.util, sys
spec = importlib.util.spec_from_file_location("g", sys.argv[1])
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
m.run({"prompt": "/grant 5 installer end-to-end self-test", "hook_event_name": "UserPromptSubmit"})
PY
[ -f "$GRANT" ] || { echo "    FAIL: the module wrote its grant somewhere other than $GRANT"; exit 1; }
[ "$(run_gate)" = "ALLOW" ] || { echo "    FAIL: a module-issued grant did not open the wall"; rm -f "$GRANT"; exit 1; }
echo "    GREEN ok — module-issued grant opens the wall (paths agree end-to-end)"
rm -f "$GRANT"

# ------------------------------------------- 5. THE MENU BANG-LINE ITSELF
# Added 2026-07-28 after the gap it covers bit live. Everything above drives the GATE end to end;
# NOTHING executed the one line the operator actually triggers. So a syntax error in `/grant`'s
# bang-line — the operator's ONLY status surface — shipped with every arm above green, and was
# found by an operator typing a reason containing parentheses and getting a raw bash trace.
#
# THE SUBSTITUTION MODEL IS THE WHOLE POINT. Claude Code replaces the arguments placeholder
# TEXTUALLY and then bash parses the result. Passing arguments as an environment variable would
# NOT reproduce the failure: `(` inside an env value is inert, while `(` pasted into the source
# text is a parse error that kills the line before any fallback branch can run. This harness
# substitutes textually, exactly as the real thing does — otherwise it would pass vacuously against
# the very bug it exists to catch (verified: it goes red against the pre-fix unquoted line).
echo "  [5/5] menu bang-line self-test:"
MENU="$ROOT/.claude/commands/grant.md"
BANG="$(grep -o '^!`.*`$' "$MENU" | sed 's/^!`//; s/`$//')"
[ -n "$BANG" ] || { echo "    FAIL: no bang-line found in $MENU"; exit 1; }

MENU_TMP="$(mktemp -d)"   # removed by cleanup_on_exit; the FAIL arms below exit non-zero
run_menu() {  # <args-text> ; substitutes textually, then executes, exactly as Claude Code does
  python3 - "$BANG" "$1" <<'PYEOF' > "$MENU_TMP/cmd.sh" 2>/dev/null
import sys
sys.stdout.write(sys.argv[1].replace("$ARGUMENTS", sys.argv[2]))
PYEOF
  # `|| true` is load-bearing under `set -e`: a bang-line with a SYNTAX error makes bash exit 2,
  # which would abort the installer at the assignment below — before the FAIL message that names
  # what broke ever prints. classify() is the judge here, never the exit status.
  bash "$MENU_TMP/cmd.sh" 2>&1 || true
}

classify() {  # ALLOW-shaped classification of the menu output, three named outcomes
  if grep -q 'NOT INSTALLED' <<<"$1"; then echo ABSENT
  elif grep -q 'STATUS UNAVAILABLE' <<<"$1"; then echo BROKEN
  elif grep -q '§grant' <<<"$1"; then echo STATUS
  else echo SHELL_ERROR; fi
}

out="$(run_menu '')"
[ "$(classify "$out")" = "STATUS" ] || { echo "    FAIL: healthy install did not report status"; echo "$out" | sed 's/^/      /'; exit 1; }
echo "    GREEN ok — healthy install reports grant status"

# THE REGRESSION ARM. A reason carrying shell metacharacters must not kill the line. Unquoted,
# this is a parse error and classify() returns SHELL_ERROR because not one branch got to run.
out="$(run_menu '15 --paths .rules close the gap (review of #62) & now; done `x`')"
[ "$(classify "$out")" = "STATUS" ] || { echo "    FAIL: a reason containing ( ) ; & or a backtick broke the bang-line"; echo "$out" | sed 's/^/      /'; exit 1; }
echo "    GREEN ok — reason text with shell metacharacters is inert"

probe="$(mktemp -d)"
out="$(cd "$probe" && run_menu '')"
[ "$(classify "$out")" = "ABSENT" ] || { echo "    FAIL: a missing module must report NOT INSTALLED, got: $(classify "$out")"; exit 1; }
echo "    RED   ok — missing module reports NOT INSTALLED"

mkdir -p "$probe/.claude/hooks/modules/userpromptsubmit"
printf 'import sys; sys.exit(3)\n' > "$probe/.claude/hooks/modules/userpromptsubmit/03_grant_command.py"
out="$(cd "$probe" && run_menu '')"
[ "$(classify "$out")" = "BROKEN" ] || { echo "    FAIL: a broken module must report STATUS UNAVAILABLE, not NOT INSTALLED"; exit 1; }
echo "    RED   ok — broken module is distinguished from a missing one"
rm -rf "$probe"

echo
echo "== installed =="
python3 "$ROOT/.claude/hooks/tests/test_orchestrator.py" 2>&1 | tail -3
echo
echo "Now, in Claude Code:   /grant 45 land the walls audit"
echo "Check state:           /grant"
echo "End early:             /grant revoke"
