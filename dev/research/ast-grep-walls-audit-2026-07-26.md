# SPLICE AST-GREP WALLS — IMPROVEMENT AUDIT

Repo `/home/marcos/Documents/dev/projects/mythos/repo`, branch `fix/summary-turn-scoped-dedup`,
ast-grep 0.44.0, 2026-07-26. Every verdict below came from an actual `ast-grep` run against the
real tree and against synthetic probes; nothing here is reasoned-from-the-YAML.

---

## 0. BOTTOM LINE

**34 wall artifacts audited** (23 Kotlin rules in `.rules/kotlin-splice/`, 10 JS/TS/TSX/CSS rules in
`.rules/rules/`, 1 codemod in `checks/codemods/`). **32 confirmed improvements, 0 rejected, 0
unvalidated.** Two walls were **not examined at all** — `kt-no-global-scope` and the JS
`l2-single-mirror-definition` — and no claim is made about them (§11).

**The single highest-leverage change: fix `kt-no-system-getenv` and `kt-no-println` to match
callable references and qualified receivers — those two walls have been reporting green while 34
real call sites accumulated behind them (21 + 13), and landing the fix forces the one decision this
audit cannot make for you: whether `System::getenv` as a DI default and `System.err.println` as a
no-logger fallback are sanctioned idioms or debt.**

Everything else is 0-hit hardening: 29 of the 32 proposals add exactly zero new findings on the
current tree. That is the honest shape of this audit — the walls are, with three exceptions, not
*wrong about the codebase*; they are **narrower than their own message claims**, and a one-line
edit defeats most of them. Six walls can be bypassed today by an agent that never types the banned
string (§3); eight can be bypassed by splitting a literal in half (§4).

The baseline is green before any change: `npm run gate:rules` → tree scan clean, `33 passed; 0
failed` across 357 scanned files.

---

## 1. HOW TO RE-RUN (two traps that silently invalidate naive verification)

Both traps produce **zero output and exit 0**, which reads as "rule is fine". Every hit count in
this document was taken under the correct mode.

**Trap 1 — `files:` globs need a project root.** `ast-grep scan --rule <rule.yml> <path>` applies
the rule's `files:` glob against the scanned path *relative to the discovered project root*. If the
rule says `gateway/*/src/main/**/*.kt` and you scan a probe file in a scratch directory with no
`sgconfig.yml` above it, you get `[]` regardless of rule correctness. Two correct modes:

```bash
# (a) real-tree counts — run from the repo root, where sgconfig.yml lives
cd /home/marcos/Documents/dev/projects/mythos/repo
ast-grep scan --rule /path/to/proposed.yml --json gateway | python3 -c 'import json,sys;print(len(json.load(sys.stdin)))'

# (b) probe direction checks — build a scratch project with its own sgconfig.yml,
#     and place probes at paths that actually match the rule's files: glob
mkdir -p probetree/gateway/probe/src/main/kotlin/p
printf 'ruleDirs:\n  - ../rulesdir\n' > probetree/sgconfig.yml
cd probetree && ast-grep scan --json          # whole-project mode; NO --rule, NO explicit path
```

**Trap 2 — `ast-grep test` bypasses `files:` entirely.** The rule-test harness never applies path
scoping (this is documented in `.rules/README.md`). A rule-test proving the *pattern* is right
proves nothing about the `ignores:` exemptions. Path scoping is proven by the tree gate and the
orchestrator hook tests, not by `ast-grep test`.

A third gotcha worth writing down: `object X { fun run() {} }` **on one line** does not parse into
an `object_declaration` in tree-sitter-kotlin (it lands under an `ERROR` node). A probe written that
way reports a false negative that is the probe's fault, not the rule's. Write multi-line probes.

---

## 2. TIER 1 — walls that were green while the invariant was broken (LIVE hits)

These three are the only proposals that change what the gate says about the tree *today*. All three
turn CI red on landing. That is the point; it is also why each needs an explicit decision first.

### 2.1 `kt-no-system-getenv` — 0 → **21 hits across 11 files**

*Invariant:* env access goes through the layered `ConfigService`; scattered `System.getenv` reads
bypass provenance, coercion, and the `/mgmt/config` layer view.

*Defect:* the rule is `pattern: System.getenv($$$A)`, which matches only a **call**. Kotlin parses
`System::getenv` (bare, capitalized LHS) as a `callable_reference` with a `type_identifier` LHS, and
`java.lang.System::getenv` as a `navigation_expression` — three structurally different nodes, two of
which the wall has never been able to see. Every one of the 21 live hits is the callable-reference
form:

| file:line | shape |
|---|---|
| `app/cli/InstallCommand.kt` :28,35,87,138,158,169,178 | `System::getenv` (7×) |
| `app/Daemon.kt` :404,460,523,569 | `System::getenv` (4×) |
| `app/cli/AdminSupport.kt` :38,159 | `System::getenv` (2×) |
| `app/cli/{Doctor,Status,Login}Command.kt` :54,25,40 | `System::getenv` |
| `app/{TopologyLoader,CodexRefresh,GrokRefresh}.kt` :48,44,44 | `System::getenv` |
| `control/LaunchService.kt` :53 | `System::getenv` |
| `provider-openai/ApiKeyAuthProvider.kt` :21 | `System::getenv` |

**The decision this forces.** `gateway/core/src/main/kotlin/splice/core/config/ConfigService.kt:37`
carries the *identical* idiom (`private val envReader: (String) -> String? = System::getenv`) and is
correctly exempted by `ignores:`. So the idiom is deliberate — it is constructor-injection of an env
reader for testability, not a scattered read. The 21 sites are the same idiom outside the exempted
directory. Either (a) the DI-default is sanctioned and the rule needs a fourth `any` branch
*excluded* (e.g. only flag `System.getenv(...)` calls plus non-default callable refs), or (b) those
11 files thread `ConfigService` and the 21 sites go away. **Do not land the widened rule until that
call is made** — otherwise the first CI run after landing is red for a reason nobody has agreed on.

*Paste-ready (`rule:` block only; `files:`/`ignores:` unchanged):*

```yaml
rule:
  any:
    - pattern: System.getenv($$$A)
    - pattern: $RECEIVER.getenv($$$A)
    - pattern: $RECEIVER::getenv
    - kind: callable_reference
      regex: "^System::getenv$"
```

The fourth clause is structurally necessary, not redundant: `$RECEIVER::getenv` compiles to a
`navigation_expression` pattern and cannot reach the bare single-identifier `callable_reference`.

*Evidence:* existing rule on `gateway/` → 0 hits; proposed → 21 hits, 0 of them in the exempted
`core/config/**` (proven discriminating, not merely absent). Probe: a constructed
`gateway/core/src/main/kotlin/config/InConfig.kt` containing `System.getenv("HOME")` → 0 hits.

### 2.2 `kt-no-println` — 0 → **13 hits across 8 files**

*Invariant:* daemon output goes through the per-head logger; `println` is invisible to
`/mgmt/logs`. `gateway/app/**` is exempted (terminal tooling legitimately writes stdout).

*Defect:* same class. `println($$$A)` sees a bare call only. `System.err.println(...)` is a
`navigation_expression`; `System.err::println` is a *different* `navigation_expression` (the last
`navigation_suffix` uses `::`); bare `::println` is a `callable_reference`. 12 of the 13 live hits
are `System.err.println`, and the 13th is `System.err::println` used as a **DI default**
(`core/auth/RefreshOutcome.kt:46`, `log: (String) -> Unit = System.err::println`).

Sites: `core/config/ConfigService.kt:168,187`; `core/util/AsyncFileIo.kt:63`;
`core/auth/RefreshOutcome.kt:46`; `dialect-openai-responses/ResponsesProvider.kt:180`;
`provider-{grok:137,264,339 | kimi:202,257 | codex:121,322 | openai:74}`.

**The decision this forces.** These are all deliberate stderr fallbacks in modules with no injected
logger (grok/kimi/codex auth providers log read failures before nulling; `RefreshOutcome` takes the
stderr writer as its *default parameter*, so a caller can inject the head logger). Same fork as
above: sanction `System.err.println` as the no-logger fallback and carve it out explicitly, or
thread the log lambda into those 8 files. `gateway/app/` has ~15 further `println` sites and stays
correctly ignored either way — verified, the `ignores:` clause does real work here, it is not
vacuous.

```yaml
rule:
  any:
    - pattern: println($$$A)
    - pattern: $RECEIVER.println($$$A)
    - pattern: $RECEIVER::println
    - pattern: ::println
```

### 2.3 `kt-state-paths-single-source` — 0 → **1 hit** (`app/Daemon.kt:719`)

*Invariant:* the byte-identical external state paths (`~/.claude-codex/*`) resolve in exactly one
place, `StatePaths.kt`.

*Defect:* `kind: string_content, regex: \.claude-codex` is a **per-node** substring check. Splitting
the literal — `".claude-" + "codex"`, or `"~/.claude-$head"` when `head == "codex"` — leaves no
single `string_content` node containing the full substring. Widening the fragment to `\.claude-`
closes it.

The one new hit is real and disclosed: `Daemon.kt:719`
`val configDir = Paths.get(TopologyLoader.expandHome(head.claude.configDir ?: "~/.claude-$key"))`
— the per-head `CLAUDE_CONFIG_DIR` default, which is only textually `.claude-codex` when
`key == "codex"`. That is arguably a *separate* contract from state-path resolution. **Operator
call:** add an explicit `ignores:` entry for `Daemon.kt` (with the reason), or route it through
`StatePaths`. Do not narrow the regex back — that reopens the concatenation evasion.

Full file (header + `rule:` both change) in §A.1.

---

## 3. TIER 2 — demonstrated bypasses and false positives, 0 live hits

Each of these was *reproduced live* against the currently-shipped rule: the bypass code was written,
scanned with today's YAML, and came back clean. Zero live hits means landing them is free.

### 3.1 `kt-no-quality-suppress` — the anti-bypass wall is itself bypassable

Two shapes defeat it, both proven by CST dump:

- `@file:Suppress("LongMethod", "TooManyFunctions")` parses as a **`file_annotation`** node wrapping
  `constructor_invocation` **directly** — there is no intervening `annotation` node, so the rule's
  `inside: {kind: annotation}` chain structurally cannot reach it. One line at the top of a file
  silences every detekt structural rule in it.
- `@kotlin.Suppress(...)` parses to a `user_type` whose text is the whole dotted string
  `kotlin.Suppress`, which the anchored `^Suppress$` regex rejects.

```yaml
rule:
  kind: string_literal
  regex: "^\"(TooManyFunctions|LongMethod|LargeClass|CyclomaticComplexMethod|ComplexCondition|LongParameterList|ReturnCount|NestedBlockDepth|MagicNumber|MaxLineLength|TooGenericExceptionCaught|SwallowedException|StringLiteralDuplication|UnusedPrivateMember|UnusedPrivateProperty|UnusedParameter)\"$"
  inside:
    kind: value_argument
    stopBy: end
    inside:
      stopBy: end
      any:
        - kind: annotation
        - kind: file_annotation
      has:
        stopBy: end
        kind: user_type
        regex: "^(.*\\.)?Suppress$"
```

*Evidence:* 4-shape probe (`@file:Suppress` with 2 args, `@kotlin.Suppress` class-level, `@Suppress`
fun-level) → 4/4 flagged with the proposed rule, 1/4 with the existing one. Compliant probe
(`@Suppress("UNCHECKED_CAST")`, `@Suppress("UseDataClass")` — the real
`provider-spi/InflightGate.kt:37` case) → 0 hits. Real tree: 0 → 0. The `@file:Suppress` hits that a
raw grep finds all live under `gateway/build-logic/build/generated-sources/**` (Gradle DSL
accessors), which contain no `src/main` path segment and never enter `files:` scope.

### 3.2 `kt-no-runblocking-in-daemon` — the exemption path can re-export the ban

`Main.kt` and `cli/**` are exempted so process entry can block. Nothing stops the exempted path from
declaring `internal fun blockingBridge(x: Int) = runBlocking { work(x) }`, which daemon code then
calls with **zero textual trace** at the call site. Reproduced: both an `internal` and a
default-public bridge in `cli/` read clean under today's rule.

The fix is a **companion rule** (new file, or a second YAML document in the same file), not a change
to the existing one — the existing rule stays exactly as it is. Full file in §A.2. Shape: any
`function_declaration` inside the exempted paths whose body reaches `runBlocking` must be `private`,
unless it carries the `override` member modifier (the sanctioned `Command.run()` dispatch seam).

*Evidence:* probe tree with `cli/Bypass.kt` (internal), `cli/BypassPublic.kt` (default-public),
`Main.kt` (2× `private fun ... runBlocking`), `cli/Command.kt` (2× `override fun run()` with
runBlocking), `appy/.../DaemonProbe.kt` (bare top-level, non-exempt). Result: companion flags exactly
Bypass.kt + BypassPublic.kt; the original rule still flags DaemonProbe.kt; Main.kt and Command.kt
clean. Real tree: companion → 0 hits (the real `Main.kt` private helpers and `cli/Command.kt`
overrides are correctly left alone).

### 3.3 `kt-l2-single-mirror-definition` — the wall cannot see the declaration it guards

The rule is `pattern: fun mirrorInto($$$P)`. The real declaration is
`gateway/gateway/.../reasoning/Mirror.kt:22` → `public suspend fun mirrorInto(`. Kotlin's `modifiers`
node is a required sibling child; present in the target and absent from the pattern, it breaks
smart-strictness matching. **The old rule returns 0 matches against the exact production shape** —
proven directly. Any faithful second copy *must* be `suspend` (it calls the suspend
`WireSink.addTextBlock`), so the wall was blind to 100% of the bug shape it exists to prevent.

Replaced with a `kind` + `has` structural match, immune to modifiers, return-type annotations, and
expression bodies. Two adversarial probes confirm it doesn't over-match: a function that merely
*calls* `mirrorInto` in its body → 0 (ast-grep's `has:` without `stopBy` checks immediate children
only), and `fun process(mirrorInto: String)` → 0 (the parameter identifier lives under
`function_value_parameters`). Full file in §A.3.

### 3.4 `kt-catch-swallows-cancellation` — a comment satisfies the exemption

The exemption is `not: has: {regex: CancellationException, stopBy: end}` — a **free-text** regex over
the catch block's subtree. A catch body containing only
`// note: CancellationException handling deferred to caller` and a log string mentioning the class
name is exempt while swallowing cancellation. Replace the free-text regex with a structural pattern
so only a real parsed type-check counts:

```yaml
rule:
  kind: catch_block
  regex: ':\s*(kotlin\.)?(Exception|Throwable)\b'
  not:
    has:
      pattern: $_ is CancellationException
      stopBy: end
```

`$_` binds any identifier — verified against `catch (ex: Throwable) { if (ex is CancellationException) throw ex; … }`
→ 0 hits, so the fix is not `e`-specific. Real tree 0 → 0; rule-tests 4/4.

### 3.5 `kt-no-sealed-interface` — an annotation or a double space defeats it

`regex: '^(public |internal |private |protected )*sealed interface\b'` must match the **whole node
text** of the `class_declaration`. `@Serializable\nsealed interface Foo` starts with the annotation;
`public  sealed interface Baz` has two spaces. Reproduced: the current rule catches 2 of 4 probe
violations. Replaced with two relational `has` checks (CST-confirmed shape:
`class_declaration > modifiers > class_modifier('sealed')` plus the anonymous `interface` keyword as
a direct sibling child):

```yaml
rule:
  kind: class_declaration
  all:
    - has:
        stopBy: end
        kind: class_modifier
        regex: '^sealed$'
    - has:
        regex: '^interface$'
```

*Evidence:* 4 violations (annotated / plain / double-space / `internal`) → 4/4; 3 compliant
(`sealed class`, `fun interface`, plain `interface`) → 0. Real tree 0 → 0.

### 3.6 `kt-no-object-command` — **the only false positive in the set**

`kind: object_declaration, regex: 'object\s+\w*Command\b'` runs the regex over the whole
declaration's text, so a docstring or a type reference *inside the body* triggers it. Reproduced:
`object AdminSupport { val usage = """Usage: object FooCommand <args>"""; val x: FooCommand = TODO() }`
is flagged by today's rule. A wall that fires on compliant code teaches agents that wall output is
noise. Scope the regex to the object's own identifier:

```yaml
rule:
  kind: object_declaration
  has:
    kind: type_identifier
    regex: '^\w*Command$'
```

*Evidence:* `object InstallCommand {…}` → flagged; the AdminSupport probe → clean (existing rule
flags it). Real tree 0 → 0.

### 3.7 `kt-no-silent-result-collapse` — split the chain across two lines and it vanishes

The wall matches a `.getOrNull()`/`.getOrDefault()` node whose **own subtree** contains
`runCatching`. Assigning the Result to a val first breaks the subtree relationship:

```kotlin
val result = runCatching { read() }   // no collapse call in this node
val token  = result.getOrNull()       // no runCatching in this node
```

Add a second `any` branch on the `property_declaration`, tied to a preceding sibling
`val $RECV = …` by **metavariable identity** on `$RECV` (that identity is what stops it firing on
unrelated vals):

```yaml
rule:
  any:
    # A. direct chain — the original wall, unchanged
    - all:
        - any:
            - pattern: $RECV.getOrNull()
            - pattern: $RECV.getOrDefault($$$ARGS)
        - has:
            stopBy: end
            regex: "\\brunCatching(Cancellable)?\\b"
        - not:
            has:
              stopBy: end
              regex: "\\bonFailure\\b"
    # B. split form: `val r = runCatching { … }` then `r.getOrNull()` on a later line.
    - all:
        - kind: property_declaration
        - has:
            stopBy: end
            any:
              - pattern: $RECV.getOrNull()
              - pattern: $RECV.getOrDefault($$$ARGS)
        - not:
            has:
              stopBy: end
              regex: "\\bonFailure\\b"
        - follows:
            stopBy: end
            all:
              - pattern: val $RECV = $$$INIT
              - has:
                  stopBy: end
                  regex: "\\brunCatching(Cancellable)?\\b"
              - not:
                  has:
                    stopBy: end
                    regex: "\\bonFailure\\b"
```

*Evidence:* split violation → flagged; split-with-`.onFailure` → clean; unrelated-name control
(`val other = compute(); val list = mapOf(…); val w = list.getOrDefault(…); val v = other.getOrNull()`)
→ clean. Rule-tests 7/7 (4 valid + 3 invalid). Real tree 0 → 0.

---

## 4. TIER 3 — THEME: split-literal evasion (7 walls, one fix shape)

**Analysis once, applied seven times.** A `regex:` on a `string_content` / `string_fragment` node is
a per-node substring test. `"end_" + "turn"` produces the same runtime string with no node containing
`end_turn`. Every literal-fingerprint wall in the repo has this hole. The fix shape is identical
everywhere: add an `additive_expression` (Kotlin) / `binary_expression` (JS) branch whose two
operands are string literals matching **morpheme-anchored** regexes.

Anchoring is load-bearing. `^(end_?|_?turn)$` matches only a whole operand that is exactly one
morpheme, so a log line like `` `stream ended: ${reason}` `` cannot trip it.

| wall | banned literal | morpheme regex | file |
|---|---|---|---|
| `kt-l3-end-turn-literal` | `end_turn` | `^(end_?\|_?turn)$` | §A.4 |
| `kt-l3-sole-wire-terminals` | `message_stop`, `message_delta` | `^(message_?\|_?stop\|_?delta)$` | §A.5 |
| `l3-end-turn-via-emitter` (JS) | `end_turn` | `^(end_?\|_?turn)$` | §A.6 |
| `l3-sole-message-stop-emitter` (JS) | `message_stop` | `^(message_?\|_?stop)$` | §A.7 |
| `no-claudex-magic-props` (JS) | `__claudex*` | operand-pair inside `subscript_expression` / `computed_property_name` | §A.8 |
| `launcher-no-pkill` (JS) | `pkill` | operand-pair inside `arguments` of an exec-family call | §A.9 |
| `kt-form-encoding-single-source` | `"%%%02X"` | `$PCT + $HEX` with `PCT ~ ^"%+"$`, `HEX` a call containing `"%02X"` | below |

Two of these carry a scope change worth reading before landing:

- **`l3-end-turn-via-emitter`** collapses its two hardcoded `ignores:` into
  `server/src/*/translate-response.mjs`. Verified a no-op today:
  `find server/src -iname translate-response.mjs` returns exactly `codex/` and `grok/`, and the glob
  resolves to the same two paths.
- **`launcher-no-pkill`** widens `files:` from `server/launcher/**` to `server/**/*.mjs` (shared
  lifecycle code in `heads.mjs` is consumed by the control server too). The concatenation branch is
  deliberately scoped to arguments of `exec|execSync|execFileSync|spawn|spawnSync` so it does not
  fire on the real HTML-string concatenation in `server/src/auth/codex-login.mjs` — checked directly
  against that file, 0 hits.

`kt-form-encoding-single-source` (the odd one out — the fingerprint is a format string, not a wire
token):

```yaml
rule:
  any:
    - kind: string_literal
      regex: '%%%02X'
    - pattern: $PCT + $HEX
constraints:
  PCT:
    kind: string_literal
    regex: '^"%+"$'
  HEX:
    kind: call_expression
    has:
      stopBy: end
      kind: string_literal
      regex: '^"%02X"$'
```

`"%" + "%02X".format(c)` reproduces `FormEncoding.kt:16`'s output byte-for-byte without ever
containing the `%%%02X` literal. The `PCT` constraint (`^"%+"$`) is what keeps
`"color-" + "%02X".format(v)` clean — verified.

**Evidence, all seven:** existing 0 hits → proposed 0 hits on the real tree (`gateway/` 357 files,
`server/` 42 `.mjs` files); each direction-checked against a split-literal violation probe and a
compliant probe; all pre-existing rule-tests pass unmodified. `PassthroughStreamTranslator.kt:127,129`
`// ast-grep-ignore: kt-l3-sole-wire-terminals` suppressions remain honored.

---

## 5. TIER 4 — THEME: alternate call shapes (8 walls, one fix shape)

**Analysis once, applied eight times.** `pattern:` is structural, not textual. A pattern for a bare
call matches only a `call_expression` with a bare-identifier callee. Package-qualified names,
optional chaining, bracket access, callable references and parenthesized-arg forms are all *different
node kinds* and slip through. The fix shape is an `any:` enumerating the shapes.

| wall | shapes added | note |
|---|---|---|
| `kt-no-runcatching-in-coroutine` | `runCatching($$$ARGS)`, `kotlin.runCatching { … }`, `kotlin.runCatching($$$ARGS)` | `runCatchingCancellable` correctly unaffected (exact-identifier matching, no prefix collision) |
| `kt-no-request-body-gzip` | `$PKG.GZIPOutputStream($$$ARGS)` | `GZIPInputStream` and `import java.util.zip.GZIPOutputStream` both stay clean |
| `kt-jsonl-sink-single-source` | `Files.write($$$)` branch with the same `StandardOpenOption.APPEND` guard | `JsonlSink.kt:29` actually uses the byte-array `Files.write` overload — the wall's own subject file has a different fingerprint than the wall |
| `kt-json-scalars-single-source` | 4 cast forms (`as`/`as?` × `content`/`contentOrNull`) + 2 smart-cast composites | see below |
| `webui-fetch-only-in-api` | `$W?.fetch`, `$W['fetch']`, `$W?.['fetch']`, **`$W["fetch"]`, `$W?.["fetch"]`** | the last two are an audit addition — see §7 |
| `webui-fetch-only-in-api-tsx` | same | same |
| `kt-embedded-server-loopback` | exemption sub-pattern rewritten | see below |
| `loopback-bind-only` (JS) | exemption sub-pattern rewritten | see below |

**`kt-json-scalars-single-source`** — the rule's own subject file (`core/util/JsonScalars.kt`) names
`(x as? JsonPrimitive)?.content` in its header as a second common leak shape, and the wall never
matched it. The smart-cast form (`if (v is JsonPrimitive) { … v.content … }`) needs metavariable
identity to avoid firing on every `.content` in the file:

```yaml
rule:
  any:
    - pattern: $X?.jsonPrimitive?.content
    - pattern: $X.jsonPrimitive.content
    - pattern: $X?.jsonPrimitive?.contentOrNull
    - pattern: $X.jsonPrimitive.contentOrNull
    - pattern: ($X as? JsonPrimitive)?.content
    - pattern: ($X as JsonPrimitive).content
    - pattern: ($X as? JsonPrimitive)?.contentOrNull
    - pattern: ($X as JsonPrimitive).contentOrNull
    - all:
        - pattern: $V.content
        - inside:
            stopBy: end
            has:
              stopBy: end
              pattern: $V is JsonPrimitive
    - all:
        - pattern: $V.contentOrNull
        - inside:
            stopBy: end
            has:
              stopBy: end
              pattern: $V is JsonPrimitive
```

Verified: 3/3 on cast + smart-cast violations; clean on a compliant probe carrying an unrelated
`if (x is String)` smart cast **and** a `data class Foo(val content: String)` field access.

**The two loopback walls share a distinct sub-defect: the exemption sub-pattern does not parse to the
node it must match.** In `kt-embedded-server-loopback` the exemption was an unscoped subtree `has`;
in `loopback-bind-only` the submitted proposal used `pattern: host: '127.0.0.1'`, which parses as a
**`labeled_statement`**, never the `pair` node that actually appears inside an object literal — the
oracle caught that the proposal's own new compliant sample (`server.listen({ port, host: '127.0.0.1' })`)
was still flagged. Both are fixed with `context` + `selector`:

```yaml
# kt-embedded-server-loopback (exemption branch)
- pattern: host = "127.0.0.1"                       # connector-block assignment
- pattern:
    context: "f(host = \"127.0.0.1\")"
    selector: value_argument                        # named-argument call form

# loopback-bind-only (exemption branch, both quote styles)
- pattern:
    context: "({host: '127.0.0.1'})"
    selector: pair
- pattern:
    context: '({host: "127.0.0.1"})'
    selector: pair
```

Full files in §A.10 / §A.11.

**Evidence, all eight:** existing 0 → proposed 0 on the real tree; each direction-checked. Both real
`embeddedServer` call sites (`HeadServer.kt` connector-block, `ControlServer.kt` named-arg) and all 8
real `.listen()` call sites read clean under the proposed rules. All 4 pre-existing
`kt-embedded-server-loopback` rule-test cases and both `loopback-bind-only` invalid cases still
behave.

---

## 6. TIER 5 — THEME: one spelling enumerated, the rest open (6 walls)

Each of these bans a concept but enumerates only the first spelling anyone happened to write.

**`kt-secure-file-single-source`** — bans `PosixFilePermissions.fromString("rw-------")`; the
`EnumSet.of(OWNER_READ, OWNER_WRITE)` spelling of 0600 was wide open. Adds 4 branches (both argument
orders × `EnumSet.of`/`setOf`). Full file in §A.12. *Disclosed residual gap:* a fully-qualified
`java.util.EnumSet.of(PosixFilePermission.OWNER_READ, …)` still evades — verified, not overstated.

**`webui-css-tokens-only`** — the unit regex was `^(px|rem)$` (case-sensitive, no `em`) and the
property regex allowed at most one dash segment, so CSS logical properties were invisible. Four live
bypasses reproduced (`padding-inline-start: 8px`, `margin-block-end: 1.5rem`, `font-size: 1.5em`,
`gap: 10PX`) — today's rule flags 0 of 4.

```yaml
    - has:
        kind: property_name
        regex: '^(font-size|gap|row-gap|column-gap|top|right|bottom|left|(inset|margin|padding)(-[a-z]+)*)$'
    - has:
        stopBy: end
        kind: unit
        regex: '(?i)^(px|rem|em)$'
```

**`webui-no-emdash-ui-text`** — matched only the literal `—` character in `jsx_text` /
`string_fragment`. CST-confirmed: `&mdash;` parses to its own `html_character_reference` node, a
*sibling* of `jsx_text`, invisible to the rule. All three encodings (`&mdash;`, `&#8212;`,
`&#x2014;`) bypass today.

```yaml
    - kind: html_character_reference
      regex: "(?i)^&#?(mdash|8212|x2014);$"
```

Verified against `&amp;` / `&nbsp;` as a false-positive control: 3 of 5 entities flagged, correctly.

**`kt-no-stream-options-request`** — `regex: '^"include_usage"$'` matches the **full literal text
including delimiters**, so a Kotlin raw string `"""include_usage"""` walks through. Match the inner
content node instead:

```yaml
rule:
  kind: string_literal
  has:
    kind: string_content
    regex: '^include_usage$'
```

Verified the `ignores: gateway/dialect-openai-chat/**` exemption still holds against the 2 real files
there that legitimately carry the string.

**`kt-force-strict-false-is-literal`** — the guard match was `kind: when_condition, regex:
'^forceStrictFalse$'` against the condition's **full text**, so the redundant-but-valid spelling
`forceStrictFalse == true ->` is not recognised as the forceStrictFalse branch and the wall silently
stops guarding it. Structural instead:

```yaml
    has:
      kind: when_condition
      has:
        kind: simple_identifier
        regex: '^forceStrictFalse$'
        stopBy: end
```

All 5 existing valid and 4 existing invalid fixtures behave unchanged; the `FIELD`/`VAL` constraints
still scope the match to `FIELD_STRICT` only (the grouped-branch case that motivated them in review
#49 still passes).

**`kt-tool-partition-no-transcript`** — matched `$X.messages` (qualified) and `warmToolNames(…)`. A
partition function written as an extension on `AnthropicRequest` reads the transcript via the
implicit receiver as a bare `messages` and slips through. Adds a third branch:

```yaml
    - kind: simple_identifier
      regex: '^messages$'
```

*Known cosmetic cost:* on a qualified `body.messages` occurrence both the `$X.messages` branch and
the new bare-identifier branch match overlapping nodes, so the reported hit count on a future
violation is inflated by 1 per qualified occurrence (the existing invalid fixture goes 2 → 3
reported for 2 sites). Pass/fail direction is unaffected on every fixture, and the real tree is 0 → 0.

**Evidence, all six:** existing 0 → proposed 0 on the real tree. All pre-existing rule-tests behave
unchanged.

---

## 7. TIER 6 — the codemod

`checks/codemods/runCatching-to-cancellable.yml` gets **one** new branch:

```yaml
rule:
  any:
    - pattern: runCatching { $$$BODY }
    - pattern: kotlin.runCatching { $$$BODY }
fix: 'runCatchingCancellable { $$$BODY }'
```

**Deliberately NOT added: a parenthesized-call branch.** `runCatching(::fn)` never binds `$$$BODY`,
so a `$$$BODY`-templated fix silently produces `runCatchingCancellable { }` — a broken empty body
with no error (ast-grep substitutes unmatched metavariables with the empty string). The
parenthesized form stays **detect-only**, caught by `kt-no-runcatching-in-coroutine` (§5). Verified:
the proposed codemod returns `[]` on `val x = runCatching(::risky)`.

*Evidence:* existing codemod on the tree → 42 hits; proposed → 42 hits (0 delta). An isolated
single-pattern probe for `kotlin.runCatching { … }` → 0 hits, i.e. the new branch is a dormant
enumeration fix with no live effect. Rewrite verified non-empty on
`val b = kotlin.runCatching { risky2() }` → `runCatchingCancellable { risky2() }`.

---

## 8. REJECTED / DISPROVEN — do not re-propose these

**Nothing was rejected outright.** All 32 proposals survived. What the oracle *did* disprove are
three claims made inside otherwise-correct proposals. They are recorded here so the next session
does not re-derive them:

1. **`pattern: host: '127.0.0.1'` as an object-literal exemption (JS) — WRONG, does not work.**
   Standalone, it parses as a `labeled_statement` (label `host`, string statement), not the `pair`
   node that appears inside `{ host: '127.0.0.1' }`. The submitted `loopback-bind-only` YAML using
   this form flagged its own compliant sample. Use `context: "({host: '127.0.0.1'})"` +
   `selector: pair`. Same trap in Kotlin for named arguments — `context: "f(host = \"127.0.0.1\")"` +
   `selector: value_argument`.

2. **"The interpolation exclusion prevents 34 false positives" (both `kt-l3-*` walls) — FALSE.**
   A no-exclusion variant of the final anchored rule was built and run against the full gateway tree:
   **0 hits, not 34.** The anchored `^(end_?|_?turn)$` already prevents any match against a string
   carrying extra text, so the `not: has: {kind: interpolated_expression}` clause is inert defensive
   redundancy in the shipped shape. It is harmless and was kept, but nobody should cite that figure
   as justification. The 34/16 numbers describe abandoned earlier drafts (unanchored regex, or a
   blanket concatenation ban).

3. **A parenthesized-call branch in the codemod — actively harmful.** See §7. This is the one place
   in the audit where the *smaller* change is correct, and the reasoning is non-obvious enough that
   it will be re-proposed if not written down.

Also worth pinning: **`files:` globs on a rule are not applied by
`ast-grep scan --rule <file> <explicit-path>` unless a project root is discoverable**, and
`ast-grep test` never applies them at all. Multiple hours of the validation pass were spent
rediscovering this; §1 is the antidote.

---

## 9. UNVALIDATED — NOT READY TO LAND

**No proposal in this document is unvalidated.** All 32 were run against the real tree and against
probes.

Two items *were* discovered while writing this document, are **not** part of the validated set, and
**must not be landed without their own red-green proof**:

- **`webui-fetch-only-in-api` / `-tsx`: the double-quoted bracket form.** The validated proposal
  covers `$W['fetch'](…)` and `$W?.['fetch'](…)` (single quotes only). A probe written with
  `window["fetch"]("/z")` slipped through. Adding `$W["fetch"]($$$A)` and `$W?.["fetch"]($$$A)` closes
  it — direction-checked 4/4 in a probe project, real tree still 0 hits across 45 `webui/src` files —
  but this pair has **no rule-test yet**. Write one before landing.
- **`kt-secure-file-single-source`: the fully-qualified `java.util.EnumSet.of(...)` form** still
  evades (disclosed and verified in the validation pass, not fixed). Closing it needs a
  `$PKG.EnumSet.of(...)` branch and its own proof.

---

## 10. LANDING PROCEDURE

`.rules/`, `checks/codemods/`, `.claude/hooks/`, `.claude/settings.json` and `sgconfig.yml` are
**grant-gated wall infrastructure**. Writes require the operator to set `SPLICE_WALLS_OK=1`, loudly.
A blocked write means fix the code, not the wall.

Per change, in order — no step is skippable, and none may be collapsed:

1. **Decide the policy first, for the three Tier-1 walls only** (§2). Landing a widened rule with an
   unresolved carve-out question is how a gate goes red for a reason nobody agreed to. Record the
   decision in the rule's header comment, not in a commit message.
2. **Write the failing rule-test first** — `.rules/rule-tests/<id>-test.yml`, one `invalid:` sample
   carrying the exact bypass shape this change closes, plus the `valid:` samples that must stay
   clean. Run `ast-grep test --skip-snapshot-tests -f '^<id>$'` and **watch it fail** on unmodified
   HEAD. A fix without a test that is red before the fix is unvalidated.
3. **Edit the rule** under `SPLICE_WALLS_OK=1`. Extend the header comment: what shape was slipping
   through, and why the new clause is structurally necessary (the `$RECEIVER::x` vs bare `::x` and
   `annotation` vs `file_annotation` distinctions are not obvious from the YAML alone).
4. **`npm run gate:rules`** — `ast-grep scan` (whole tree) + `ast-grep test --skip-snapshot-tests`.
   Green means the new test passes **and** no other rule regressed. Baseline for comparison:
   357 files scanned, `33 passed; 0 failed`.
5. **`npm run test:hooks`** — `python3 .claude/hooks/tests/test_orchestrator.py`. This is the half
   that proves `files:`/`ignores:` path scoping, which `ast-grep test` cannot (§1, trap 2). For any
   change that touches an exemption path (`kt-state-paths`, `l3-end-turn-via-emitter`,
   `launcher-no-pkill`, the `kt-no-runblocking` companion), add an orchestrator case.
6. **Re-run the real-tree count** and confirm it matches the delta claimed in this document
   (Appendix B). A count that moved when the table says 0 → 0 means the reconstruction diverged from
   what was validated — stop and re-read the diff, do not adjust the table.

Suggested batching: §3 and §4/§5/§6 are all 0 → 0 and can land as themed batches (one commit per
theme, one rule-test per rule). §2 lands last, one rule per commit, each with its policy decision
written down.

---

## 11. WHAT THIS AUDIT DID NOT COVER

- **Two walls were not examined at all:** `kt-no-global-scope` and the JavaScript
  `l2-single-mirror-definition`. No claim is made about them either way. `kt-no-global-scope` is a
  bare `pattern: GlobalScope`-family rule and is a plausible candidate for the §5 call-shape theme;
  the JS L2 rule is the sibling of `kt-l2-single-mirror-definition`, which turned out to be blind to
  its own production shape (§3.3) — that sibling should be checked next.
- **The non-ast-grep half of the wall system is out of scope entirely:** detekt
  (`gateway/detekt.yml`), konsist (`.rules/konsist/`), the `:fir-checks` compiler plugin and
  `@MustConsume`, `-Xreturn-value-checker`, `checks/gate.sh`, `checks/e2e/heads-e2e.sh`, and the
  test-plane invariants (L4, the L2 both-paths-call assertion). Several walls here have a
  same-checker-twice partner in those systems; whether the partners have the same blind spots was not
  checked.
- **`.claude/hooks/orchestrator.py` was not audited.** The write-time half is assumed to bind
  `files:`/`ignores:` identically to the gate, per `.rules/README.md`. That claim was not tested.
- **No wall was assessed for whether it protects the right invariant.** This audit asks only
  "does the rule catch what its own message says it catches". Whether the message is the right policy
  is an operator question; the two loudest candidates surfaced anyway (§2.1, §2.2), where the code's
  behaviour and the rule's text disagree and the code may be right.
- **No new walls are proposed.** Uncovered invariants — shapes with no rule at all — were not
  searched for.
- **`ignores:` entries were spot-checked, not systematically audited.** Where a change touched an
  exemption it was verified to still discriminate (proven, not merely absent) — `kt-no-system-getenv`
  vs `ConfigService.kt:37`, `kt-no-println` vs `gateway/app/**`, `kt-jsonl-sink` vs `JsonlSink.kt`,
  `kt-no-stream-options` vs the chat dialect. Untouched exemptions on untouched rules were not.
- **Performance was not measured.** Several proposals add `stopBy: end` traversals and
  regex-without-kind clauses. On 357 files this is not a concern; it was not benchmarked.
- **Snapshot tests were skipped** (`--skip-snapshot-tests` throughout), consistent with the repo's
  own gate. Message/label formatting changes are therefore unverified; several proposals change the
  `message:` text and will need snapshot regeneration if snapshots are ever turned on.

---

## APPENDIX A — full rule files (where more than the `rule:` block changes)

Everything not listed here changes only the `rule:` block; paste it in place and extend the header
comment per §10 step 3.

### A.1 `kt-state-paths-single-source`

```yaml
# External contract protection — the byte-identical state paths (~/.claude-codex/*)
# resolve in ONE place (StatePaths.kt); a second literal is a drift seed.
# Widened from `\.claude-codex` to `\.claude-`: the narrower regex is a per-node substring check, so
# it is trivially evaded by splitting the literal across concatenation or a string template
# (e.g. ".claude-" + "codex", or "~/.claude-$head" when head=="codex") — no single string_content
# node then contains the full substring. Verified against the whole gateway/*/src/main tree: the
# wider fragment adds exactly one new hit (Daemon.kt's "~/.claude-$key" CLAUDE_CONFIG_DIR default)
# and zero false positives elsewhere. If that hit is a deliberately separate per-head config-dir
# contract rather than state-path drift, add it to ignores: explicitly instead of narrowing back.
id: kt-state-paths-single-source
language: kotlin
severity: error
message: "State paths resolve only in StatePaths.kt — no `.claude-` state-dir literals, concatenated or templated, elsewhere."
files:
  - gateway/*/src/main/**/*.kt
ignores:
  - gateway/core/src/main/kotlin/**/config/StatePaths.kt
rule:
  kind: string_content
  regex: \.claude-
```

### A.2 `kt-no-runblocking-exported-bridge` (NEW companion — the existing rule is untouched)

```yaml
# COMPANION to kt-no-runblocking-in-daemon: the exempted entry paths (Main.kt, cli/**) may CALL
# runBlocking, but they must not EXPORT it. A non-private `fun blockingBridge(...) = runBlocking {…}`
# declared in cli/ is callable from daemon code and defeats the wall with zero textual trace at the
# call site. Sanctioned shape: private helpers, plus `override fun run()` (the Command dispatch seam).
id: kt-no-runblocking-exported-bridge
language: kotlin
severity: error
message: "A function whose body reaches runBlocking inside the entry-path exemption must be private (or an `override` Command.run) — a non-private bridge re-exports runBlocking into daemon code."
note: >
  Mark the helper `private`, or keep it suspend and let the caller stay suspend. The exemption for
  Main.kt/cli exists for PROCESS ENTRY, not for a reusable blocking API.
files:
  - gateway/app/src/main/kotlin/**/Main.kt
  - gateway/app/src/main/kotlin/**/cli/**
rule:
  kind: function_declaration
  all:
    - has:
        stopBy: end
        pattern: runBlocking
    - not:
        has:
          kind: modifiers
          has:
            kind: visibility_modifier
            regex: '^private$'
    - not:
        has:
          kind: modifiers
          has:
            kind: member_modifier
            regex: '^override$'
```

### A.3 `kt-l2-single-mirror-definition`

```yaml
# Invariant L2 (Kotlin) — exactly ONE mirrorInto lives in reasoning/Mirror.kt.
# v29 shipped two drifting copies; this wall makes a second definition unwritable.
# Rewritten as a kind+has structural match (not a text pattern): the real mirrorInto is declared
# `public suspend fun mirrorInto(...)`, and any faithful reimplementation MUST be `suspend` (it calls
# WireSink.addTextBlock, itself suspend) — a bare pattern match on `fun mirrorInto($$$P)` never sees a
# modifier-bearing declaration at all (Kotlin's `modifiers` node is a required sibling child that
# breaks smart-strictness pattern matching when present in the target but absent from the pattern),
# so the old rule could never actually catch the shape of the bug it exists to prevent.
id: kt-l2-single-mirror-definition
language: kotlin
severity: error
message: "L2: mirrorInto is defined once, in gateway/gateway/src/main/kotlin/**/reasoning/Mirror.kt."
files:
  - gateway/**/*.kt
ignores:
  - gateway/gateway/src/main/kotlin/**/reasoning/Mirror.kt
rule:
  kind: function_declaration
  has:
    kind: simple_identifier
    regex: ^mirrorInto$
```

### A.4 `kt-l3-end-turn-literal` (`rule:` block)

```yaml
rule:
  any:
    - kind: string_content
      regex: \bend_turn\b
    - all:
        - kind: additive_expression
        - has:
            kind: string_literal
            nthChild: 1
            has:
              kind: string_content
              regex: "^(end_?|_?turn)$"
        - has:
            kind: string_literal
            nthChild: 2
            has:
              kind: string_content
              regex: "^(end_?|_?turn)$"
        - not:
            has:
              stopBy: end
              kind: interpolated_expression
```

### A.5 `kt-l3-sole-wire-terminals` (`rule:` block)

```yaml
rule:
  any:
    - kind: string_content
      regex: \b(message_stop|message_delta)\b
    - all:
        - kind: additive_expression
        - has:
            kind: string_literal
            nthChild: 1
            has:
              kind: string_content
              regex: "^(message_?|_?stop|_?delta)$"
        - has:
            kind: string_literal
            nthChild: 2
            has:
              kind: string_content
              regex: "^(message_?|_?stop|_?delta)$"
        - not:
            has:
              stopBy: end
              kind: interpolated_expression
```

### A.6 `l3-end-turn-via-emitter` (JS — `ignores:` collapse + `rule:`)

```yaml
files:
  - server/src/**/*.mjs
ignores:
  - server/src/anthropic/sse.mjs
  - server/src/*/translate-response.mjs
rule:
  any:
    - kind: string_fragment
      regex: \bend_turn\b
    - all:
        - kind: binary_expression
        - has:
            field: left
            kind: string
            has:
              kind: string_fragment
              regex: "^(end_?|_?turn)$"
        - has:
            field: right
            kind: string
            has:
              kind: string_fragment
              regex: "^(end_?|_?turn)$"
```

### A.7 `l3-sole-message-stop-emitter` (JS — `rule:` block)

```yaml
rule:
  any:
    - kind: string_fragment
      regex: \bmessage_stop\b
    - all:
        - kind: binary_expression
        - has:
            field: left
            kind: string
            has:
              kind: string_fragment
              regex: "^(message_?|_?stop)$"
        - has:
            field: right
            kind: string
            has:
              kind: string_fragment
              regex: "^(message_?|_?stop)$"
```

### A.8 `no-claudex-magic-props` (JS — appended 5th branch)

```yaml
    - kind: binary_expression
      all:
        - has:
            field: operator
            regex: ^\+$
        - has:
            field: left
            any:
              - kind: string
              - kind: template_string
        - has:
            field: right
            any:
              - kind: string
              - kind: template_string
        - inside:
            stopBy: end
            any:
              - kind: subscript_expression
              - kind: computed_property_name
```

### A.9 `launcher-no-pkill` (JS — `files:` widen + appended branch)

```yaml
files:
  - server/**/*.mjs          # widened from server/launcher/** — heads.mjs is shared lifecycle code
rule:
  any:
    - kind: string_fragment
      regex: pkill
    - kind: binary_expression
      all:
        - has:
            field: operator
            regex: ^\+$
        - has:
            field: left
            any:
              - kind: string
              - kind: template_string
        - has:
            field: right
            any:
              - kind: string
              - kind: template_string
        - inside:
            stopBy: end
            kind: arguments
            inside:
              stopBy: neighbor
              kind: call_expression
              has:
                field: function
                regex: ^(execSync|exec|execFileSync|spawn|spawnSync)$
```

### A.10 `kt-embedded-server-loopback` (`rule:` block)

```yaml
rule:
  pattern: embeddedServer($$$ARGS)
  not:
    has:
      stopBy: end
      any:
        - pattern: host = "127.0.0.1"
        - pattern:
            context: "f(host = \"127.0.0.1\")"
            selector: value_argument
```

### A.11 `loopback-bind-only` (JS — `rule:` block; `constraints:`/`labels:` unchanged)

```yaml
rule:
  any:
    - all:
        - pattern: $S.listen($P)
        - not:
            has:
              stopBy: end
              any:
                - pattern:
                    context: "({host: '127.0.0.1'})"
                    selector: pair
                - pattern:
                    context: '({host: "127.0.0.1"})'
                    selector: pair
    - pattern: $S.listen($P, $HOST)
    - pattern: $S.listen($P, $HOST, $$$REST)
```

### A.12 `kt-secure-file-single-source` (`rule:` block + message)

```yaml
message: "Use SecureFile.writeAtomic0600 (core/util) — the 0600 credential-write primitive lives in one place; re-deriving it — via PosixFilePermissions.fromString(\"rw-------\") or an equivalent OWNER_READ+OWNER_WRITE permission set — is how kimi drifted into a world-readable window."
rule:
  any:
    - pattern: PosixFilePermissions.fromString("rw-------")
    - pattern: EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    - pattern: EnumSet.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ)
    - pattern: setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    - pattern: setOf(PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ)
```

---

## APPENDIX B — real-tree hit counts (existing → proposed)

Scan root per language: `gateway/` (Kotlin, 357 files in project scope), `server/` (42 `.mjs`),
`webui/` (45 `.ts`/`.tsx`/`.css`), `.` (codemod). Run from the repo root per §1 mode (a).

| rule | existing | proposed | Δ |
|---|---:|---:|---:|
| `kt-no-system-getenv` | 0 | **21** | +21 |
| `kt-no-println` | 0 | **13** | +13 |
| `kt-state-paths-single-source` | 0 | **1** | +1 |
| `codemod-runcatching-to-cancellable` | 42 | 42 | 0 |
| `kt-catch-swallows-cancellation` | 0 | 0 | 0 |
| `kt-embedded-server-loopback` | 0 | 0 | 0 |
| `kt-force-strict-false-is-literal` | 0 | 0 | 0 |
| `kt-form-encoding-single-source` | 0 | 0 | 0 |
| `kt-json-scalars-single-source` | 0 | 0 | 0 |
| `kt-jsonl-sink-single-source` | 0 | 0 | 0 |
| `kt-l2-single-mirror-definition` | 0 | 0 | 0 |
| `kt-l3-end-turn-literal` | 0 | 0 | 0 |
| `kt-l3-sole-wire-terminals` | 0 | 0 | 0 |
| `kt-no-object-command` | 0 | 0 | 0 |
| `kt-no-quality-suppress` | 0 | 0 | 0 |
| `kt-no-request-body-gzip` | 0 | 0 | 0 |
| `kt-no-runblocking-in-daemon` (+ companion) | 0 | 0 | 0 |
| `kt-no-runcatching-in-coroutine` | 0 | 0 | 0 |
| `kt-no-sealed-interface` | 0 | 0 | 0 |
| `kt-no-silent-result-collapse` | 0 | 0 | 0 |
| `kt-no-stream-options-request` | 0 | 0 | 0 |
| `kt-secure-file-single-source` | 0 | 0 | 0 |
| `kt-tool-partition-no-transcript` | 0 | 0 | 0 |
| `l3-end-turn-via-emitter` | 0 | 0 | 0 |
| `l3-sole-message-stop-emitter` | 0 | 0 | 0 |
| `launcher-no-pkill` | 0 | 0 | 0 |
| `loopback-bind-only` | 0 | 0 | 0 |
| `no-claudex-magic-props` | 0 | 0 | 0 |
| `webui-css-tokens-only` | 0 | 0 | 0 |
| `webui-fetch-only-in-api` | 0 | 0 | 0 |
| `webui-fetch-only-in-api-tsx` | 0 | 0 | 0 |
| `webui-no-emdash-ui-text` | 0 | 0 | 0 |
