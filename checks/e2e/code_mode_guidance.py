#!/usr/bin/env python3
"""Frozen synthetic repository and evidence oracles for the prompt-only A/B."""
from __future__ import annotations

import argparse
import ast
from collections import Counter
import json
from pathlib import Path
import re
import shlex
import unittest


SYSTEM = """Investigate the supplied synthetic repository using tools to establish the requested facts.
The tool implementations are read-only fixtures. Do not edit files or invent evidence.
Return only the requested JSON object, without markdown fences. Stop once sufficient evidence exists."""

GUIDANCE_PATH = (Path(__file__).resolve().parents[2] / "gateway/provider-codex/src/main/resources"
                 / "splice/provider/codex/code-mode-orchestration.txt")
GUIDANCE = GUIDANCE_PATH.read_text(encoding="utf-8").rstrip()


def _tool(name, description, properties):
    return {"name": name, "description": description, "input_schema": {
        "type": "object", "properties": properties, "required": list(properties),
        "additionalProperties": False,
    }}


TOOLS = [
    _tool("Read", "Read one complete synthetic fixture file by exact relative path.", {
        "file_path": {"type": "string"},
    }),
    _tool("Grep", "Literal text search. path is a fixture path/prefix, or '.' for all files. Returns path:line:text.", {
        "pattern": {"type": "string"}, "path": {"type": "string"},
    }),
    _tool("LSP", "Inspect Python definitions or call references across the fixture workspace. "
          "findReferences returns caller function names and source locations, excluding comments/strings. "
          "file_path is the defining Python file; symbol is empty for documentSymbol.", {
        "operation": {"type": "string", "enum": ["documentSymbol", "findReferences"]},
        "file_path": {"type": "string"}, "symbol": {"type": "string"},
    }),
    _tool("Bash", "Read-only simulated fixture commands only: find . -type f; cat PATH; grep -n LITERAL PATH. "
          "No command execution, pipes, or multiple commands; use the documented forms exactly.", {
        "command": {"type": "string"},
    }),
    _tool("mcp__calendar__list", "Unrelated synthetic calendar integration.", {}),
    _tool("mcp__deploy__status", "Unrelated synthetic deployment integration.", {}),
    _tool("mcp__tickets__search", "Unrelated synthetic ticket integration.", {}),
]

FILES = {
    "config/settings.toml": '[service]\ntimeout_seconds = 15\nretries = 2\n',
    "config/worker.toml": '[worker]\nconcurrency = 4\n',
    "config/cache.toml": '[cache]\nttl_seconds = 60\nenabled = false\n',
    "app/config.py": 'def load_timeout(config):\n    return config["service"]["timeout_seconds"]\n',
    "app/limits.py": '''def build_client(timeout_seconds):
    return {"timeout": timeout_seconds}


def normalize_timeout(seconds):
    return min(max(seconds, 0), 60)


# The text build_client in this comment is not a caller.
DISPLAY_NAME = "build_client"
''',
    "app/service.py": '''from app.config import load_timeout
from app.limits import build_client


def start(config):
    return build_client(load_timeout(config))
''',
    "app/worker.py": '''from app import limits


def worker_client():
    return limits.build_client(30)
''',
    "docs/notes.txt": "The words build_client() appear here, but this is documentation only.\n",
    "spec/timeouts.md": "# Request timeouts\n\nA request timeout of zero is invalid; accept integers 1 through 60 seconds.\n",
    "tests/test_limits.py": '''from app.limits import normalize_timeout


def test_zero_is_currently_accepted():
    assert normalize_timeout(0) == 0
''',
}

CASES = (
    ("configuration-audit", "Audit config/settings.toml, config/worker.toml, and config/cache.toml. "
     "Report service timeout_seconds, worker concurrency, and cache ttl_seconds. "
     'Return JSON with numeric fields "timeout_seconds", "worker_concurrency", "cache_ttl_seconds", '
     'and "citations": an array of source references in path:line format.'),
    ("function-callers", "Find every actual Python caller of build_client, defined in app/limits.py. "
     "Exclude definitions, imports, comments, and string decoys. "
     'Return JSON with "callers": an array of "path:function" strings, '
     'and "citations": an array of call-site references in path:line format.'),
    ("config-consumer-trace", "Trace the configured service timeout from config/settings.toml through "
     "app/config.py to app/service.py and identify its sink function. "
     'Return JSON with "setting" (section.key), "reader" (path:function), '
     '"consumer" (path:function), "sink" (dotted module.function), '
     'and "citations": an array of source references in path:line format.'),
    ("timeout-boundary-bug", "Check normalize_timeout in app/limits.py against spec/timeouts.md and "
     "tests/test_limits.py for input zero. Do not edit anything. "
     'Return JSON with numeric "actual_zero", "allowed_min", "allowed_max", boolean "violates_spec", '
     'and "citations": an array of source references in path:line format.'),
)

# These expectations are evaluator-only; none are interpolated into model instructions.
CONTRACTS = {
    "configuration-audit": {"timeout_seconds": 15, "worker_concurrency": 4, "cache_ttl_seconds": 60,
                            "citations": ["config/settings.toml:2", "config/worker.toml:2", "config/cache.toml:2"]},
    "function-callers": {"callers": ["app/service.py:start", "app/worker.py:worker_client"],
                         "citations": ["app/service.py:6", "app/worker.py:5"]},
    "config-consumer-trace": {"setting": "service.timeout_seconds", "reader": "app/config.py:load_timeout",
                              "consumer": "app/service.py:start", "sink": "app.limits.build_client",
                              "citations": ["config/settings.toml:2", "app/config.py:2", "app/service.py:6"]},
    "timeout-boundary-bug": {"actual_zero": 0, "allowed_min": 1, "allowed_max": 60, "violates_spec": True,
                             "citations": ["app/limits.py:6", "spec/timeouts.md:3", "tests/test_limits.py:5"]},
}

REQUIRED_LINES = {
    "configuration-audit": {("config/settings.toml", 2), ("config/worker.toml", 2), ("config/cache.toml", 2)},
    "function-callers": {("app/service.py", 5), ("app/service.py", 6), ("app/worker.py", 4), ("app/worker.py", 5)},
    "config-consumer-trace": {("config/settings.toml", 2), ("app/config.py", 1), ("app/config.py", 2),
                              ("app/service.py", 5), ("app/service.py", 6)},
    "timeout-boundary-bug": {("app/limits.py", 6), ("spec/timeouts.md", 3), ("tests/test_limits.py", 5)},
}


class Workspace:
    """All tools derive results from FILES; no shell, disk, network, exec, or eval."""

    def __init__(self):
        self.files = dict(FILES)
        self.calls = []
        self.repeated_calls = 0
        self._seen_calls = set()
        self._lines = set()
        self._facts = set()
        self._no_new_evidence = 0
        self._quality = {}
        self._trace = []

    def execute(self, tool):
        name, args = tool["name"], tool["input"]
        self.calls.append(name)
        signature = json.dumps([name, args], sort_keys=True)
        self.repeated_calls += signature in self._seen_calls
        self._seen_calls.add(signature)
        self._trace.append({"name": name, "input": args})
        before = (len(self._lines), len(self._facts))
        handlers = {"Read": self._Read, "Grep": self._Grep, "LSP": self._LSP, "Bash": self._Bash}
        if name not in handlers:
            raise ValueError("unknown or unavailable synthetic tool")
        result = handlers[name](args)
        self._no_new_evidence += before == (len(self._lines), len(self._facts))
        return result

    def _Read(self, args):
        self._exact(args, {"file_path": str})
        path = args["file_path"]
        if path not in self.files:
            raise ValueError("unknown fixture path")
        self._lines.update((path, n) for n in range(1, len(self.files[path].splitlines()) + 1))
        return self.files[path]

    def _Grep(self, args):
        self._exact(args, {"pattern": str, "path": str})
        pattern, prefix = args["pattern"], args["path"]
        paths = [p for p in self.files if prefix == "." or p == prefix or p.startswith(prefix.rstrip("/") + "/")]
        if not pattern or not paths:
            raise ValueError("invalid literal search")
        rows = []
        for path in paths:
            for number, line in enumerate(self.files[path].splitlines(), 1):
                if pattern in line:
                    rows.append(f"{path}:{number}:{line}")
                    self._lines.add((path, number))
        return "\n".join(rows)

    def _LSP(self, args):
        self._exact(args, {"operation": str, "file_path": str, "symbol": str})
        operation, path, symbol = args["operation"], args["file_path"], args["symbol"]
        if path not in self.files or not path.endswith(".py"):
            raise ValueError("LSP requires a Python fixture")
        tree = ast.parse(self.files[path])
        if operation == "documentSymbol" and not symbol:
            rows = [{"name": node.name, "line": node.lineno} for node in tree.body
                    if isinstance(node, (ast.FunctionDef, ast.ClassDef))]
            self._lines.update((path, row["line"]) for row in rows)
        elif operation == "findReferences" and symbol:
            if not any(isinstance(n, ast.FunctionDef) and n.name == symbol for n in tree.body):
                raise ValueError("symbol is not defined in the given fixture")
            rows = []
            for candidate, source in self.files.items():
                if not candidate.endswith(".py"):
                    continue
                for enclosing in ast.parse(source).body:
                    if not isinstance(enclosing, ast.FunctionDef):
                        continue
                    for node in ast.walk(enclosing):
                        if not isinstance(node, ast.Call):
                            continue
                        func = node.func
                        if ((isinstance(func, ast.Name) and func.id == symbol) or
                                (isinstance(func, ast.Attribute) and func.attr == symbol)):
                            rows.append({"path": candidate, "line": node.lineno, "caller": enclosing.name,
                                         "caller_line": enclosing.lineno})
                            self._lines.update(((candidate, node.lineno), (candidate, enclosing.lineno)))
        else:
            raise ValueError("invalid LSP operation")
        self._facts.add((operation, path, symbol))
        return json.dumps(rows)

    def _Bash(self, args):
        self._exact(args, {"command": str})
        words = shlex.split(args["command"])
        if words == ["find", ".", "-type", "f"]:
            self._facts.update(("path", path) for path in self.files)
            return "\n".join(self.files)
        if len(words) == 2 and words[0] == "cat":
            return self._Read({"file_path": words[1]})
        if len(words) == 4 and words[:2] == ["grep", "-n"] and words[3] in self.files:
            return self._Grep({"pattern": words[2], "path": words[3]})
        raise ValueError("command is outside the read-only fixture allowlist")

    @staticmethod
    def _exact(args, expected):
        if not isinstance(args, dict) or set(args) != set(expected) or any(
                type(args[key]) is not kind for key, kind in expected.items()):
            raise ValueError("invalid tool input")

    def correct(self, case, text):
        expected = CONTRACTS[case]
        try:
            answer = json.loads(text)
        except (TypeError, ValueError):
            answer = None
        obj = answer if isinstance(answer, dict) else {}
        self._quality = {"json_shape": isinstance(answer, dict) and set(obj) == set(expected)}
        for key, value in expected.items():
            if key == "citations":
                continue
            actual = obj.get(key)
            self._quality[key] = (sorted(actual) == sorted(value) if isinstance(value, list)
                                  and isinstance(actual, list) and all(isinstance(v, str) for v in actual)
                                  else actual == value and type(actual) is type(value))
        references = obj.get("citations", [])
        cited = set()
        valid = isinstance(references, list) and bool(references)
        if isinstance(references, list):
            for ref in references:
                match = re.fullmatch(r"(.+):(\d+)(?:-(\d+))?", ref) if isinstance(ref, str) else None
                if not match:
                    valid = False
                    continue
                path, first, last = match.groups()
                numbers = range(int(first), int(last or first) + 1)
                lines = {(path, n) for n in numbers}
                valid = valid and bool(lines) and lines.issubset(self._lines)
                cited.update(lines)
        required = REQUIRED_LINES[case]
        self._quality["valid_citations"] = valid
        self._quality["required_sources_cited"] = {p for p, _ in required}.issubset({p for p, _ in cited})
        self._quality["source_evidence"] = required.issubset(self._lines)
        return all(self._quality.values())

    def metrics(self):
        return {"tool_counts": dict(Counter(self.calls)), "exact_duplicate_calls": self.repeated_calls,
                "no_new_evidence_calls": self._no_new_evidence,
                "quality_checks": {"passed": sum(self._quality.values()), "total": len(self._quality),
                                   "checks": dict(self._quality)},
                "trace": list(self._trace)}


class GuidanceTests(unittest.TestCase):
    def test_prompts_do_not_supply_the_answers(self):
        prompts = dict(CASES)
        self.assertNotIn('"timeout_seconds":15', prompts["configuration-audit"])
        self.assertNotIn('"app/service.py:start"', prompts["function-callers"])
        self.assertNotIn('"consumer":"app/service.py:start"', prompts["config-consumer-trace"])
        self.assertNotIn('"bug":"normalize_timeout accepts 0"', prompts["timeout-boundary-bug"])
        self.assertIn("<code_mode_orchestration>", GUIDANCE)
        self.assertIn("tools.call('Read', args)", GUIDANCE)
        self.assertNotIn("Promise.all", SYSTEM)

    def test_source_name_alone_is_not_fact_evidence(self):
        w = Workspace()
        for path in ("config/settings.toml", "config/worker.toml", "config/cache.toml"):
            w.execute({"name": "Grep", "input": {"pattern": "[", "path": path}})
        self.assertFalse(w.correct("configuration-audit", json.dumps(CONTRACTS["configuration-audit"])))

    def test_all_oracles_accept_read_or_shell_evidence(self):
        for case in CONTRACTS:
            for name in ("Read", "Bash"):
                w = Workspace()
                for path in {p for p, _ in REQUIRED_LINES[case]}:
                    args = {"file_path": path} if name == "Read" else {"command": "cat " + path}
                    w.execute({"name": name, "input": args})
                answer = dict(CONTRACTS[case])
                answer["citations"] = list(reversed(answer["citations"]))
                self.assertTrue(w.correct(case, json.dumps(answer)), (case, w.metrics()))

    def test_lsp_derives_callers_excluding_decoys(self):
        w = Workspace()
        refs = json.loads(w.execute({"name": "LSP", "input": {
            "operation": "findReferences", "file_path": "app/limits.py", "symbol": "build_client"}}))
        self.assertEqual({"start", "worker_client"}, {r["caller"] for r in refs})
        self.assertEqual(2, len(refs))
        self.assertTrue(w.correct("function-callers", json.dumps(CONTRACTS["function-callers"])))

    def test_wrong_facts_and_unread_citations_fail(self):
        for case, contract in CONTRACTS.items():
            w = Workspace()
            self.assertFalse(w.correct(case, json.dumps(contract)))
            for path in FILES:
                w.execute({"name": "Read", "input": {"file_path": path}})
            self.assertTrue(w.correct(case, json.dumps(contract)))
            bad = dict(contract, citations=["not-a-file:1"])
            self.assertFalse(w.correct(case, json.dumps(bad)))
            bad = dict(contract)
            key = next(k for k in contract if k != "citations")
            bad[key] = None
            self.assertFalse(w.correct(case, json.dumps(bad)))

    def test_redundancy_tracks_new_lines_not_just_file_names(self):
        w = Workspace()
        w.execute({"name": "Grep", "input": {"pattern": "build_client", "path": "app/service.py"}})
        w.execute({"name": "Read", "input": {"file_path": "app/service.py"}})
        self.assertEqual(0, w.metrics()["no_new_evidence_calls"])
        w.execute({"name": "Bash", "input": {"command": "cat app/service.py"}})
        w.execute({"name": "Read", "input": {"file_path": "app/service.py"}})
        self.assertEqual(2, w.metrics()["no_new_evidence_calls"])
        self.assertEqual(1, w.repeated_calls)

    def test_invalid_operations_do_not_touch_the_host(self):
        for tool in ({"name": "Bash", "input": {"command": "cat /etc/passwd"}},
                     {"name": "Read", "input": {"file_path": "../anything"}},
                     {"name": "Bash", "input": {"command": "find . -type f; true"}},
                     {"name": "mcp__deploy__status", "input": {}}):
            with self.assertRaises(ValueError):
                Workspace().execute(tool)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        unittest.main(argv=[__file__])
    else:
        parser.error("choose --selftest")
