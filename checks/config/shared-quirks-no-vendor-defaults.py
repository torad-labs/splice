#!/usr/bin/env python3
"""V4-29 — shared dialect quirks must not default a vendor fact.

THE CLASS. A non-null default on ResponsesQuirks or ChatQuirks that is a model-id
regex, an HTTP header name, or a vendor host is inherited by every provider that
does not override it. V4-20 moved ChatQuirks.xhighModels to GrokQuirks. V4-28
moved the lite regex and lite header to CodexQuirks. This wall closes the class
so the next one cannot land as a dialect default.

SCOPE. Shared types are the *Quirks data classes under gateway/dialect-*/src/main.
Per-vendor types (CodexQuirks, GrokQuirks, OpenAiQuirks and their kin under
gateway/provider-*) are exactly where such values are allowed to live; they are
out of scope by design. A vendor fact that belongs to one backend is set on that
backend's profile, not on the dialect.

DENOMINATOR. Fields are enumerated by parsing the primary constructor of every
shared *Quirks data class on disk. A field added tomorrow is in scope without
editing this checker. The field list is not an allowlist this file types.

DISPOSITION. Every parsed field is classified: required (no default), clean-null
(default null — the exemplary shape for a vendor-shaped knob), clean-unshaped
(a default that is not a model id, header name, or vendor host), or FAIL (a
vendor-shaped non-null default, named). Absence is not a disposition. There is
no exemption table; a vendor-shaped default that must stay is a bug in the type
boundary, not a reason to write a blank pass.

NOT CAUGHT, and why.

  liteTextVerbosity defaults to low from a codex-cli 0.145.0 measurement.
  sendClientMetadata defaults to true. Both are lite-gated, so they are latent
  rather than live, and neither is a model id, header name, or vendor host.
  This wall's class is vendor IDENTITY leaking across backends, not vendor
  wire-behavior. What would catch them: a wall on lite-gated defaults that
  encode a measured vendor wire byte — the class V4-28 already moved for the
  lite header pair.

  effortVocabulary defaults to DefaultEffortVocabulary(). That type's own
  header says it is dialect-owned, not a vendor fact; grok supplies
  GrokEffortVocabulary on the vendor profile. Not a model id, header, or host.
  What would catch a vendor-owned vocabulary parked as a dialect default: a
  wall on EffortVocabulary defaults whose runtime class is not the dialect
  default — which this one is not.

  maxTokensField defaults to max_tokens. That is a JSON body key shared by
  OpenAI-compatible chat, not an HTTP header and not a vendor host.

  Knob.FOLD_REASONING_MODELS defaults to gpt-5.6-luna,gpt-5.6-terra,gpt-5.5.
  That is a model-id list on a SHARED config type, and this wall does not
  catch it. It is a fail-safe ALLOWLIST: folding is off for any model not
  in the set, so an unlisted vendor model loses a Codex-only repair it
  never had rather than silently gaining a vendor behaviour. The gemini
  clamp is fail-unsafe (a foreign id inherits a vendor reject). Different
  class. What would catch a fail-safe allowlist parked as a global Knob
  default: a wall on Knob STRING defaults that contain model-id tokens.
  Knob.kt is also a different syntax (enum entries, not a *Quirks data
  class constructor), so it is outside this parser by construction.

SELFTEST. --selftest injects fixtures. RED on a synthetic Regex/header/host
default. GREEN on the compliant form (null vendor-shaped knobs, plus the
boring extra field that defaults to null). A vendor *Quirks file under
provider-* with a Regex default is not in scope.
"""
from __future__ import annotations

import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]

CLASS_HEAD = re.compile(r"public data class (\w+Quirks)\s*\(", re.MULTILINE)
HEADER_DEFAULT = re.compile(r'^["\']x-[A-Za-z0-9-]+["\']$', re.IGNORECASE)
HOST_DEFAULT = re.compile(
    r"https?://|api\.openai\.|api\.x\.ai|anthropic\.com|openrouter\.ai|moonshot\.cn",
    re.IGNORECASE,
)


def dialect_quirk_files(root: pathlib.Path) -> list[pathlib.Path]:
    files: list[pathlib.Path] = []
    for dialect in sorted(root.glob("gateway/dialect-*")):
        main = dialect / "src" / "main"
        if main.is_dir():
            files.extend(sorted(main.rglob("*.kt")))
    return files


def extract_constructor(source: str, start: int) -> str | None:
    """Return the primary-constructor text inside the parens at start, or None."""
    i = start
    depth = 0
    in_string = False
    quote = ""
    escape = False
    body_start = None
    while i < len(source):
        ch = source[i]
        if in_string:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == quote:
                in_string = False
            i += 1
            continue
        if ch in "\"'":
            in_string = True
            quote = ch
            i += 1
            continue
        if ch == "/" and i + 1 < len(source) and source[i + 1] == "/":
            nl = source.find("\n", i)
            i = len(source) if nl < 0 else nl
            continue
        if ch == "/" and i + 1 < len(source) and source[i + 1] == "*":
            end = source.find("*/", i + 2)
            i = len(source) if end < 0 else end + 2
            continue
        if ch == "(":
            depth += 1
            if depth == 1:
                body_start = i + 1
            i += 1
            continue
        if ch == ")":
            depth -= 1
            if depth == 0 and body_start is not None:
                return source[body_start:i]
            i += 1
            continue
        i += 1
    return None


def split_params(body: str) -> list[str]:
    parts: list[str] = []
    buf: list[str] = []
    depth = 0
    in_string = False
    quote = ""
    escape = False
    i = 0
    while i < len(body):
        ch = body[i]
        if in_string:
            buf.append(ch)
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == quote:
                in_string = False
            i += 1
            continue
        if ch in "\"'":
            in_string = True
            quote = ch
            buf.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < len(body) and body[i + 1] == "/":
            nl = body.find("\n", i)
            i = len(body) if nl < 0 else nl
            continue
        if ch == "/" and i + 1 < len(body) and body[i + 1] == "*":
            end = body.find("*/", i + 2)
            i = len(body) if end < 0 else end + 2
            continue
        if ch in "({[<":
            depth += 1
            buf.append(ch)
            i += 1
            continue
        if ch in ")}]>":
            depth -= 1
            buf.append(ch)
            i += 1
            continue
        if ch == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    if buf:
        parts.append("".join(buf))
    return parts


PARAM = re.compile(
    r"\bval\s+(\w+)\s*:\s*([^=]+?)(?:\s*=\s*(.*))?\s*$",
    re.DOTALL,
)


def parse_params(body: str) -> list[tuple[str, str, str | None]]:
    fields: list[tuple[str, str, str | None]] = []
    for raw in split_params(body):
        text = raw.strip()
        if not text:
            continue
        match = PARAM.search(text)
        if match is None:
            continue
        name, type_text, default = match.group(1), match.group(2).strip(), match.group(3)
        default = None if default is None else default.strip()
        fields.append((name, type_text, default))
    return fields


def parse_shared_quirks(source: str) -> list[tuple[str, list[tuple[str, str, str | None]]]]:
    found: list[tuple[str, list[tuple[str, str, str | None]]]] = []
    for match in CLASS_HEAD.finditer(source):
        body = extract_constructor(source, match.end() - 1)
        if body is None:
            continue
        found.append((match.group(1), parse_params(body)))
    return found


def is_null_default(default: str | None) -> bool:
    return default is not None and default.strip() == "null"


def vendor_shaped(name: str, type_text: str, default: str | None) -> str | None:
    """Return the shape label, or None if this field is not a vendor identity fact."""
    type_flat = re.sub(r"\s+", "", type_text)
    if "Regex" in type_flat or name.endswith("Models") or "ModelRegex" in name:
        return "model-id"
    if "Header" in name:
        return "header-name"
    if default is None:
        return None
    stripped = default.strip()
    inner = stripped.strip('"').strip("'")
    if inner.lower().startswith("x-") and HEADER_DEFAULT.match('"' + inner + '"'):
        return "header-name"
    if HOST_DEFAULT.search(stripped):
        return "vendor-host"
    return None


def classify(name: str, type_text: str, default: str | None) -> tuple[str, str]:
    """Return (kind, detail) where kind is required, clean-null, clean-unshaped, or fail."""
    shape = vendor_shaped(name, type_text, default)
    if default is None:
        return "required", "no default"
    if is_null_default(default):
        return "clean-null", "null default" + (f" ({shape})" if shape else "")
    if shape is None:
        return "clean-unshaped", "default is not a model id, header name, or vendor host"
    return "fail", f"{shape} default: {default}"


def check_source(label: str, source: str) -> list[str]:
    problems: list[str] = []
    classes = parse_shared_quirks(source)
    if not classes:
        return problems
    for class_name, fields in classes:
        if not fields:
            problems.append(f"{label}: {class_name} has no parsed fields — refusing to pass vacuously")
            continue
        for name, type_text, default in fields:
            kind, detail = classify(name, type_text, default)
            if kind == "fail":
                problems.append(f"{label}: {class_name}.{name} {detail}")
    return problems


def check_tree(root: pathlib.Path) -> list[str]:
    problems: list[str] = []
    files = dialect_quirk_files(root)
    saw = False
    for path in files:
        source = path.read_text(encoding="utf-8")
        classes = parse_shared_quirks(source)
        if not classes:
            continue
        saw = True
        rel = path.as_posix()
        if str(root) in rel:
            rel = str(path.relative_to(root)).replace("\\", "/")
        problems.extend(check_source(rel, source))
    if not saw:
        problems.append("no shared *Quirks data class under gateway/dialect-*/src/main")
    return problems


COMPLIANT = """
public data class ResponsesQuirks(
    val providerTag: String,
    val summaryRejectModelRegex: Regex? = null,
    val effortMaxRejectModelRegex: Regex? = null,
    val responsesLiteHeader: String? = null,
    val minImageEdgePx: Int? = null,
    val extra: String? = null,
)
"""

REGEX_VIOLATION = """
public data class ResponsesQuirks(
    val providerTag: String,
    val effortMaxRejectModelRegex: Regex? = Regex("mini", RegexOption.IGNORE_CASE),
)
"""

HEADER_VIOLATION = """
public data class ResponsesQuirks(
    val providerTag: String,
    val responsesLiteHeader: String? = "x-openai-internal-codex-responses-lite",
)
"""

HOST_VIOLATION = """
public data class ResponsesQuirks(
    val providerTag: String,
    val baseUrl: String? = "https://api.openai.com/v1",
)
"""

VENDOR_FILE = """
public data class CodexQuirks(
    val effortMaxRejectModelRegex: Regex? = Regex("mini", RegexOption.IGNORE_CASE),
)
"""


def selftest() -> int:
    failures: list[str] = []
    if check_source("compliant", COMPLIANT):
        failures.append("compliant null vendor knobs plus a boring null extra must be GREEN")
    regex_hits = check_source("regex", REGEX_VIOLATION)
    if not any("effortMaxRejectModelRegex" in hit for hit in regex_hits):
        failures.append("synthetic Regex default must be RED by field name")
    header_hits = check_source("header", HEADER_VIOLATION)
    if not any("responsesLiteHeader" in hit for hit in header_hits):
        failures.append("synthetic header-name default must be RED by field name")
    host_hits = check_source("host", HOST_VIOLATION)
    if not any("baseUrl" in hit for hit in host_hits):
        failures.append("synthetic vendor-host default must be RED by field name")
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        dialect = root / "gateway/dialect-openai-responses/src/main/kotlin"
        vendor = root / "gateway/provider-codex/src/main/kotlin"
        dialect.mkdir(parents=True)
        vendor.mkdir(parents=True)
        (dialect / "ResponsesQuirks.kt").write_text(COMPLIANT, encoding="utf-8")
        (vendor / "CodexQuirks.kt").write_text(VENDOR_FILE, encoding="utf-8")
        live = check_tree(root)
        if live:
            failures.append("temp tree with compliant dialect and vendor Regex must be GREEN, got: " + "; ".join(live))
        (dialect / "ResponsesQuirks.kt").write_text(REGEX_VIOLATION, encoding="utf-8")
        live_red = check_tree(root)
        if not any("effortMaxRejectModelRegex" in hit for hit in live_red):
            failures.append("temp tree Regex default must be RED")
    if failures:
        print("shared-quirks-no-vendor-defaults SELFTEST FAIL:")
        for failure in failures:
            print("  " + failure)
        return 1
    print(
        "shared-quirks-no-vendor-defaults SELFTEST OK — null vendor knobs and a boring "
        "null extra are green; Regex, header-name and vendor-host defaults are red by "
        "name; a provider-codex CodexQuirks Regex is out of scope"
    )
    return 0


def report(root: pathlib.Path) -> None:
    files = dialect_quirk_files(root)
    print("shared-quirks-no-vendor-defaults fields:")
    for path in files:
        source = path.read_text(encoding="utf-8")
        for class_name, fields in parse_shared_quirks(source):
            rel = str(path.relative_to(root)).replace("\\", "/")
            print(f"  {rel} {class_name}")
            for name, type_text, default in fields:
                kind, detail = classify(name, type_text, default)
                print(f"    {kind}: {name}: {detail}")


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    root = ROOT
    for arg in sys.argv[1:]:
        if arg not in {"check", "report", "--selftest"} and not arg.startswith("-"):
            root = pathlib.Path(arg)
            break
    if not root.exists():
        print("shared-quirks-no-vendor-defaults: tree missing", file=sys.stderr)
        return 1
    root = root.resolve()
    if "report" in sys.argv:
        report(root)
        return 0
    problems = check_tree(root)
    if problems:
        print("shared-quirks-no-vendor-defaults RED:")
        for problem in problems:
            print("  " + problem)
        return 1
    print(
        "shared-quirks-no-vendor-defaults GREEN: no vendor-fact default on a shared "
        "*Quirks type under gateway/dialect-*/src/main"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
