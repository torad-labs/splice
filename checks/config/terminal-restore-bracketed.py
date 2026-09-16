#!/usr/bin/env python3
"""CW-9 — raw-mode entry is only legal inside TerminalMode.raw.

THE CLASS. A wizard that leaves the terminal raw with echo off is strictly
worse than no wizard, because the damage outlives the process. TerminalMode.raw
is the restoring bracket: capture, enter, try/finally restore, shutdown hook.
This wall closes the class so a widget cannot invoke stty on its own or enter
raw outside that bracket.

SCOPE. Kotlin sources of splice.app.cli.prompt under
gateway/app/src/main/kotlin/splice/app/cli/prompt. Tests are out of scope —
they inject SttyCommand and never talk to a real tty.

DENOMINATOR. Every .kt file in that directory on disk. Zero files, or a missing
package directory, is a FAILURE, not a pass. There is no allowlist and no
exemption table.

PARSE. Sources are tokenized (comments dropped, string literals kept as STRING
tokens, identifiers as IDENT). This is not a substring grep: stty in a comment
does not count; the command string "stty" does.

VIOLATIONS, failed BY NAME:
  1. STRING token stty in any file other than TerminalMode.kt
  2. STRING token -icanon (the raw-mode entry flags) outside the body of
     TerminalMode.raw — including in TerminalMode.kt itself if it is not
     inside fun raw
"""
from __future__ import annotations

import pathlib
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
PROMPT_REL = "gateway/app/src/main/kotlin/splice/app/cli/prompt"
STTY = "stty"
RAW_FLAG = "-icanon"
TERMINAL_MODE = "TerminalMode.kt"


def prompt_dir(root: pathlib.Path) -> pathlib.Path:
    return root / PROMPT_REL


def prompt_files(root: pathlib.Path) -> list[pathlib.Path] | None:
    directory = prompt_dir(root)
    if not directory.is_dir():
        return None
    return sorted(path for path in directory.glob("*.kt") if path.is_file())


def tokenize(source: str) -> list[tuple[str, str, int]]:
    """Return (kind, value, line) tokens. kind is STRING, IDENT, or LBRACE/RBRACE."""
    tokens: list[tuple[str, str, int]] = []
    i = 0
    n = len(source)
    line = 1
    while i < n:
        ch = source[i]
        if ch == "\n":
            line += 1
            i += 1
            continue
        if ch.isspace():
            i += 1
            continue
        if source.startswith("//", i):
            i = source.find("\n", i)
            if i < 0:
                break
            continue
        if source.startswith("/*", i):
            end = source.find("*/", i + 2)
            if end < 0:
                break
            line += source[i:end].count("\n")
            i = end + 2
            continue
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            if end < 0:
                tokens.append(("STRING", source[i + 3 :], line))
                break
            tokens.append(("STRING", source[i + 3 : end], line))
            line += source[i:end].count("\n")
            i = end + 3
            continue
        if ch == '"':
            j = i + 1
            bits: list[str] = []
            while j < n:
                cur = source[j]
                if cur == "\\":
                    j += 2
                    continue
                if cur == '"':
                    break
                bits.append(cur)
                j += 1
            tokens.append(("STRING", "".join(bits), line))
            line += source[i:j].count("\n")
            i = j + 1 if j < n else n
            continue
        if ch.isalpha() or ch == "_":
            j = i + 1
            while j < n and (source[j].isalnum() or source[j] == "_"):
                j += 1
            tokens.append(("IDENT", source[i:j], line))
            i = j
            continue
        if ch == "{":
            tokens.append(("LBRACE", "{", line))
            i += 1
            continue
        if ch == "}":
            tokens.append(("RBRACE", "}", line))
            i += 1
            continue
        if ch == "<":
            tokens.append(("LT", "<", line))
            i += 1
            continue
        if ch == ">":
            tokens.append(("GT", ">", line))
            i += 1
            continue
        i += 1
    return tokens


def raw_function_string_lines(tokens: list[tuple[str, str, int]]) -> set[int]:
    """Line numbers of STRING tokens that sit inside fun raw { ... }."""
    lines: set[int] = set()
    i = 0
    n = len(tokens)
    while i < n:
        if tokens[i][0] == "IDENT" and tokens[i][1] == "fun":
            j = i + 1
            if j < n and tokens[j][0] == "LT":
                depth = 0
                while j < n:
                    if tokens[j][0] == "LT":
                        depth += 1
                    elif tokens[j][0] == "GT":
                        depth -= 1
                        j += 1
                        if depth == 0:
                            break
                        continue
                    j += 1
            if j < n and tokens[j][0] == "IDENT" and tokens[j][1] == "raw":
                while j < n and tokens[j][0] != "LBRACE":
                    j += 1
                if j >= n:
                    break
                depth = 0
                k = j
                while k < n:
                    kind, _value, line = tokens[k]
                    if kind == "LBRACE":
                        depth += 1
                    elif kind == "RBRACE":
                        depth -= 1
                        if depth == 0:
                            break
                    elif kind == "STRING":
                        lines.add(line)
                    k += 1
                i = k + 1
                continue
        i += 1
    return lines


def check_file(path: pathlib.Path, source: str) -> list[str]:
    tokens = tokenize(source)
    raw_lines = raw_function_string_lines(tokens) if path.name == TERMINAL_MODE else set()
    problems: list[str] = []
    for kind, value, line in tokens:
        if kind != "STRING":
            continue
        if value == STTY and path.name != TERMINAL_MODE:
            problems.append(f"{path.name}:{line}: stty invocation outside {TERMINAL_MODE}")
        if value == RAW_FLAG and not (path.name == TERMINAL_MODE and line in raw_lines):
            problems.append(
                f"{path.name}:{line}: raw-mode entry (-icanon) is not inside TerminalMode.raw"
            )
    return problems


def check_tree(root: pathlib.Path) -> list[str]:
    files = prompt_files(root)
    if files is None:
        return [f"prompt package missing: {PROMPT_REL}"]
    if not files:
        return [f"scanned zero files under {PROMPT_REL}"]
    problems: list[str] = []
    for path in files:
        rel = str(path.relative_to(root)).replace("\\", "/") if path.is_relative_to(root) else path.name
        try:
            source = path.read_text(encoding="utf-8")
        except OSError as exc:
            problems.append(f"{rel}: unreadable ({exc})")
            continue
        for hit in check_file(path, source):
            problems.append(hit if hit.startswith(path.name) else f"{rel}: {hit}")
    return problems


COMPLIANT_TERMINAL = """
package splice.app.cli.prompt
internal class TerminalMode {
    fun <T> raw(block: () -> T): T {
        stty.run(listOf("stty", "-g"))
        stty.run(listOf("stty", "-icanon", "-echo", "min", "1", "time", "0"))
        return block()
    }
}
"""

VIOLATION = """
package splice.app.cli.prompt
internal class LooseStty {
    fun go() {
        ProcessBuilder(listOf("stty", "-icanon", "-echo")).start()
    }
}
"""


def write_prompt(root: pathlib.Path, name: str, source: str) -> pathlib.Path:
    directory = prompt_dir(root)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / name
    path.write_text(source, encoding="utf-8")
    return path


def selftest() -> int:
    failures: list[str] = []
    # TemporaryDirectory is the Python form of mktemp -d plus trap EXIT.
    with tempfile.TemporaryDirectory(prefix="cw9-restore-") as tmp:
        root = pathlib.Path(tmp)
        empty = check_tree(root)
        if not any("missing" in hit or "zero files" in hit for hit in empty):
            failures.append("empty tree must refuse to pass vacuously, got: " + repr(empty))
        write_prompt(root, TERMINAL_MODE, COMPLIANT_TERMINAL)
        bad = write_prompt(root, "LooseStty.kt", VIOLATION)
        red = check_tree(root)
        if not any("LooseStty.kt" in hit for hit in red):
            failures.append("synthetic unbracketed stty must be RED naming LooseStty.kt, got: " + repr(red))
        bad.unlink()
        green = check_tree(root)
        if green:
            failures.append("compliant TerminalMode-only tree must be GREEN, got: " + repr(green))
    if failures:
        print("terminal-restore-bracketed SELFTEST FAIL:")
        for failure in failures:
            print("  " + failure)
        return 1
    print(
        "terminal-restore-bracketed SELFTEST OK — empty tree is vacuous-fail; "
        "unbracketed stty is RED naming LooseStty.kt; TerminalMode.raw only is GREEN"
    )
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    root = ROOT
    for arg in sys.argv[1:]:
        if arg not in {"check", "--selftest"} and not arg.startswith("-"):
            root = pathlib.Path(arg)
            break
    if not root.exists():
        print("terminal-restore-bracketed: tree missing", file=sys.stderr)
        return 1
    root = root.resolve()
    problems = check_tree(root)
    if problems:
        print("terminal-restore-bracketed RED:")
        for problem in problems:
            print("  " + problem)
        return 1
    files = prompt_files(root) or []
    print(
        f"terminal-restore-bracketed GREEN: {len(files)} prompt sources, "
        "stty only in TerminalMode.kt, -icanon only inside fun raw"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
