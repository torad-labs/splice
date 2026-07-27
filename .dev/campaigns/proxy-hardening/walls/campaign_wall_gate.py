#!/usr/bin/env python3
"""campaign_wall_gate — an unwalled campaign item is structurally inexpressible.

WHAT  The proxy-hardening campaign's enforcement census. Items must name an enforcing WALL, laws
      must name an enforcing CHECKER, walls must be real and honest, and fences must not collide.

LAW   #924 ("you make drift not compile") + #954 (all-red-then-green: "the error list IS the
      complete, honest work inventory; green is EARNED, never protected"). Ported from qgre's
      dev/gates/law-wall-registry-gate.py.

SEVERITY IS SPLIT (2026-07-26 review finding #2). Lumping "79 walls still to build" together with
      "a wall is lying" forced the whole gate out of `npm run gate`, which meant NOTHING ran it and
      a false-green could sit undetected forever. Now:

        BLOCKING  — never acceptable, safe to wire into `npm run gate` today:
                    C1 census   an item has zero or >1 registry rows
                    C2 orphan   a registry row names no live item
                    C3 missing  a named wall is not on disk
                    C5 polarity a todo item's wall PASSES (vacuous) / a done item's wall FAILS
                    C6 control  a wall does not pass its OWN --selftest (positive control)
                    C10 live    two IN_FLIGHT items own the same file (real concurrent writers)
                    C11 unverif a wall could not be run inside the total budget — its polarity is
                                UNKNOWN, which must never be reported as a pass (review finding #8)
                    C9 law      a law enforcer FAILS — the law is being broken right now
        ADVISORY  — the standing worklist, reported but non-blocking:
                    C4 unwalled an item has no wall yet
                    C7 fence    two TODO items in one phase share a derived fence. Advisory on
                                purpose: fences here are superset heuristics to be narrowed by
                                `edit-fence` at claim time, and an unclaimed item cannot write
                                anything, so it cannot collide yet. C10 is the real hazard.
                    C8 unlawed  a law has no mechanical enforcer

      Exit 0 = no BLOCKING findings. `--strict` makes advisory blocking too (the end state).

THE POLARITY LAW (C5): a wall for a todo item MUST FAIL. A wall that passes while its item is
      unfinished is VACUOUS — it does not detect the gap it claims to guard.

THE POSITIVE CONTROL (C6, review finding #1): polarity alone is not enough. A wall that is merely
      `sys.exit(1)` also "fails on a todo item", so it looks honest for the campaign's whole life
      and only betrays you at the moment you mark the item done. PROVEN 2026-07-26 by registering a
      do-nothing wall: it counted as `walled` and produced zero findings. So every wall must also
      prove it CAN go green — `<wall> --selftest` runs its detection against synthetic
      closed-gap/open-gap inputs and must exit 0. Red-green, applied to the walls themselves.

RUN   npm run gate:campaign            blocking only (safe for the main ladder)
      npm run gate:campaign:strict     blocking + advisory (the worklist view)
      npm run gate:campaign:census     census only, no wall execution — EXIT 2, never 0, so it
                                       can never be mistaken in CI for a passing polarity run
      npm run gate:campaign:selftest   this gate's own red/green fixtures

LEDGER SOURCE  `manifest.py <board> list` (concept #945: the CLI is the only sanctioned channel),
      cross-checked against the raw TOML item count so a silent parse drift cannot fake a pass.
"""

from __future__ import annotations

import argparse
import pathlib
import time
import subprocess
import sys
import tempfile
import tomllib

ROOT = pathlib.Path(__file__).resolve().parents[4]
CAMPAIGN = ROOT / "dev" / "campaigns" / "proxy-hardening"
REGISTRY = CAMPAIGN / "walls" / "wall_registry.toml"
LAW_REGISTRY = CAMPAIGN / "walls" / "law_registry.toml"
BOARD = ROOT / "dev" / "campaigns" / "proxy-hardening.toml"
MANIFEST = ROOT / "dev" / "campaigns" / "manifest.py"

RED_STATUSES = {"todo", "in_flight"}
GREEN_STATUSES = {"done", "verified"}
WALL_TIMEOUT_S = 120
TOTAL_WALL_BUDGET_S = 900

BLOCKING = "BLOCK"
ADVISORY = "ADVIS"
SEVERITY = {"C1": BLOCKING, "C2": BLOCKING, "C3": BLOCKING, "C4": ADVISORY,
            "C5": BLOCKING, "C6": BLOCKING, "C7": ADVISORY, "C8": ADVISORY, "C9": BLOCKING, "C10": BLOCKING, "C11": BLOCKING}


class Finding(str):
    pass


def _code(f: str) -> str:
    return str(f).split()[0]


# ── inputs ───────────────────────────────────────────────────────────────────

def ledger_items(board: pathlib.Path = BOARD) -> dict[str, tuple[str, str]]:
    """id -> (phase, status) via the manifest CLI, cross-checked against the raw TOML count."""
    proc = subprocess.run(
        [sys.executable, str(MANIFEST), str(board.relative_to(ROOT)), "list"],
        cwd=ROOT, capture_output=True, text=True, timeout=120,
    )
    if proc.returncode != 0:
        raise SystemExit(f"campaign_wall_gate: manifest list failed: {proc.stderr.strip()[:400]}")
    out: dict[str, tuple[str, str]] = {}
    for line in proc.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 3 and "-" in parts[0] and parts[0][:2].isalpha():
            out[parts[0]] = (parts[1], parts[2])
    # Review finding #10: the CLI listing is whitespace-columnar and could drift. Refuse to run on
    # a parse that disagrees with the ledger itself rather than emit confident, wrong verdicts.
    truth = len(tomllib.loads(board.read_text(encoding="utf-8")).get("items", []))
    if len(out) != truth:
        raise SystemExit(
            f"campaign_wall_gate: parsed {len(out)} items from `manifest list` but the ledger has "
            f"{truth}. The listing format drifted — fix the parser, do not trust this run.")
    if not out:
        raise SystemExit("campaign_wall_gate: no items — refusing to pass vacuously")
    return out


def rows_of(path: pathlib.Path, key: str) -> list[dict]:
    if not path.exists():
        return []
    return [r for r in tomllib.loads(path.read_text(encoding="utf-8")).get(key, []) if isinstance(r, dict)]


def item_fences(board: pathlib.Path = BOARD) -> dict[str, list[str]]:
    data = tomllib.loads(board.read_text(encoding="utf-8"))
    return {i["id"]: list(i.get("files", [])) for i in data.get("items", [])}


# ── wall execution ───────────────────────────────────────────────────────────

def _resolve(wall: str) -> pathlib.Path:
    p = pathlib.Path(wall)
    return p if p.is_absolute() else (ROOT / p)


def run_wall(wall: str, *, selftest: bool = False, timeout: int = WALL_TIMEOUT_S) -> tuple[int, str]:
    target = _resolve(wall)
    cmd = ["bash", str(target)] if target.suffix == ".sh" else [sys.executable, str(target)]
    if selftest:
        cmd.append("--selftest")
    try:
        proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return 124, f"timed out after {timeout}s"
    tail = (proc.stdout + proc.stderr).strip().splitlines()
    return proc.returncode, (tail[-1][:190] if tail else "")


# ── the audit ────────────────────────────────────────────────────────────────

def audit(items, rows, *, fences=None, laws=None, run_polarity=True, run_controls=True,
          budget_s: float = TOTAL_WALL_BUDGET_S):
    findings: list[Finding] = []
    budget = {"left": float(budget_s)}

    def spend(wall: str, *, selftest: bool = False) -> tuple[int, str] | None:
        """Run a wall against the shared budget. None = not run (caller must raise C11, never pass)."""
        if budget["left"] <= 0:
            return None
        t0 = time.monotonic()
        out = run_wall(wall, selftest=selftest, timeout=int(max(5, min(WALL_TIMEOUT_S, budget["left"]))))
        budget["left"] -= time.monotonic() - t0
        return out
    by_id: dict[str, list[dict]] = {}
    for r in rows:
        by_id.setdefault(str(r.get("id", "")), []).append(r)

    for item_id in sorted(items):
        n = len(by_id.get(item_id, []))
        if n == 0:
            findings.append(Finding(f"C1 UNREGISTERED  {item_id}: in the ledger, absent from wall_registry.toml"))
        elif n > 1:
            findings.append(Finding(f"C1 AMBIGUOUS     {item_id}: {n} registry rows share this id"))
    for row_id in sorted(by_id):
        if row_id not in items:
            findings.append(Finding(
                f"C2 ORPHAN        {row_id}: registry row names no ledger item. A row may NEVER be "
                "deleted to silence this — fix the id or the ledger."))

    stats = {"total": len(items), "unwalled": 0, "walled": 0, "green": 0,
             "vacuous": 0, "false_green": 0, "uncontrolled": 0, "laws": 0, "unlawed": 0, "lawed": 0, "law_violations": 0}

    for item_id, (_phase, status) in sorted(items.items()):
        rs = by_id.get(item_id, [])
        if len(rs) != 1:
            continue
        wall = str(rs[0].get("wall", "")).strip()
        if not wall:
            stats["unwalled"] += 1
            findings.append(Finding(
                f"C4 UNWALLED      {item_id} [{status}]: no wall. The fix is BUILDING THE WALL, not editing the row."))
            continue
        if not _resolve(wall).exists():
            findings.append(Finding(f"C3 MISSING WALL  {item_id}: wall '{wall}' does not exist on disk"))
            continue
        stats["walled"] += 1

        # C6 — positive control. Runs FIRST: a wall with no proven green state cannot be trusted to
        # mean anything by C5, so its polarity verdict is worthless until this passes.
        if run_controls:
            res = spend(wall, selftest=True)
            if res is None:
                findings.append(Finding(
                    f"C11 UNVERIFIED   {item_id}: total wall budget exhausted before '{wall}' could run its "
                    "positive control — polarity UNKNOWN. Never reported as a pass."))
                continue
            code, note = res
            if code != 0:
                stats["uncontrolled"] += 1
                findings.append(Finding(
                    f"C6 NO CONTROL    {item_id}: wall '{wall}' does not pass its own --selftest (exit {code}). "
                    f"Without a positive control a do-nothing `exit(1)` is indistinguishable from real "
                    f"enforcement. {note}"))

        if not run_polarity:
            continue
        res = spend(wall)
        if res is None:
            findings.append(Finding(
                f"C11 UNVERIFIED   {item_id}: total wall budget exhausted before '{wall}' could run — "
                "polarity UNKNOWN. Never reported as a pass."))
            continue
        code, note = res
        if status in RED_STATUSES and code == 0:
            stats["vacuous"] += 1
            findings.append(Finding(
                f"C5 VACUOUS WALL  {item_id} [{status}]: wall '{wall}' PASSES while the item is unfinished. "
                f"It does not detect the gap it claims to guard. {note}"))
        elif status in GREEN_STATUSES and code != 0:
            stats["false_green"] += 1
            findings.append(Finding(
                f"C5 FALSE STATUS  {item_id} [{status}]: wall '{wall}' FAILS (exit {code}) but the item claims done. {note}"))
        elif status in GREEN_STATUSES and code == 0:
            stats["green"] += 1

    # C7 — fence exclusivity, mechanical (review finding #4: `claim` does not check this)
    if fences:
        by_phase: dict[str, dict[str, list[str]]] = {}
        inflight: dict[str, list[str]] = {}
        for iid, (phase, status) in items.items():
            for f in fences.get(iid, []):
                by_phase.setdefault(phase, {}).setdefault(f, []).append(iid)
                if status == "in_flight":
                    inflight.setdefault(f, []).append(iid)
        for phase, owners in sorted(by_phase.items()):
            for f, ids in sorted(owners.items()):
                if len(ids) > 1:
                    findings.append(Finding(
                        f"C7 FENCE CLASH   {phase}: '{f}' owned by {', '.join(sorted(ids))} in the same phase — "
                        "derived fences overlap; narrow via edit-fence at claim time or serialize"))
        for f, ids in sorted(inflight.items()):
            if len(ids) > 1:
                findings.append(Finding(
                    f"C10 LIVE CLASH  '{f}' is fenced by {len(ids)} IN_FLIGHT items ({', '.join(sorted(ids))}) — "
                    "two claimed agents are writing one file RIGHT NOW"))

    # C8 — every law names an enforcer  |  C9 — that enforcer must RUN and the law must HOLD
    if laws is not None:
        stats["laws"] = len(laws)
        ran: set[str] = set()
        for law in laws:
            tag = str(law.get("tag", "?"))
            wall = str(law.get("wall", "")).strip()
            if not wall:
                stats["unlawed"] += 1
                findings.append(Finding(
                    f"C8 UNLAWED       {tag}: standing law with no mechanical enforcer — "
                    "prose-only lawmaking (#924). The fix is building the checker."))
                continue
            if not _resolve(wall).exists():
                findings.append(Finding(f"C8 MISSING       {tag}: named enforcer '{wall}' is not on disk"))
                continue
            stats["lawed"] += 1
            # Several laws are enforced by checks INSIDE this gate (C5/C6/C7). Running this gate
            # from within itself would recurse, so those rows are satisfied by existence alone.
            if pathlib.Path(wall).name == pathlib.Path(__file__).name or not run_polarity or wall in ran:
                continue
            ran.add(wall)
            # A law enforcer's polarity is the INVERSE of an item wall's: it must PASS today
            # (the law is currently honored). Red means the law was BROKEN.
            lres = spend(wall)
            if lres is None:
                findings.append(Finding(
                    f"C11 UNVERIFIED   {tag}: budget exhausted before law enforcer '{wall}' could run"))
                continue
            code, note = lres
            if code != 0:
                stats["law_violations"] += 1
                findings.append(Finding(
                    f"C9 LAW VIOLATED  {tag}: enforcer '{wall}' FAILS — the law is being broken right now. {note}"))
            if run_controls:
                sres = spend(wall, selftest=True)
                if sres is None:
                    findings.append(Finding(f"C11 UNVERIFIED   {tag}: budget exhausted before its control could run"))
                    continue
                sc, snote = sres
                if sc != 0:
                    stats["uncontrolled"] += 1
                    findings.append(Finding(
                        f"C6 NO CONTROL    {tag}: law enforcer '{wall}' does not pass its own --selftest "
                        f"(exit {sc}). {snote}"))

    return findings, stats


def report(findings: list[Finding], stats: dict, *, strict: bool) -> int:
    blocking = [f for f in findings if SEVERITY.get(_code(f), BLOCKING) == BLOCKING]
    advisory = [f for f in findings if SEVERITY.get(_code(f), BLOCKING) == ADVISORY]

    if blocking:
        print("  ── BLOCKING ──")
        for f in blocking:
            print(f"  {f}")
    if advisory:
        print(f"  ── ADVISORY ({len(advisory)}) ── the standing worklist")
        for f in advisory[:6]:
            print(f"  {f}")
        if len(advisory) > 6:
            print(f"  … and {len(advisory) - 6} more")
    print()
    print(f"  items {stats['total']} | walled {stats['walled']} | UNWALLED {stats['unwalled']} | "
          f"earned-green {stats['green']} | vacuous {stats['vacuous']} | false-green {stats['false_green']} | "
          f"uncontrolled {stats['uncontrolled']}")
    print(f"  laws {stats['laws']} | enforced {stats['lawed']} | UNLAWED {stats['unlawed']} | violated {stats['law_violations']}")

    if blocking:
        print(f"\nCAMPAIGN WALL GATE: BLOCKED ({len(blocking)} blocking, {len(advisory)} advisory)")
        return 1
    if strict and advisory:
        print(f"\nCAMPAIGN WALL GATE: RED --strict ({len(advisory)} advisory)")
        print("  Red is the honest work inventory, not a failure.")
        return 1
    print(f"\nCAMPAIGN WALL GATE: PASS (0 blocking, {len(advisory)} advisory outstanding)")
    return 0


# ── selftest ─────────────────────────────────────────────────────────────────

_REAL = "import sys\nif '--selftest' in sys.argv: sys.exit(0)\nsys.exit({code})\n"
_NOCTL = "import sys\nsys.exit(1)\n"   # the do-nothing wall the review caught


def selftest() -> int:
    fails: list[str] = []

    def expect(name, got, want):
        codes = {_code(f) for f in got}
        if want is None:
            if codes:
                fails.append(f"{name}: expected clean, got {sorted(codes)}")
        elif want not in codes:
            fails.append(f"{name}: expected {want}, got {sorted(codes) or 'clean'}")

    with tempfile.TemporaryDirectory() as td:
        t = pathlib.Path(td)
        real_red = t / "red.py"; real_red.write_text(_REAL.format(code=1))
        real_grn = t / "grn.py"; real_grn.write_text(_REAL.format(code=0))
        noctl = t / "noctl.py"; noctl.write_text(_NOCTL)

        def I(s):
            return {"NF-01": ("W1", s)}

        expect("C1-unregistered", audit(I("todo"), [], run_controls=False)[0], "C1")
        expect("C1-ambiguous", audit(I("todo"), [{"id": "NF-01", "wall": ""}] * 2, run_controls=False)[0], "C1")
        expect("C2-orphan", audit({}, [{"id": "ZZ-99", "wall": ""}], run_controls=False)[0], "C2")
        expect("C3-missing", audit(I("todo"), [{"id": "NF-01", "wall": "walls/__nope__.py"}], run_controls=False)[0], "C3")
        expect("C4-unwalled", audit(I("todo"), [{"id": "NF-01", "wall": ""}], run_controls=False)[0], "C4")
        expect("C5-vacuous", audit(I("todo"), [{"id": "NF-01", "wall": str(real_grn)}], run_controls=False)[0], "C5")
        expect("C5-false-green", audit(I("verified"), [{"id": "NF-01", "wall": str(real_red)}], run_controls=False)[0], "C5")

        # C6 — THE review finding: a do-nothing wall has honest polarity but no positive control
        f6, _ = audit(I("todo"), [{"id": "NF-01", "wall": str(noctl)}])
        expect("C6-no-control", f6, "C6")
        if "C5" in {_code(x) for x in f6}:
            fails.append("C6-no-control: should NOT also raise C5 (its polarity is honest — that is the trap)")

        expect("C7-phase-clash", audit({"A-01": ("W1", "todo"), "A-02": ("W1", "todo")},
                                       [{"id": "A-01", "wall": ""}, {"id": "A-02", "wall": ""}],
                                       fences={"A-01": ["x.kt"], "A-02": ["x.kt"]}, run_controls=False)[0], "C7")
        cross = [x for x in audit({"A-01": ("W1", "todo"), "A-02": ("W2", "todo")},
                                  [{"id": "A-01", "wall": ""}, {"id": "A-02", "wall": ""}],
                                  fences={"A-01": ["x.kt"], "A-02": ["x.kt"]}, run_controls=False)[0]
                 if _code(x) == "C7"]
        if cross:
            fails.append("C7-cross-phase: same file in DIFFERENT phases must not clash")

        live = audit({"A-01": ("W1", "in_flight"), "A-02": ("W1", "in_flight")},
                     [{"id": "A-01", "wall": ""}, {"id": "A-02", "wall": ""}],
                     fences={"A-01": ["x.kt"], "A-02": ["x.kt"]}, run_controls=False)[0]
        if "C10" not in {_code(x) for x in live}:
            fails.append("C10-live-clash: two in_flight items sharing a file must raise C10")
        if SEVERITY.get("C10") != BLOCKING or SEVERITY.get("C7") != ADVISORY:
            fails.append("severity: C10 must BLOCK and C7 must be ADVISORY")
        expect("C8-unlawed", audit(I("todo"), [{"id": "NF-01", "wall": str(real_red)}],
                                   laws=[{"tag": "L1", "wall": ""}], run_controls=False)[0], "C8")
        # C9 — a law enforcer's polarity is INVERSE: a FAILING enforcer means the law is broken now
        expect("C9-law-violated", audit(I("todo"), [{"id": "NF-01", "wall": str(real_red)}],
                                        laws=[{"tag": "L1", "wall": str(real_red)}], run_controls=False)[0], "C9")
        law_ok = [x for x in audit(I("todo"), [{"id": "NF-01", "wall": str(real_red)}],
                                   laws=[{"tag": "L1", "wall": str(real_grn)}])[0] if _code(x) in ("C8", "C9")]
        if law_ok:
            fails.append(f"law-honored: a PASSING enforcer must be clean, got {law_ok}")
        starved = audit(I("todo"), [{"id": "NF-01", "wall": str(real_red)}], budget_s=0.0)[0]
        if "C11" not in {_code(x) for x in starved}:
            fails.append("C11-budget: an exhausted budget must raise C11, never silently pass")
        if SEVERITY.get("C11") != BLOCKING:
            fails.append("C11 must BLOCK — an unverified wall is not a passing wall")
        expect("green-honest-wall", audit(I("todo"), [{"id": "NF-01", "wall": str(real_red)}])[0], None)
        expect("green-earned", audit(I("verified"), [{"id": "NF-01", "wall": str(real_grn)}])[0], None)

    for c in ("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10", "C11"):
        if c not in SEVERITY:
            fails.append(f"severity map missing {c}")

    if fails:
        print("SELFTEST FAIL:")
        for x in fails:
            print("  " + x)
        return 1
    print("SELFTEST OK — C1-C11 red cases fire; C6 catches the do-nothing wall WITHOUT a false C5; "
          "cross-phase fence reuse stays clean; both correct-polarity cases pass")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=(__doc__ or "campaign wall gate").splitlines()[0])
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--strict", action="store_true", help="advisory findings also fail (the end state)")
    ap.add_argument("--no-run", action="store_true", help="census only; exits 2, never 0")
    args = ap.parse_args()
    if args.selftest:
        return selftest()

    print("── campaign wall gate — proxy-hardening ──")
    items = ledger_items()
    findings, stats = audit(
        items, rows_of(REGISTRY, "item"),
        fences=item_fences(), laws=rows_of(LAW_REGISTRY, "law"),
        run_polarity=not args.no_run, run_controls=not args.no_run,
    )
    rc = report(findings, stats, strict=args.strict)
    if args.no_run:
        # Review finding #9: census mode must never be mistakable for a passing polarity run.
        print("  (--no-run: C5/C6 SKIPPED — census only. Exit 2 by construction.)")
        return 2
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
