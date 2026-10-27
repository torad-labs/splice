// One Kotlin or Java source file, read for what the pre-push test selection needs (test-impact.ts): the names it declares at
// top level, the names it mentions in code and in string literals, and the test classes it holds. Pure: text in, facts out.
//
// It is a lexer, not a compiler. It follows what can hide a name or fake one: nested block comments, strings with templates that
// hold code that holds strings, raw strings, char literals, backticked names. A source it cannot follow to the end with every
// comment, string and bracket closed is reported unparsed, and then every identifier of its raw text counts, comments included:
// the selection that reads this sees more names, never fewer.

/** A top-level class or object, as a unit `test` task sees it. */
export interface TestClassDecl {
  readonly name: string;
  /** Tagged as a law: the unit task excludes it and `lawTest` runs it. */
  readonly law: boolean;
  /** A concrete class whose own body declares a JUnit test, with nothing on the class that could keep JUnit from running it. */
  readonly runnable: boolean;
}

export interface SourceScan {
  /** Every comment, string, template and bracket closes. A source that does not is one the selection cannot see into. */
  readonly parsed: boolean;
  readonly packageName: string;
  /** The names other files can refer to: its top-level declarations but file-private functions and properties, and its JVM facade. */
  readonly declared: readonly string[];
  /** The names of the sealed classes and interfaces it declares, at any depth. */
  readonly sealed: readonly string[];
  /** It declares a top-level operator function, which a file in its package, or one importing the package whole, can use unnamed. */
  readonly operators: boolean;
  /** The packages it imports whole (`import a.b.*`). */
  readonly wildcards: readonly string[];
  /** What it imports by name, as written before any alias (`a.b.C`, `a.b.C.Companion.f`). */
  readonly imports: readonly string[];
  /** The declared names that are functions or properties, which only code can use: reflection reaches them through a class. */
  readonly values: readonly string[];
  /** Every identifier in its code and in its string literals. Comments are not read. */
  readonly tokens: ReadonlySet<string>;
  /** Every identifier in its code, string templates included, and none from a string literal's own text. */
  readonly code: ReadonlySet<string>;
  /** The text of its string literals, without their templates. */
  readonly strings: readonly string[];
  /** Its top-level classes and objects. */
  readonly classes: readonly TestClassDecl[];
  /** It loads classes the analysis cannot name: a class name computed at run time, a service loader, a classpath scan. */
  readonly loadsByName: boolean;
}

interface Tok {
  k: "id" | "sym" | "str";
  /** The identifier, the symbol, or a string literal's text. */
  t: string;
  /** Brace depth, 0 at top level. Code inside a string template sits at TEMPLATE, which no declaration reads. */
  d: number;
  /** Paren and bracket depth, outside templates. */
  p: number;
  /** An identifier written in backticks, or a string literal with a template. */
  q: boolean;
}

const TEMPLATE = 1_000_000;

const MODIFIERS = new Set([
  "public", "private", "protected", "internal", "open", "final", "abstract", "sealed", "data", "enum", "annotation", "inner",
  "value", "inline", "noinline", "crossinline", "operator", "infix", "suspend", "tailrec", "external", "const", "lateinit",
  "override", "expect", "actual", "companion", "vararg", "reified", "static", "default", "synchronized", "native", "transient",
  "volatile", "strictfp",
]);
const DECLARES = new Set(["class", "interface", "object", "typealias", "fun", "val", "var", "package", "import"]);
const TEST_ANNOTATIONS = new Set(["Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate"]);
/** Class annotations that never keep JUnit from running the class's tests. Any other one might (a tag, a condition, a custom meta-annotation). */
const SAFE_CLASS_ANNOTATIONS = new Set([
  "Suppress", "TestInstance", "Execution", "Timeout", "ExtendWith", "DisplayName", "DisplayNameGeneration", "OptIn", "Isolated",
  "ResourceLock", "TestMethodOrder", "TestClassOrder", "Nested",
]);
/** Method annotations that can keep JUnit from running the method here. */
const SKIPS_METHOD = /^(Disabled|Tag|Tags|EnabledIf.*|DisabledIf.*|EnabledOn.*|DisabledOn.*|EnabledFor.*|DisabledFor.*)$/;
const DISCOVERY = new Set(["ServiceLoader", "ClassGraph", "Reflections"]);
const IDENTIFIER = /[\p{L}_][\p{L}\p{N}_]*/gu;

const isDigit = (c: number): boolean => c >= 48 && c <= 57;
const isAsciiLetter = (c: number): boolean => (c >= 97 && c <= 122) || (c >= 65 && c <= 90) || c === 95;
const isLetter = (c: number): boolean => isAsciiLetter(c) || (c > 127 && /\p{L}/u.test(String.fromCharCode(c)));
const isIdentPart = (c: number): boolean => isAsciiLetter(c) || isDigit(c) || (c > 127 && /[\p{L}\p{N}]/u.test(String.fromCharCode(c)));

const ESCAPES: Readonly<Record<string, string>> = { t: "\t", n: "\n", r: "\r", b: "\b", f: "\f", "0": "\0" };

interface Lexed {
  readonly toks: Tok[];
  readonly strings: string[];
  readonly ok: boolean;
}

function lex(text: string, java: boolean): Lexed {
  const toks: Tok[] = [];
  const strings: string[] = [];
  const n = text.length;
  // A script line (#!) is not Kotlin.
  let i = text.startsWith("#!") ? (text.indexOf("\n") < 0 ? n : text.indexOf("\n")) : 0;
  let ok = true;
  let depth = 0;
  let parens = 0;
  const idStart = (c: number): boolean => isLetter(c) || (java && c === 36);
  const idPart = (c: number): boolean => isIdentPart(c) || (java && c === 36);

  const push = (k: Tok["k"], t: string, inTemplate: boolean, q = false): Tok => {
    const tok: Tok = { k, t, d: inTemplate ? TEMPLATE : depth, p: inTemplate ? 0 : parens, q };
    toks.push(tok);
    return tok;
  };

  const blockComment = (): void => {
    let nest = 1;
    i += 2;
    while (i < n) {
      const c = text.charCodeAt(i);
      if (c === 42 && text.charCodeAt(i + 1) === 47) {
        i += 2;
        nest--;
        if (nest === 0 || java) return;
        continue;
      }
      if (!java && c === 47 && text.charCodeAt(i + 1) === 42) {
        nest++;
        i += 2;
        continue;
      }
      i++;
    }
    ok = false;
  };

  // A template: `$name` or `${ code }`, inside a Kotlin string. Returns whether one was read at i.
  const template = (pieces: string[], seg: { text: string }): boolean => {
    const next = text.charCodeAt(i + 1);
    if (next === 123) {
      pieces.push(seg.text);
      seg.text = "";
      i += 2;
      code(true);
      return true;
    }
    if (idStart(next)) {
      pieces.push(seg.text);
      seg.text = "";
      const start = i + 1;
      i += 2;
      while (i < n && idPart(text.charCodeAt(i))) i++;
      push("id", text.slice(start, i), true);
      return true;
    }
    return false;
  };

  const finish = (tok: Tok, pieces: string[], seg: { text: string }): void => {
    pieces.push(seg.text);
    for (const piece of pieces) if (piece !== "") strings.push(piece);
    tok.t = pieces.join("");
    tok.q = pieces.length > 1;
  };

  const quoted = (inTemplate: boolean): void => {
    const tok = push("str", "", inTemplate);
    const pieces: string[] = [];
    const seg = { text: "" };
    i++;
    while (i < n) {
      const c = text.charCodeAt(i);
      if (c === 92) {
        const e = text[i + 1] ?? "";
        if (e === "u" && /^[0-9a-fA-F]{4}$/.test(text.slice(i + 2, i + 6))) {
          seg.text += String.fromCharCode(Number.parseInt(text.slice(i + 2, i + 6), 16));
          i += 6;
        } else {
          seg.text += ESCAPES[e] ?? e;
          i += 2;
        }
        continue;
      }
      if (c === 34) {
        i++;
        finish(tok, pieces, seg);
        return;
      }
      if (c === 10) {
        // A quoted string does not cross a line: the source is broken here, and the next line is read as code again.
        ok = false;
        finish(tok, pieces, seg);
        return;
      }
      if (c === 36 && !java && template(pieces, seg)) continue;
      seg.text += text[i];
      i++;
    }
    ok = false;
    finish(tok, pieces, seg);
  };

  const raw = (inTemplate: boolean): void => {
    const tok = push("str", "", inTemplate);
    const pieces: string[] = [];
    const seg = { text: "" };
    i += 3;
    while (i < n) {
      const c = text.charCodeAt(i);
      if (java && c === 92) {
        seg.text += text[i + 1] ?? "";
        i += 2;
        continue;
      }
      if (c === 34 && text.startsWith('"""', i)) {
        if (java) {
          i += 3;
          finish(tok, pieces, seg);
          return;
        }
        // Kotlin closes a raw string on the last three quotes of a run; the ones before are its text.
        let j = i;
        while (j < n && text.charCodeAt(j) === 34) j++;
        seg.text += '"'.repeat(j - i - 3);
        i = j;
        finish(tok, pieces, seg);
        return;
      }
      if (c === 36 && !java && template(pieces, seg)) continue;
      seg.text += text[i];
      i++;
    }
    ok = false;
    finish(tok, pieces, seg);
  };

  const char = (): void => {
    let j = i + 1;
    if (text.charCodeAt(j) === 92) j += text[j + 1] === "u" ? 6 : 2;
    else j += 1;
    if (text.charCodeAt(j) === 39) {
      i = j + 1;
      return;
    }
    ok = false;
    i++;
  };

  const backtick = (inTemplate: boolean): void => {
    const end = text.indexOf("`", i + 1);
    const line = text.indexOf("\n", i + 1);
    if (end < 0 || (line >= 0 && line < end)) {
      ok = false;
      i++;
      return;
    }
    push("id", text.slice(i + 1, end), inTemplate, true);
    i = end + 1;
  };

  const number = (): void => {
    i++;
    while (i < n) {
      const c = text.charCodeAt(i);
      if (isIdentPart(c)) i++;
      else if (c === 46 && isDigit(text.charCodeAt(i + 1))) i++;
      else break;
    }
  };

  // Code until the end, or, inside a template, until the brace that closes it.
  function code(inTemplate: boolean): void {
    let braces = 0;
    let tparens = 0;
    while (i < n) {
      const c = text.charCodeAt(i);
      if (c === 32 || c === 9 || c === 10 || c === 13 || c === 12) {
        i++;
        continue;
      }
      if (c === 47) {
        const next = text.charCodeAt(i + 1);
        if (next === 47) {
          const end = text.indexOf("\n", i);
          i = end < 0 ? n : end;
          continue;
        }
        if (next === 42) {
          blockComment();
          continue;
        }
      }
      if (c === 34) {
        if (text.startsWith('"""', i)) raw(inTemplate);
        else quoted(inTemplate);
        continue;
      }
      if (c === 39) {
        char();
        continue;
      }
      if (c === 96 && !java) {
        backtick(inTemplate);
        continue;
      }
      if (idStart(c)) {
        const start = i;
        i++;
        while (i < n && idPart(text.charCodeAt(i))) i++;
        push("id", text.slice(start, i), inTemplate);
        continue;
      }
      if (isDigit(c)) {
        number();
        continue;
      }
      if (c === 123) {
        push("sym", "{", inTemplate);
        if (inTemplate) braces++;
        else depth++;
        i++;
        continue;
      }
      if (c === 125) {
        i++;
        if (inTemplate) {
          if (braces === 0) return;
          braces--;
        } else if (depth === 0) ok = false;
        else depth--;
        push("sym", "}", inTemplate);
        continue;
      }
      if (c === 40 || c === 91) {
        push("sym", c === 40 ? "(" : "[", inTemplate);
        if (inTemplate) tparens++;
        else parens++;
        i++;
        continue;
      }
      if (c === 41 || c === 93) {
        if (inTemplate) tparens--;
        else if (parens === 0) ok = false;
        else parens--;
        push("sym", c === 41 ? ")" : "]", inTemplate);
        i++;
        continue;
      }
      if (c === 45 && text.charCodeAt(i + 1) === 62) {
        push("sym", "->", inTemplate);
        i += 2;
        continue;
      }
      if (c === 58 && text.charCodeAt(i + 1) === 58) {
        push("sym", "::", inTemplate);
        i += 2;
        continue;
      }
      push("sym", text[i] ?? "", inTemplate);
      i++;
    }
    if (inTemplate || tparens !== 0) ok = false;
  }

  code(false);
  if (depth !== 0 || parens !== 0) ok = false;
  return { toks, strings, ok };
}

interface Annotation {
  readonly name: string;
  readonly args: readonly string[];
  readonly next: number;
}

/** The index of the bracket that closes the one at [open], or the end. */
function closeOf(toks: readonly Tok[], open: number): number {
  const at = toks[open];
  if (!at) return toks.length;
  for (let j = open + 1; j < toks.length; j++) {
    const t = toks[j];
    if (t && t.d !== TEMPLATE && t.k === "sym" && (t.t === ")" || t.t === "]") && t.p === at.p) return j;
  }
  return toks.length;
}

/** The index of the `>` that closes the type brackets opening at [open], or the end. */
function closeAngle(toks: readonly Tok[], open: number): number {
  let level = 0;
  for (let j = open; j < toks.length; j++) {
    const t = toks[j];
    if (!t || t.k !== "sym") continue;
    if (t.t === "<") level++;
    else if (t.t === ">") {
      level--;
      if (level === 0) return j;
    } else if (t.t === "{" || t.t === "=" || t.t === ";") return j - 1;
  }
  return toks.length;
}

/** The annotation whose `@` is at [at]: its simple name and its string arguments. */
function annotation(toks: readonly Tok[], at: number): Annotation {
  let j = at + 1;
  if (toks[j]?.k === "id" && toks[j + 1]?.t === ":" && toks[j + 2]?.k === "id") j += 2;
  if (toks[j]?.t === "[") return { name: "", args: [], next: closeOf(toks, j) + 1 };
  let name = "";
  while (toks[j]?.k === "id") {
    name = toks[j]?.t ?? "";
    j++;
    if (toks[j]?.t === "." && toks[j + 1]?.k === "id") j++;
    else break;
  }
  const args: string[] = [];
  if (toks[j]?.t === "(") {
    const end = closeOf(toks, j);
    for (let k = j + 1; k < end; k++) {
      const t = toks[k];
      if (t?.k === "str") args.push(t.t);
    }
    j = end + 1;
  }
  return { name, args, next: j };
}

/**
 * Whether the class body between [open] and [close] declares, as its own member, a JUnit test this class runs. [unitCalls] are
 * the calls known to return Unit, so a test whose expression body is one of them returns nothing too.
 */
function declaresTest(toks: readonly Tok[], open: number, close: number, unitCalls: ReadonlySet<string>): boolean {
  for (let k = open + 1; k < close; k++) {
    const at = toks[k];
    if (!at || at.d !== 1 || at.p !== 0 || at.k !== "sym" || at.t !== "@") continue;
    const first = annotation(toks, k);
    if (!TEST_ANNOTATIONS.has(first.name)) continue;
    const names = [first.name];
    const mods = new Set<string>();
    let m = first.next;
    while (m < close) {
      const t = toks[m];
      if (t?.k === "sym" && t.t === "@") {
        const next = annotation(toks, m);
        names.push(next.name);
        m = next.next;
      } else if (t?.k === "id" && !t.q && MODIFIERS.has(t.t)) {
        mods.add(t.t);
        m++;
      } else break;
    }
    if (toks[m]?.t !== "fun" || mods.has("private") || mods.has("abstract") || names.some((name) => SKIPS_METHOD.test(name))) continue;
    let params = m + 1;
    while (params < close && !(toks[params]?.t === "(" && toks[params]?.d === 1)) params++;
    if (params >= close) continue;
    const after = closeOf(toks, params) + 1;
    // A test method must return nothing: a block body does, and so does a declared Unit. An expression body may return a value,
    // and JUnit then skips the method without a word.
    if (toks[after]?.t === "{") return true;
    if (toks[after]?.t === ":" && toks[after + 1]?.t === "Unit" && (toks[after + 2]?.t === "=" || toks[after + 2]?.t === "{")) return true;
    const call = toks[after + 1];
    if (toks[after]?.t === "=" && call?.k === "id" && !call.q && unitCalls.has(call.t) && (toks[after + 2]?.t === "{" || toks[after + 2]?.t === "(")) {
      return true;
    }
  }
  return false;
}

/** The name a top-level `fun` at [at] declares, if it has one. */
function funName(toks: readonly Tok[], at: number): string | undefined {
  let j = at + 1;
  if (toks[j]?.t === "<") j = closeAngle(toks, j) + 1;
  let prev = toks[j - 1];
  let angle = 0;
  for (; j < toks.length; j++) {
    const t = toks[j];
    if (!t) break;
    if (t.d === TEMPLATE) continue;
    if (t.k === "sym") {
      if (t.t === "<") angle++;
      else if (t.t === ">") angle = Math.max(0, angle - 1);
      else if (angle === 0 && t.t === "(") {
        if (prev?.k === "id" && j - 1 !== at && !(prev.t === "fun" && !prev.q)) return prev.t;
        j = closeOf(toks, j);
        prev = toks[j];
        continue;
      } else if (angle === 0 && t.t !== "." && t.t !== "?") return undefined;
    } else if (t.k === "id" && !t.q && DECLARES.has(t.t)) return undefined;
    prev = t;
  }
  return undefined;
}

/** The name a top-level `val` or `var` at [at] declares, if it has one. */
function propName(toks: readonly Tok[], at: number): string | undefined {
  let j = at + 1;
  if (toks[j]?.t === "<") j = closeAngle(toks, j) + 1;
  let name: string | undefined;
  let angle = 0;
  for (; j < toks.length; j++) {
    const t = toks[j];
    if (!t || t.d === TEMPLATE) continue;
    if (t.d !== 0) break;
    if (t.k === "sym") {
      if (t.t === "<") angle++;
      else if (t.t === ">") angle = Math.max(0, angle - 1);
      else if (angle > 0) continue;
      else if (t.t === "(") j = closeOf(toks, j);
      else if (t.t !== "." && t.t !== "?") break;
      continue;
    }
    if (t.k !== "id") break;
    if (!t.q && (t.t === "by" || DECLARES.has(t.t) || MODIFIERS.has(t.t))) break;
    if (!t.q && (t.t === "get" || t.t === "set") && (toks[j + 1]?.t === "(" || toks[j + 1]?.t === "=")) break;
    if (angle === 0) name = t.t;
  }
  return name;
}

const facadeOf = (path: string): string | undefined => {
  const file = path.slice(path.lastIndexOf("/") + 1);
  if (!file.endsWith(".kt")) return undefined;
  const base = file.slice(0, -3).replace(/[^\p{L}\p{N}_]/gu, "_");
  return `${base.charAt(0).toUpperCase()}${base.slice(1)}Kt`;
};

/** The names a source the lexer could not follow may declare, read from its raw text: more than it declares, never fewer. */
function roughDeclarations(text: string): string[] {
  const names: string[] = [];
  for (const m of text.matchAll(/\b(?:class|interface|object|typealias|enum|record)\s+`?([\p{L}_][\p{L}\p{N}_]*)/gu)) if (m[1]) names.push(m[1]);
  for (const m of text.matchAll(/\bfun\b([^{=;]{0,300})/gu)) {
    const head = m[1] ?? "";
    for (let open = head.indexOf("("); open >= 0; open = head.indexOf("(", open + 1)) {
      const name = /([\p{L}_][\p{L}\p{N}_]*)`?\s*$/u.exec(head.slice(0, open))?.[1];
      if (name) {
        names.push(name);
        break;
      }
    }
  }
  for (const m of text.matchAll(/\b(?:val|var)\s+([^:=\n]{1,200})/gu)) {
    const ids = [...(m[1] ?? "").replace(/\bby\b.*$/u, "").matchAll(IDENTIFIER)].map((x) => x[0]);
    const last = ids.at(-1);
    if (last) names.push(last);
  }
  return names;
}

/** The facts of [text], the source at [path]. A `.java` path is read as Java. */
export function scanSource(text: string, path: string): SourceScan {
  const java = path.endsWith(".java");
  const { toks, strings, ok } = lex(text, java);
  const declared = new Set<string>();
  const sealed = new Set<string>();
  const wildcards = new Set<string>();
  const imports = new Set<string>();
  const values = new Set<string>();
  const types = new Set<string>();
  const classes: TestClassDecl[] = [];
  let packageName = "";
  let operators = false;
  let loadsByName = false;

  // kotlinx's runTest returns TestResult, which is Unit on the JVM. Imports come before classes, so they are read by now.
  const unitCalls = (): ReadonlySet<string> =>
    imports.has("kotlinx.coroutines.test.runTest") || wildcards.has("kotlinx.coroutines.test") ? new Set(["runTest"]) : new Set();

  let mods = new Set<string>();
  let anns: Annotation[] = [];
  const reset = (): void => {
    mods = new Set();
    anns = [];
  };

  const classDecl = (at: number): number => {
    const keyword = toks[at]?.t ?? "";
    const nameTok = toks[at + 1];
    if (nameTok?.k !== "id" || (!nameTok.q && DECLARES.has(nameTok.t))) return at + 1;
    const name = nameTok.t;
    if (!(keyword === "typealias" && mods.has("private"))) {
      declared.add(name);
      types.add(name);
    }
    let j = at + 2;
    if (keyword !== "class" && keyword !== "object" && keyword !== "enum" && keyword !== "record") return j;
    let generic = false;
    let params = false;
    if (toks[j]?.t === "<") {
      generic = true;
      j = closeAngle(toks, j) + 1;
    }
    if (toks[j]?.t === "@" || toks[j]?.t === "constructor") params = true;
    if (toks[j]?.t === "(") {
      const end = closeOf(toks, j);
      if (end > j + 1) params = true;
      j = end + 1;
    }
    let open = -1;
    for (let k = j; k < toks.length; k++) {
      const t = toks[k];
      if (!t || t.d === TEMPLATE || t.p !== 0) continue;
      if (t.d !== 0) break;
      if (t.k === "sym" && t.t === "{") {
        open = k;
        break;
      }
      if ((t.k === "id" && !t.q && DECLARES.has(t.t)) || (t.k === "sym" && (t.t === "@" || t.t === ";"))) break;
    }
    let close = toks.length;
    if (open >= 0) {
      for (let k = open + 1; k < toks.length; k++) {
        const t = toks[k];
        if (t && t.k === "sym" && t.t === "}" && t.d === 0) {
          close = k;
          break;
        }
      }
    }
    const law = anns.some((a) => a.name === "Tag" && a.args.includes("law"));
    const runnable =
      !java &&
      keyword === "class" &&
      !["abstract", "sealed", "private", "inner", "enum", "annotation", "data", "value", "expect"].some((m) => mods.has(m)) &&
      !generic &&
      !params &&
      anns.every((a) => SAFE_CLASS_ANNOTATIONS.has(a.name)) &&
      open >= 0 &&
      declaresTest(toks, open, close, unitCalls());
    classes.push({ name, law, runnable });
    return j;
  };

  for (let i = 0; i < toks.length; ) {
    const t = toks[i];
    if (!t) break;
    if (t.d !== 0 || t.p !== 0) {
      i++;
      continue;
    }
    if (t.k === "sym" && t.t === "@") {
      if (toks[i + 1]?.t === "interface") {
        i = classDecl(i + 1);
        reset();
        continue;
      }
      const a = annotation(toks, i);
      anns.push(a);
      i = Math.max(a.next, i + 1);
      continue;
    }
    if (t.k === "id" && !t.q) {
      const word = t.t;
      const prev = toks[i - 1];
      const qualified = prev?.k === "sym" && (prev.t === "::" || prev.t === ".");
      if (java && (word === "enum" || word === "record") && toks[i + 1]?.k === "id") {
        i = classDecl(i);
        reset();
        continue;
      }
      if (MODIFIERS.has(word) && !qualified) {
        mods.add(word);
        i++;
        continue;
      }
      if (word === "context" && toks[i + 1]?.t === "(") {
        i = closeOf(toks, i + 1) + 1;
        continue;
      }
      if ((word === "package" || word === "import") && !qualified) {
        let j = i + 1;
        if (toks[j]?.t === "static") j++;
        const parts: string[] = [];
        let wildcard = false;
        while (j < toks.length) {
          const x = toks[j];
          if (x?.k === "id") {
            parts.push(x.t);
            j++;
            if (toks[j]?.t === ".") {
              j++;
              continue;
            }
          } else if (x?.t === "*") {
            wildcard = true;
            j++;
          }
          break;
        }
        if (word === "package") packageName = parts.join(".");
        else if (wildcard) wildcards.add(parts.join("."));
        else if (parts.length > 0) imports.add(parts.join("."));
        i = j;
        reset();
        continue;
      }
      if (word === "fun" && toks[i + 1]?.t === "interface") {
        mods.add("fun");
        i++;
        continue;
      }
      if ((word === "class" || word === "interface" || word === "object" || word === "typealias") && !qualified) {
        i = classDecl(i);
        reset();
        continue;
      }
      if (word === "fun" && !qualified) {
        const name = funName(toks, i);
        if (name !== undefined && !mods.has("private")) {
          declared.add(name);
          values.add(name);
          if (mods.has("operator")) operators = true;
        }
        reset();
        i++;
        continue;
      }
      if ((word === "val" || word === "var") && !qualified) {
        const name = propName(toks, i);
        if (name !== undefined && !mods.has("private")) {
          declared.add(name);
          values.add(name);
        }
        reset();
        i++;
        continue;
      }
    }
    reset();
    i++;
  }

  const code = new Set<string>();
  for (let i = 0; i < toks.length; i++) {
    const t = toks[i];
    if (!t || t.k !== "id") continue;
    code.add(t.t);
    if (t.q) for (const m of t.t.matchAll(IDENTIFIER)) code.add(m[0]);
    if (DISCOVERY.has(t.t)) loadsByName = true;
    if ((t.t === "forName" || t.t === "loadClass") && toks[i + 1]?.t === "(") {
      const arg = toks[i + 2];
      const after = toks[i + 3];
      const literal = arg?.k === "str" && !arg.q && (after?.t === ")" || after?.t === ",");
      if (!literal) loadsByName = true;
    }
    if (t.t === "sealed" && !t.q && t.d !== TEMPLATE) {
      let j = i + 1;
      while (toks[j]?.k === "id" && !toks[j]?.q && MODIFIERS.has(toks[j]?.t ?? "")) j++;
      const keyword = toks[j]?.t;
      const name = toks[j + 1];
      if ((keyword === "class" || keyword === "interface") && name?.k === "id") sealed.add(name.t);
    }
  }
  const tokens = new Set(code);
  for (const piece of strings) for (const m of piece.matchAll(IDENTIFIER)) tokens.add(m[0]);

  if (!java) {
    const facade = facadeOf(path);
    if (facade) declared.add(facade);
    for (const m of text.matchAll(/@file\s*:\s*JvmName\s*\(\s*"([^"\\]+)"\s*\)/g)) if (m[1]) declared.add(m[1]);
  }
  if (!ok) {
    // A source the lexer could not follow: its references and declarations come from its raw text, comments included.
    for (const m of text.matchAll(IDENTIFIER)) {
      tokens.add(m[0]);
      code.add(m[0]);
    }
    for (const name of roughDeclarations(text)) declared.add(name);
  }

  return {
    parsed: ok,
    packageName,
    declared: [...declared],
    sealed: [...sealed],
    operators,
    wildcards: [...wildcards],
    imports: [...imports],
    values: [...values].filter((name) => !types.has(name)),
    tokens,
    code,
    strings,
    classes,
    loadsByName,
  };
}
