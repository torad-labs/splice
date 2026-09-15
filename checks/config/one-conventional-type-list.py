#!/usr/bin/env python3
"""V4-30 — the conventional-type list lives in checks/pr-title.sh, once.

THE CLASS. A second copy of the conventional-commit type vocabulary drifts.
This repo shipped .github/workflows/pr-title.yml allowing two types the org
gate rejects. The workflow is deleted; the org gate is the authority;
checks/pr-title.sh mirrors it. Restating the list in prose is how a stale
copy outlives that deletion and how a contributor titles the 0.4.0 PR with
a type that cannot merge.

SCOPE. Every text file in the tree except checks/pr-title.sh, which is the
single allowed copy. Docs, templates, agents files, checkers, tests: if it
is in the tree and is text, it is in scope.

DENOMINATOR. Files are enumerated by git ls-files from the check root
(tracked content plus the index, so a staged new file is in). The file
list is not an allowlist this file types and is not the working-tree
walk: untracked scratch, editor backups, and gitignored sidecars cannot
red the gate. A second copy has to be tracked to reach main. When there
is no git repo the checker falls back to a tree walk so hermetic
selftests still run. The type vocabulary itself is parsed from the
TYPES assignment in checks/pr-title.sh; this checker does not restate it.

DISPOSITION. A file either contains a run of three or more conventional
types in sequence, in which case it FAILs by name with the remedy, or it
does not. Absence is not a disposition. There is no exemption table; a
second copy that happens to be correct is still the divergence mechanism.

NOT CAUGHT, and why.

  Two-type phrases (feat vs fix). The class is a restated vocabulary, not
  a comparison. What would catch a two-type copy that later grows a third
  type: this wall, on the third type.

  Types that are not in the org list (release, codex, harden, verify) on
  their own. Those are not conventional types under the parsed TYPES
  assignment. A list of them becomes this wall's class the moment three
  org types sit in the same run. What would catch a docs line that names
  only the two rejected types: a wall on those two strings as a pair.

  git log history. Commit subjects are not a restated list. What would
  catch inferring the convention from history: checks/pr-title.sh itself,
  which already warns not to.

  Campaign ledgers under dev/campaigns. They quote the defect being
  fixed. They are not a title vocabulary a contributor reads. What would
  catch a CONTRIBUTING copy: this wall, on that file.

  Recorded captures under dev/research. Mutating a capture to please a
  docs wall would falsify evidence. What would catch a guide that embeds
  the same list: this wall, on that guide.
"""
from __future__ import annotations

import pathlib
import re
import subprocess
import sys

SOURCE = "checks/pr-title.sh"
TYPES_LINE = re.compile(r"^TYPES='([^']+)'", re.MULTILINE)
SKIP_DIRS = {
    ".git",
    "node_modules",
    "build",
    ".gradle",
    "dist",
    "out",
    "__pycache__",
    ".venv",
}
SKIP_PREFIXES = (
    "dev/campaigns/",
    "dev/research/",
)
SKIP_SUFFIXES = {
    ".png",
    ".jpg",
    ".jpeg",
    ".gif",
    ".webp",
    ".ico",
    ".jar",
    ".class",
    ".so",
    ".dylib",
    ".zip",
    ".gz",
    ".pdf",
    ".woff",
    ".woff2",
    ".ttf",
    ".eot",
    ".wasm",
}


def parse_types(root: pathlib.Path) -> list[str]:
    path = root / SOURCE
    if not path.is_file():
        raise SystemExit(f"{SOURCE} missing — refusing to pass vacuously")
    match = TYPES_LINE.search(path.read_text(encoding="utf-8"))
    if match is None or not match.group(1):
        raise SystemExit(f"{SOURCE} has no TYPES assignment — refusing to pass vacuously")
    types = [part for part in match.group(1).split("|") if part]
    if len(types) < 3:
        raise SystemExit(f"{SOURCE} TYPES has fewer than 3 entries — refusing to pass vacuously")
    return types


def run_pattern(types: list[str]) -> re.Pattern[str]:
    alt = "|".join(re.escape(t) for t in types)
    return re.compile(
        rf"(?<![A-Za-z])(?:{alt})(?:[\s·,|/]+(?:{alt})){{2,}}(?![A-Za-z])",
        re.MULTILINE,
    )


def git_listed(root: pathlib.Path) -> list[str] | None:
    try:
        out = subprocess.check_output(
            ["git", "-C", str(root), "ls-files", "-z"],
            stderr=subprocess.DEVNULL,
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    names = [name for name in out.decode("utf-8", errors="replace").split("\0") if name]
    return names or None


def accept(relative: str, path: pathlib.Path) -> bool:
    if not path.is_file():
        return False
    if any(part in SKIP_DIRS for part in path.parts):
        return False
    if path.suffix.lower() in SKIP_SUFFIXES:
        return False
    if any(relative.startswith(prefix) for prefix in SKIP_PREFIXES):
        return False
    return True


def iter_text_files(root: pathlib.Path) -> list[pathlib.Path]:
    listed = git_listed(root)
    if listed is None:
        files: list[pathlib.Path] = []
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            relative = path.relative_to(root).as_posix()
            if accept(relative, path):
                files.append(path)
        return files
    files = []
    for relative in listed:
        path = root / relative
        if accept(relative, path):
            files.append(path)
    return files


def rel(root: pathlib.Path, path: pathlib.Path) -> str:
    return path.relative_to(root).as_posix()


def hits_in(text: str, pattern: re.Pattern[str]) -> list[int]:
    # Strip backticks so a middot-separated fenced list is one run.
    stripped = text.replace("`", " ")
    return [m.start() for m in pattern.finditer(stripped)]


def check(root: pathlib.Path) -> int:
    types = parse_types(root)
    pattern = run_pattern(types)
    source = (root / SOURCE).resolve()
    scanned = 0
    failures: list[str] = []
    for path in iter_text_files(root):
        if path.resolve() == source:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        scanned += 1
        if hits_in(text, pattern):
            name = rel(root, path)
            failures.append(
                f"{name} restates 3+ conventional types; the list lives once in {SOURCE}. "
                f"Remedy: delete the copy and point readers at bash {SOURCE} "
                f'"feat(scope): subject"'
            )
    if scanned == 0:
        print("one-conventional-type-list: scanned 0 files — refusing to pass vacuously", file=sys.stderr)
        return 1
    if failures:
        print("ONE CONVENTIONAL TYPE LIST RED — a second copy is the divergence mechanism:")
        for failure in failures:
            print("  " + failure)
        return 1
    print(
        f"ONE CONVENTIONAL TYPE LIST GREEN: {scanned} files, no restated vocabulary; "
        f"the list lives once in {SOURCE}"
    )
    return 0


def main() -> int:
    args = sys.argv[1:]
    if len(args) != 2 or args[0] != "check":
        print("usage: one-conventional-type-list.py check <root>", file=sys.stderr)
        return 2
    return check(pathlib.Path(args[1]).resolve())


if __name__ == "__main__":
    sys.exit(main())
