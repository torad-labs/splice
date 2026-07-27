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
mkdir -p "$ROOT/.claude/commands"
cp "$ROOT/dev/walls-grant/grant.command.md" "$ROOT/.claude/commands/grant.md"
echo "  [1/4] installed $MODDIR/03_grant_command.py + .claude/commands/grant.md"

# ------------------------------------------------- 2. teach the gate about grants
# Patched with python + an explicit match assertion: a str.replace that matches nothing rewrites
# the file unchanged and exits 0, which would leave you with a /grant that appears installed and
# a gate that still blocks. Assert, never assume.
python3 - "$ORCH" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1]); s = p.read_text(encoding="utf-8")

if "_walls_grant_active" in s:
    print("  [2/4] orchestrator already grant-aware — skipping patch")
    sys.exit(0)

OLD_GUARD = '    if _is_wall_path(rel) and os.environ.get("SPLICE_WALLS_OK") != "1":'
NEW_GUARD = '    if _is_wall_path(rel) and os.environ.get("SPLICE_WALLS_OK") != "1" and not _walls_grant_active():'
assert s.count(OLD_GUARD) == 1, f"wall guard line matched {s.count(OLD_GUARD)}x, expected 1 — orchestrator.py changed shape; patch by hand"
s = s.replace(OLD_GUARD, NEW_GUARD)

ANCHOR = "def _is_wall_path(rel: Path) -> bool:"
assert s.count(ANCHOR) == 1, f"anchor matched {s.count(ANCHOR)}x, expected 1"
HELPER = '''def _walls_grant_active() -> bool:
    """True while an operator-issued /grant is live (see modules/userpromptsubmit/03_grant_command.py).

    A grant is issuable ONLY from a UserPromptSubmit hook, which fires only on text a human typed
    into the prompt box — an assistant emits tool calls, never a user prompt. So this stays
    operator-only by construction, not by policy. Expiry is enforced HERE, at the gate, so a stale
    file can never hold the wall open; the grant module reports the same rule.
    """
    try:
        raw = json.loads((ROOT / ".claude/state/walls-grant.json").read_text(encoding="utf-8"))
        return float(raw.get("until", 0)) > __import__("time").time()
    except (OSError, ValueError, TypeError):
        return False


'''
s = s.replace(ANCHOR, HELPER + ANCHOR)

# The block message must name the new route, or the next blocked write sends someone off to
# relaunch the CLI for no reason.
OLD_MSG = '"SPLICE_WALLS_OK=1 — loud and never silent. Then re-run the gate\\n"'
if s.count(OLD_MSG) == 1:
    s = s.replace(OLD_MSG, '"/grant <minutes> <reason> from the prompt line (operator-only), or\\n"\n            "SPLICE_WALLS_OK=1 — loud and never silent. Then re-run the gate\\n"')
p.write_text(s, encoding="utf-8")
print("  [2/4] patched orchestrator.py (guard + _walls_grant_active + block message)")
PY

# ------------------------------------------------------------------ 3. ignore state
GI="$ROOT/.gitignore"
grep -qxF '.claude/state/walls-grant.json' "$GI" 2>/dev/null || {
  printf '\n# transient operator wall grant (/grant) — never committed\n.claude/state/walls-grant.json\n' >> "$GI"
}
echo "  [3/4] .gitignore covers the transient grant file"

# ------------------------------------------------------- 4. RED -> GREEN self-test
# Proves the gate actually changes behavior. Drives orchestrator.py's pretooluse lifecycle with a
# real hook event, exactly as Claude Code does.
#
# NB the oracle: this hook signals a deny as {"decision":"block"} JSON on STDOUT with EXIT 0 (the
# legacy path, not exit-2). An exit-code-based check therefore reports "allowed" for every blocked
# write and the whole self-test passes vacuously — caught while simulating this installer against a
# copy of orchestrator.py rather than trusting it.
echo "  [4/4] self-test:"
EVENT='{"tool_name":"Write","cwd":"'"$ROOT"'","tool_input":{"file_path":"'"$ROOT"'/.rules/rules/__grant_probe.yml","content":"id: x\nlanguage: yaml\nrule:\n  pattern: x\n"}}'
GRANT="$ROOT/.claude/state/walls-grant.json"
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
python3 -c "import json,time,sys; open(sys.argv[1],'w').write(json.dumps({'until': time.time()+600, 'reason':'installer self-test'}))" "$GRANT"
case "$(run_gate)" in
  ALLOW) echo "    GREEN ok — wall path allowed with an active grant" ;;
  INFRA) echo "    FAIL: grant passed the wall, but the ast-grep scan failed closed."
         echo "          That is a toolchain problem, not a grant problem — is ast-grep on PATH?"
         rm -f "$GRANT"; exit 1 ;;
  *)     echo "    FAIL: expected ALLOW with an active grant"; rm -f "$GRANT"; exit 1 ;;
esac

python3 -c "import json,time,sys; open(sys.argv[1],'w').write(json.dumps({'until': time.time()-1, 'reason':'expired'}))" "$GRANT"
[ "$(run_gate)" = "BLOCK" ] || { echo "    FAIL: an EXPIRED grant must not hold the wall open"; rm -f "$GRANT"; exit 1; }
echo "    RED   ok — expired grant does not hold the wall open"
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

echo
echo "== installed =="
python3 "$ROOT/.claude/hooks/tests/test_orchestrator.py" 2>&1 | tail -3
echo
echo "Now, in Claude Code:   /grant 45 land the walls audit"
echo "Check state:           /grant"
echo "End early:             /grant revoke"
