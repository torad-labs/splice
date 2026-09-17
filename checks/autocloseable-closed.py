#!/usr/bin/env python3
"""V4-95 — every main-source class that declares itself AutoCloseable is CLOSED from a main source.

THE LAW. Implementing AutoCloseable is a promise that the type owns something the JVM will not
reclaim on its own: a process, a file lock, a channel, a thread. The promise is only kept if some
PRODUCTION path actually calls `close()`. A closeable whose only `close()` callers live in
`src/test` has the shape of a managed resource and the behaviour of a leak — and the test suite is
green, because the tests are precisely the code that closes it.

THE SCAR (ARCH-AUDIT 2026-09-17, audit D row 6). `JvmCodeModeRuntime` implements `CodeModeRuntime`,
which extends `AutoCloseable`, and its `close()` shuts down the worker pool: it destroys child JVMs
and releases their permits. It is constructed in production at
gateway/app/src/main/kotlin/splice/app/provider/CodexResponsesArm.kt:101 (`runtime =
JvmCodeModeRuntime()`), and nothing in any main source ever closes it. The only `.close()`/`.use {}`
callers are gateway/app/src/test/kotlin/CodeModeRuntimeTest.kt:238 and friends — which is why every
test that exercises worker reclamation passes while the daemon never reclaims anything.

THE DENOMINATOR, FROM THE SOURCE, AND TRANSITIVELY (§24). The types are not a list in this file.
Every `class`/`interface`/`object` declaration under gateway/*/src/main is parsed off disk with its
supertype list, and the closeable set is the TRANSITIVE closure of {AutoCloseable, Closeable} over
that graph. Transitivity is the whole point: `JvmCodeModeRuntime` does not say `AutoCloseable`
anywhere — it says `CodeModeRuntime`, which says `AutoCloseable` in a different module. A direct-
mention denominator would have reported the audit's own finding as absent, which is the tautology
this campaign keeps finding (a check whose denominator comes from the same list it checks cannot
fail for anything outside that list).

DISPOSITION. Every concrete closeable gets exactly one, and absence is not one:
  closed      — main-source evidence that it is closed (see EVIDENCE below);
  allowlisted — a DATED entry in ALLOWLIST with a written reason;
  RED         — reported BY NAME with its declaration site.
Interfaces are excluded by design: `CodeModeRuntime : AutoCloseable` declares the contract, it does
not own a resource. An interface that is never implemented is a different defect and not this one's.

EVIDENCE that a type is closed, in main sources only. Three forms, all of which the tree already
uses, so the compliant form needs no invention:
  1. construct-and-use   `WorkerSession(start).use { … }`      (CodeModeWorker.kt:62)
  2. a handle name       `val lock = DaemonLock(path)` … `lock.close()`   (Main.kt:77, :185)
                         `var channel: WorkerChannel? = null` … `channel?.close()`
                         (JvmCodeModeRuntime.kt:48, :73)
  3. a method reference  `Cancellables.runCatchingCleanup(lease::close)`
                         (OAuthAccountFiles.kt:71)
A "handle name" is any identifier this parser saw BOUND to the type — `val`/`var` with that
declared type, a constructor or function parameter of that type, or a `val x = Type(…)`
initialisation. Closing evidence is then `x.close()`, `x?.close()`, `x::close`, `x.use` or
`x?.use` anywhere in a main source.

NOT CAUGHT, and stated rather than discovered later:
  - Whether close is reached on every PATH. This proves a production caller exists, not that it
    runs in a finally. `use { }` is the form that also gives you that, which is why it is listed
    first.
  - A handle passed into a helper that closes it through its own parameter name. The helper's
    parameter IS a binding of that type, so the name is collected and the evidence found there —
    but only if the parameter is spelled with the concrete type. A helper taking the INTERFACE
    (`fun shut(r: CodeModeRuntime) = r.close()`) closes the implementation without this checker
    seeing it, which would report a false RED. No such helper exists today (measured: the six
    concrete closeables are closed by forms 1-3 or not at all); when one appears the honest fix is
    to teach this checker interface-typed handles, not to allowlist the finding.
  - Anonymous closeables (`return AutoCloseable { timer.cancel() }`, Spinner.kt:26). They have no
    declaration to enumerate and no name to track. Spinner closes its own through
    `pulses?.close()`.

SELFTEST. `--selftest` builds temp trees and proves BOTH directions: GREEN on a tree where each
evidence form appears, RED BY NAME on a synthetic closeable that nothing closes, RED on one closed
only from src/test (the scar, reconstructed), RED through a TWO-HOP interface chain (the property
that made the real finding visible), GREEN for a dated allowlist entry and RED for an undated or
reasonless one, and a refusal to pass vacuously when the parse yields no closeables at all.
"""
import pathlib
import re
import sys
import tempfile

MAIN_GLOB = "gateway/*/src/main/**/*.kt"
TEST_GLOBS = ("gateway/*/src/test/**/*.kt", "gateway/*/src/testFixtures/**/*.kt")

SEEDS = {"AutoCloseable", "Closeable", "java.lang.AutoCloseable", "java.io.Closeable"}

# Concrete closeables that may go unclosed in main. Format: name -> "YYYY-MM-DD: <reason>".
# EMPTY today, and that is a finding rather than an oversight: all six concrete closeables in the
# tree either carry main-source closing evidence or are the audit's RED. An undated or reasonless
# entry is itself a failure (see audit()), because an exemption with no written reason is an absence
# wearing a label.
ALLOWLIST: dict[str, str] = {}

DECL = re.compile(
    r"^[ \t]*(?:(?:public|internal|private|protected|abstract|open|final|sealed|data|value|inner|"
    r"fun|enum|annotation|expect|actual|companion)\s+)*(class|interface|object)\s+([A-Za-z_]\w*)",
    re.M,
)
# The independent second reading used as the parser-drift guard: a supertype list mentioning a seed.
DIRECT_MENTION = re.compile(r"[:,]\s*(?:java\.lang\.|java\.io\.)?(?:AutoCloseable|Closeable)\b")


def strip_comments(source: str) -> str:
    """Blank out // and /* */ comments, string literals and char literals, preserving offsets and
    newlines. Offsets matter: every declaration site is reported as a line number."""
    out = []
    i, n = 0, len(source)
    while i < n:
        two = source[i : i + 2]
        if two == "//":
            while i < n and source[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if two == "/*":
            while i < n and source[i : i + 2] != "*/":
                out.append("\n" if source[i] == "\n" else " ")
                i += 1
            out.append("  ")
            i += 2
            continue
        if source[i : i + 3] == '"""':
            out.append("   ")
            i += 3
            while i < n and source[i : i + 3] != '"""':
                out.append("\n" if source[i] == "\n" else " ")
                i += 1
            out.append("   ")
            i += 3
            continue
        if source[i] == '"':
            out.append(" ")
            i += 1
            while i < n and source[i] != '"':
                if source[i] == "\\":
                    out.append(" ")
                    i += 1
                    if i < n:
                        out.append(" ")
                        i += 1
                    continue
                out.append("\n" if source[i] == "\n" else " ")
                i += 1
            out.append(" ")
            i += 1
            continue
        out.append(source[i])
        i += 1
    return "".join(out)


def supertypes(tail: str) -> list[str]:
    """The supertype names of a declaration, given the text after its name. Walks to the ':' that
    opens the supertype list at nesting depth 0 — so a constructor parameter typed `Foo : Bar` (an
    impossibility) or a generic bound in <> cannot be mistaken for one — then splits on depth-0
    commas until the class body opens."""
    depth, colon = 0, -1
    for i, c in enumerate(tail):
        if c in "(<[":
            depth += 1
        elif c in ")>]":
            depth -= 1
        elif depth == 0 and c == "{":
            break
        elif depth == 0 and c == ":":
            colon = i
            break
        elif depth == 0 and c == "\n" and tail[:i].count("(") == tail[:i].count(")") and i > 0:
            # a declaration with no supertype list and no body on this line still ends at the body
            continue
    if colon < 0:
        return []
    names, buf, depth = [], "", 0
    for c in tail[colon + 1 :]:
        if c in "(<[":
            depth += 1
        elif c in ")>]":
            depth -= 1
        if depth == 0 and c == "{":
            break
        if depth == 0 and c == ",":
            names.append(buf)
            buf = ""
        else:
            buf += c
    names.append(buf)
    out = []
    for raw in names:
        m = re.match(r"\s*([A-Za-z_][\w.]*)", raw)
        if m:
            out.append(m.group(1))
    return out


class Decl:
    __slots__ = ("kind", "name", "supers", "path", "line")

    def __init__(self, kind: str, name: str, supers: list[str], path: str, line: int) -> None:
        self.kind, self.name, self.supers = kind, name, supers
        self.path, self.line = path, line


def declarations(sources: dict[str, str]) -> list[Decl]:
    out = []
    for path, src in sorted(sources.items()):
        for m in DECL.finditer(src):
            out.append(
                Decl(
                    m.group(1),
                    m.group(2),
                    supertypes(src[m.end() : m.end() + 2000]),
                    path,
                    src[: m.start()].count("\n") + 1,
                )
            )
    return out


def closeable_closure(decls: list[Decl]) -> set[str]:
    """Transitive closure of the seeds over the declaration graph, by SIMPLE name. Simple-name
    resolution is an over-approximation — two unrelated `Lease` classes in different modules would
    both join if either did — and it is the safe direction: it can only add types to the audit, and
    a false member fails loudly (by name, with its declaration site) instead of silently."""
    closeable: set[str] = set()
    changed = True
    while changed:
        changed = False
        for d in decls:
            if d.name in closeable:
                continue
            if any(s in SEEDS or s.split(".")[-1] in closeable for s in d.supers):
                closeable.add(d.name)
                changed = True
    return closeable


def handle_names(sources: dict[str, str], type_name: str) -> set[str]:
    """Identifiers bound to type_name: declared-type properties and parameters, and `val x = T(…)`."""
    names: set[str] = set()
    typed = re.compile(
        r"\b(?:val|var)?\s*([A-Za-z_]\w*)\s*:\s*(?:[A-Za-z_][\w.]*\.)?" + re.escape(type_name) + r"\s*\??"
    )
    initialised = re.compile(
        r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]*)?=\s*(?:[A-Za-z_][\w.]*\.)?"
        + re.escape(type_name)
        + r"\s*\("
    )
    for src in sources.values():
        names.update(m.group(1) for m in typed.finditer(src))
        names.update(m.group(1) for m in initialised.finditer(src))
    return names


def construct_and_use(sources: dict[str, str], type_name: str) -> bool:
    """`T(…).use` / `T(…)?.use` / `T(…).close()` — the construction closed on the spot. The paren
    walk is what makes this work across the line breaks a multi-argument construction wears."""
    pattern = re.compile(r"\b(?:[A-Za-z_][\w.]*\.)?" + re.escape(type_name) + r"\s*\(")
    for src in sources.values():
        for m in pattern.finditer(src):
            i, depth = m.end() - 1, 0
            while i < len(src):
                if src[i] == "(":
                    depth += 1
                elif src[i] == ")":
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            after = src[i + 1 : i + 40]
            if re.match(r"\s*\??\.(use\b|close\s*\()", after):
                return True
    return False


def closed_by_name(sources: dict[str, str], names: set[str]) -> bool:
    for name in names:
        closing = re.compile(
            r"\b" + re.escape(name) + r"\s*(?:\?\s*)?(?:\.use\b|\.close\s*\(|::close\b)"
        )
        for src in sources.values():
            if closing.search(src):
                return True
    return False


def read(root: pathlib.Path, patterns) -> dict[str, str]:
    out = {}
    for pattern in patterns if isinstance(patterns, tuple) else (patterns,):
        for path in sorted(root.glob(pattern)):
            out[str(path.relative_to(root))] = strip_comments(path.read_text(encoding="utf-8"))
    return out


def audit(root: pathlib.Path) -> list[str]:
    problems: list[str] = []
    main = read(root, MAIN_GLOB)
    if not main:
        return [
            f"no Kotlin main sources matched {MAIN_GLOB} under {root} — refusing to pass vacuously; "
            "a checker that reads nothing vouches for nothing."
        ]

    decls = declarations(main)
    closeable = closeable_closure(decls)

    # Parser-drift guard, computed a SECOND and independent way: the number of declarations the
    # parser attributed a seed supertype to must equal the number of supertype lists that MENTION a
    # seed. They can only disagree if `supertypes()` stopped reading a declaration shape it should
    # have read, which is how a denominator quietly shrinks.
    parsed_direct = {d.name for d in decls if any(s in SEEDS for s in d.supers)}
    mentioned = sum(len(DIRECT_MENTION.findall(src)) for src in main.values())
    # A seed may also appear as a property type or SAM constructor, so the mention count is an upper
    # bound; the failure that matters is the parser finding FEWER than the declarations that exist.
    if mentioned and not parsed_direct:
        problems.append(
            f"{mentioned} supertype list(s) mention AutoCloseable/Closeable but the parser "
            "attributed NONE to a declaration — supertypes() has drifted from the Kotlin it reads, "
            "so the closeable denominator is empty for a parser reason, not a code reason."
        )
    if not closeable:
        problems.append(
            "the closeable closure is EMPTY — refusing to pass vacuously. Either no type in this "
            "tree implements AutoCloseable/Closeable (then this checker has nothing to guard and "
            "should say so out loud) or the parse failed."
        )
        return problems

    concrete = [d for d in decls if d.name in closeable and d.kind != "interface"]
    for decl in sorted(concrete, key=lambda d: (d.path, d.line)):
        if decl.name in ALLOWLIST:
            reason = ALLOWLIST[decl.name]
            if not re.match(r"^\d{4}-\d{2}-\d{2}: \S", reason):
                problems.append(
                    f"{decl.path}:{decl.line} {decl.name} — ALLOWLIST entry is not "
                    "'YYYY-MM-DD: <reason>'. An exemption with no dated, written reason is an "
                    "absence wearing a label."
                )
            continue
        names = handle_names(main, decl.name)
        if construct_and_use(main, decl.name) or closed_by_name(main, names):
            continue
        constructed = re.search(
            r"\b(?:[A-Za-z_][\w.]*\.)?" + re.escape(decl.name) + r"\s*\(", "\n".join(main.values())
        )
        why = (
            "constructed in a main source but never closed from one"
            if constructed
            else "never constructed in a main source either — dead in production, or its only "
            "construction moved to tests"
        )
        problems.append(
            f"{decl.path}:{decl.line} {decl.name} implements AutoCloseable/Closeable "
            f"(via {' -> '.join(decl.supers) or 'AutoCloseable'}) and is {why}. "
            "Close it from production: `use { }`, a `close()` on a handle, or `handle::close` in a "
            "cleanup. A closeable whose only close() callers are tests is a leak with a green suite."
        )
    return problems


def test_only_closers(root: pathlib.Path, type_name: str) -> list[str]:
    """Reporting aid: where the type IS closed, when it is not closed in main. Not part of the
    verdict — it is the sentence that makes a RED actionable."""
    tests = read(root, TEST_GLOBS)
    names = handle_names(tests, type_name) | {type_name}
    hits = []
    for path, src in tests.items():
        for name in names:
            for m in re.finditer(
                r"\b" + re.escape(name) + r"\s*(?:\([^()]*\))?\s*(?:\?\s*)?(?:\.use\b|\.close\s*\(|::close\b)",
                src,
            ):
                hits.append(f"{path}:{src[: m.start()].count(chr(10)) + 1}")
    return sorted(set(hits))


# ── selftest fixtures ──────────────────────────────────────────────────────────────────────────
SPI_CONTRACT = """package splice.spi

public interface CodeModeRuntime : AutoCloseable {
    public fun start(): Unit
}
"""

# Each of the three evidence forms, once.
COMPLIANT_APP = """package splice.app

internal class WorkerSession(private val start: WorkerStart) : AutoCloseable {
    override fun close() = Unit
}

public class DaemonLock(private val lockFile: Path) : AutoCloseable {
    override fun close() = Unit
}

internal class Lease(val label: String) : AutoCloseable {
    override fun close() = Unit
}

internal class Runner {
    fun one(start: WorkerStart) = WorkerSession(start).use { session -> session.toString() }

    fun two(path: Path) {
        val lock = DaemonLock(path)
        lock.close()
    }

    fun three(lease: Lease) {
        Cancellables.runCatchingCleanup(lease::close)
    }
}
"""

# The scar: a two-hop closeable closed only from src/test.
LEAKY_APP = """package splice.app

public class JvmCodeModeRuntime : CodeModeRuntime {
    override fun start() = Unit
    override fun close() = Unit
}

internal class Arm {
    fun build() = Wiring(runtime = JvmCodeModeRuntime())
}
"""

LEAKY_TEST = """package splice.app

class CodeModeRuntimeTest {
    fun reclaims() {
        JvmCodeModeRuntime().use { runtime -> runtime.start() }
    }
}
"""

# The BORING case: exactly one closeable in the whole tree, and it is closed.
ONE_CLOSEABLE_APP = """package splice.app

public class DaemonLock(private val lockFile: Path) : AutoCloseable {
    override fun close() = Unit
}

internal class Runner {
    fun go(path: Path) {
        val lock = DaemonLock(path)
        lock.close()
    }
}
"""

NO_CLOSEABLE_APP = """package splice.app

internal class Plain(val label: String) {
    fun go() = Unit
}
"""


def write(root: pathlib.Path, files: dict[str, str]) -> None:
    for name in list(root.glob("gateway/*/src/*/**/*.kt")):
        name.unlink()
    for rel, body in files.items():
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")


APP = "gateway/app/src/main/kotlin/splice/app/App.kt"
SPI = "gateway/provider-spi/src/main/kotlin/splice/spi/Spi.kt"
TEST = "gateway/app/src/test/kotlin/RuntimeTest.kt"


def selftest() -> int:
    failures: list[str] = []
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)

        # 1. GREEN: every evidence form, plus the interface exclusion.
        write(root, {SPI: SPI_CONTRACT, APP: COMPLIANT_APP})
        hits = audit(root)
        if hits:
            failures.append(f"1. the compliant tree must be GREEN, got: {hits}")

        # 2. the BORING case: exactly one closeable, closed. A wall that only works on a crowd is
        #    not a wall (§24 — the boring case is the one that gets waved through).
        write(root, {APP: ONE_CLOSEABLE_APP})
        hits = audit(root)
        if hits:
            failures.append(f"2. one-closeable tree, closed, must be GREEN, got: {hits}")
        #    ...and the same single closeable, NOT closed, must be RED — so case 2's green is a
        #    measurement of the evidence and not of an empty denominator.
        write(root, {APP: ONE_CLOSEABLE_APP.replace("        lock.close()\n", "")})
        hits = audit(root)
        if not any("DaemonLock" in h for h in hits):
            failures.append(f"2b. the boring case must be able to FAIL, got: {hits}")

        # 3. RED BY NAME through a TWO-HOP interface chain, closed only from src/test — the scar.
        write(root, {SPI: SPI_CONTRACT, APP: LEAKY_APP, TEST: LEAKY_TEST})
        hits = audit(root)
        if not any("JvmCodeModeRuntime" in h for h in hits):
            failures.append(f"3. a two-hop closeable closed only in tests must be RED BY NAME, got: {hits}")
        if not any("never closed from one" in h for h in hits):
            failures.append(f"3b. the RED must say it IS constructed in main, got: {hits}")
        if any(h.split()[1] == "CodeModeRuntime" for h in hits if len(h.split()) > 1):
            failures.append(f"3c. the INTERFACE must not be reported, only the class, got: {hits}")
        if len([h for h in hits if "implements AutoCloseable" in h]) != 1:
            failures.append(f"3e. exactly ONE finding is expected here, got: {hits}")
        if not test_only_closers(root, "JvmCodeModeRuntime"):
            failures.append("3d. the reporting aid must locate the test-only closers")

        # 4. the same tree with the close moved into main goes GREEN — proves 3 failed for the
        #    stated reason (no main closer) and not for some incidental parse difference.
        write(
            root,
            {
                SPI: SPI_CONTRACT,
                APP: LEAKY_APP.replace(
                    "    fun build() = Wiring(runtime = JvmCodeModeRuntime())",
                    "    fun build() {\n"
                    "        val runtime = JvmCodeModeRuntime()\n"
                    "        runtime.close()\n"
                    "    }",
                ),
                TEST: LEAKY_TEST,
            },
        )
        hits = audit(root)
        if hits:
            failures.append(f"4. closing it from main must go GREEN, got: {hits}")

        # 5. a dated allowlist entry is a disposition; an undated one is not.
        write(root, {SPI: SPI_CONTRACT, APP: LEAKY_APP, TEST: LEAKY_TEST})
        ALLOWLIST["JvmCodeModeRuntime"] = "2026-09-17: selftest fixture, allowlisted on purpose."
        hits = audit(root)
        if hits:
            failures.append(f"5. a dated allowlist entry must be GREEN, got: {hits}")
        ALLOWLIST["JvmCodeModeRuntime"] = "because I said so"
        hits = audit(root)
        if not any("not 'YYYY-MM-DD" in h for h in hits):
            failures.append(f"5b. an UNDATED allowlist entry must be RED, got: {hits}")
        ALLOWLIST.pop("JvmCodeModeRuntime")

        # 6. refuse to pass vacuously: no closeables at all, and no sources at all.
        write(root, {APP: NO_CLOSEABLE_APP})
        hits = audit(root)
        if not any("refusing to pass vacuously" in h for h in hits):
            failures.append(f"6. a tree with no closeable must REFUSE, not pass, got: {hits}")
        write(root, {})
        hits = audit(root)
        if not any("refusing to pass vacuously" in h for h in hits):
            failures.append(f"6b. a tree with no main sources must REFUSE, got: {hits}")

    if failures:
        print("autocloseable-closed --selftest FAIL")
        for failure in failures:
            print(f"  x {failure}")
        return 1
    print(
        "autocloseable-closed --selftest OK — compliant GREEN (all 3 evidence forms), boring "
        "one-type case GREEN and red-provable, two-hop test-only closer RED by name, same tree "
        "GREEN once closed from main, dated allowlist GREEN / undated RED, empty parse REFUSES"
    )
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    # `check` is accepted (and ignored) so the gate `run` line reads like its siblings in
    # checks/gate.sh; anything else is a typo and must fail rather than be silently dropped.
    for arg in sys.argv[1:]:
        if arg != "check":
            print(f"autocloseable-closed: unknown argument {arg!r} (usage: [check] | --selftest)")
            return 2
    root = pathlib.Path(__file__).resolve().parent.parent
    problems = audit(root)
    if not problems:
        main_sources = read(root, MAIN_GLOB)
        concrete = [
            d
            for d in declarations(main_sources)
            if d.name in closeable_closure(declarations(main_sources)) and d.kind != "interface"
        ]
        print(f"autocloseable-closed: PASS ({len(concrete)} concrete closeable type(s) accounted for)")
        return 0
    print("autocloseable-closed: FAIL")
    for problem in problems:
        print(f"  x {problem}")
        name = problem.split()[1] if len(problem.split()) > 1 else ""
        for hit in test_only_closers(root, name):
            print(f"      closed only here: {hit}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
