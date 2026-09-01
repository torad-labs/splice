#!/usr/bin/env python3
"""checks/config/ast-grep-rule-docs.py — the ONE definition of "an ast-grep rule document",
derived from a real YAML parse instead of a line grep.

DR-131/DR-132. Both rule walls used to answer "is this a rule file?" and "does this doc carry
severity: error?" with `grep -E '^[[:space:]]*id:'` and a matching count of `severity: error`
lines. rule-routing.sh:38 said so out loud — "the SAME test checks/config-guard.sh uses ... so the
two legs cannot disagree about what a rule is". They could not disagree with each OTHER. They
could, and did, disagree with ast-grep, and a hand-authored pair of lists agreeing with each other
is not a check against reality (the completeness law).

Four shapes were proven to load in ast-grep 0.45.0 and run NON-BLOCKING (`ast-grep scan` exits 0 on
a match; the same rule with a real top-level `severity: error` exits 1) while both walls said PASS:

  A  severity: error nested under `metadata:`      — counted by the grep, invisible to ast-grep
  B  severity: error inside a `note:` block scalar — 15 .rules files already use block scalars
  C  multi-doc: doc 1 carries a decoy in `note:`, doc 2 has no severity at all
  D  `{id: x, language: kotlin, rule: {pattern: p()}}` — flow style, zero line-leading `id:` keys,
     so BOTH walls skipped the file entirely: no severity check, and rule-routing.sh's forward
     direction went fail-OPEN on a dormant directory holding one

A real parser sees all four. Structure, not indentation, is the denominator.

Subcommands:
  severity <root>          every rule document under <root>/.rules carries a top-level
                           `severity: error`; exits 1 naming each document that does not
  count <dir> <maxdepth>   number of files under <dir> holding at least one ast-grep document
                           (a doc with a top-level `id`) — the parser-derived replacement for
                           rule-routing.sh's `rule_file_count`
"""
import pathlib
import sys

import yaml

RULE_SUFFIXES = (".yml", ".yaml")


def documents(path):
    """(index, mapping) for every YAML document in path. Raises on unparseable input —
    a rule file ast-grep cannot read is a hard failure, never a silent skip."""
    with open(path, encoding="utf-8") as fh:
        for i, doc in enumerate(yaml.safe_load_all(fh)):
            if isinstance(doc, dict):
                yield i, doc


def is_fixture(doc):
    """A rule-TEST fixture: cases without a matcher. Identified structurally, exactly as the
    shell wall did — `valid:`/`invalid:` present and no `rule:` — so the exemption still cannot
    be claimed by dropping a real rule into a directory named rule-tests."""
    return ("valid" in doc or "invalid" in doc) and "rule" not in doc


def is_ast_grep_doc(doc):
    """Carries a top-level id — a rule or a fixture. The parser-derived twin of `grep '^ *id:'`."""
    return "id" in doc


def rule_files(root, maxdepth):
    """Files under root (bounded by maxdepth, mirroring find -maxdepth) holding >=1 ast-grep doc."""
    root = pathlib.Path(root)
    found = []
    for path in sorted(root.rglob("*")):
        if path.suffix not in RULE_SUFFIXES or not path.is_file():
            continue
        if len(path.relative_to(root).parts) > maxdepth:
            continue
        try:
            if any(is_ast_grep_doc(doc) for _, doc in documents(path)):
                found.append(path)
        except (yaml.YAMLError, OSError):
            # Unparseable: count it. Fail-closed — an unreadable rule file is not "no rules here".
            found.append(path)
    return found


def check_severity(root):
    violations = []
    for path in sorted(pathlib.Path(root).joinpath(".rules").rglob("*")):
        if path.suffix not in RULE_SUFFIXES or not path.is_file():
            continue
        rel = path.relative_to(root)
        try:
            docs = list(documents(path))
        except (yaml.YAMLError, OSError) as exc:
            violations.append(f"{rel} is not parseable YAML ({type(exc).__name__}) — ast-grep cannot load it")
            continue
        for index, doc in docs:
            if not is_ast_grep_doc(doc) or is_fixture(doc):
                continue
            severity = doc.get("severity")
            # Wording is load-bearing: config-guard-selftest.sh pins these two phrases so a wall
            # that fails for the WRONG reason cannot be mistaken for one that works.
            if severity is None:
                violations.append(
                    f"{rel} document {index} (id: {doc.get('id')}) declares no top-level severity, "
                    "so ast-grep loads it and runs non-blocking (scan exits 0 on a match)"
                )
            elif severity != "error":
                violations.append(
                    f"{rel} document {index} (id: {doc.get('id')}) has a non-error severity "
                    f"({severity!r}) — every rule document must be 'severity: error'"
                )
    return violations


def main(argv):
    if len(argv) >= 3 and argv[1] == "severity":
        violations = check_severity(argv[2])
        for v in violations:
            print(f"  ✗ {v}")
        return 1 if violations else 0
    if len(argv) >= 4 and argv[1] == "count":
        print(len(rule_files(argv[2], int(argv[3]))))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
