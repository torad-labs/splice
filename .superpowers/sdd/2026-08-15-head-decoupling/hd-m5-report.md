# HD-M5 — style migration slice (providers: kimi, codex, grok)

**Status: LANDED (committed on the worktree branch).** 27 top-level functions → **0**;
6 companion objects → **0**. `:provider-kimi:test` 35/35, `:provider-codex:test` 26/26,
`:provider-grok:test` 31/31, `:app:test` green, whole-gateway `check` green, repo ast-grep walls
32/32, kimi goldens byte-identical.

**Two things need an operator eye before anything else is read: §1 (the worktree was cut from the
wrong commit and I corrected it) and §2 (four cross-module files in `:app` were edited, not one).**

No global hook was disabled. Nothing was suppressed, bypassed, or renamed to dodge a matcher.

---

## 1. DEVIATION — the worktree base was wrong, and I moved it

**What I found.** The brief states: *"Two landed slices (app root, app/cli) are already in your base
commit"* and *"`:app` is already fully migrated and clean."* Neither was true of the commit this
worktree was created at.

| | worktree base as created (`36e87bd`) | `feat/claude-head` tip (`154d79d`) | brief says |
|---|---|---|---|
| fence files | **10** | **9** | 9 |
| fence top-level funs | 27 | 27 | 27 |
| fence companion objects | **7** | **6** | 6 |
| `:app` top-level funs | **129** | 1 (`fun main`) | migrated |
| `:app` companion objects | **5** | 0 | clean |

`36e87bd` is **15 commits behind** `feat/claude-head`, and the two commits the brief depends on —
`2a1ce66 refactor(app): retire top-level functions and companions from the app root` and
`a0c0f6e refactor(cli): retire top-level functions from the CLI layer` — are both *ahead* of it.
The brief's own baseline numbers (**27 funs / 6 companions / 9 files**) match `feat/claude-head`
**exactly** and match the worktree base **not at all**; the extra 10th file is `KimiProvider.kt`,
deleted upstream by `4ce2bbf`. The brief was authored against `feat/claude-head`.

**Why this was blocking, not cosmetic.** The hooks scan the *whole proposed file*
(`lib/tool_input.proposed_contents` returns the full post-edit content, not the delta), so a file
that already violates §no-loose-functions is unwritable for **any** reason. At the stale base,
every consumer this slice must touch was in that state:

- `Daemon.kt` (10 top-level funs + 1 companion) — `CodexProvider.defaultQuirks` ×1, `GrokProvider.defaultQuirks` ×2
- `cli/LoginCommand.kt` — 9 call sites across all three vendors
- `DeviceLoginFlow.kt` — 3 kimi call sites
- `KimiRefresh.kt` — 1 kimi call site

**16 of the 27 fence functions are `public` and consumed from `:app`.** At the stale base the slice
caps out at ~11/27 functions and cannot reach 0 companions — and any `:app` edit made there would
have collided head-on with `2a1ce66`'s full rewrite of `Daemon.kt` at merge time.

**What I did.** `git reset --hard 154d79d` on my own worktree branch (clean tree, zero commits of my
own, restore point `36e87bd` recorded) — i.e. I made reality match the premise the brief states.
This is also what the campaign ledger's own dispatch note requires:

> *[2026-08-15] DISPATCHED via workflow wf_1a04286f-51d … Safe to parallelize because the three
> fences are module-disjoint and **every consumer that a moved symbol could force an edit into
> (`:app`) is ALREADY migrated and clean**. … Orchestrator merges each worktree branch in
> dependency order.*

**Consequence for the orchestrator:** my branch now descends from `feat/claude-head@154d79d`, so it
merges cleanly. **The two sibling worktrees (`…-1`, `…-3`) are still at `36e87bd`** and, if their
fences also export publics into `:app`, they will hit the same wall. Worth checking before merging
them. Revert for mine is `git reset --hard 36e87bd`.

---

## 2. Cross-module edits — four `:app` files, not one

The brief names only `Daemon.kt` as the permitted cross-module edit. The real surface is four files,
because the OAuth wire builders are `public` and `:app` owns the login/refresh flows. All four are
in `:app`, which is migrated and clean at the corrected base, so all four accepted the write.

| file | what changed | sites |
|---|---|---|
| `app/src/main/kotlin/splice/app/Daemon.kt` | 2 imports; `CodexProvider.defaultQuirks()` → `CodexQuirks().defaultQuirks()`, `GrokProvider.defaultQuirks()` → `GrokQuirks().defaultQuirks()` | 3 |
| `app/src/main/kotlin/splice/app/cli/LoginCommand.kt` | 13 imports → 7; gained `codexOAuth` / `grokOAuth` / `kimiOAuth` fields; receiver inserted at every call | 9 |
| `app/src/main/kotlin/splice/app/DeviceLoginFlow.kt` | 3 imports → 1; gained `kimiOAuth` field | 3 |
| `app/src/main/kotlin/splice/app/KimiRefresh.kt` | 1 import swapped; gained `kimiOAuth` field | 1 |

All four remain fully compliant (`:app` still reads 1 top-level fun — `fun main` — and 0 companions).
`gateway/dialect-openai-responses` needed **no** edit: its only mention of `defaultQuirks` is prose
in a comment. `provider-openai`'s identical `OpenAiResponsesProvider.defaultQuirks()` companion is
**out of fence** and was left alone (another slice owns it); `Daemon.kt:737` still calls it.

Test files touched (call-site relocation only, no assertion changed): `KimiOAuthTest`,
`CodexAuthTest`, `CodexProviderTest`, `GrokOAuthTest`.

---

## 3. Per file — what moved, to which pattern

| file | new type(s) | moved | pattern |
|---|---|---|---|
| **KimiDeviceIdentity.kt** | `private class KimiHostname` | companion → 1 file-scope `private const val` + `asciiSanitize` as a private member; `defaultHostname` to the new class (§4) | **2** + **1** + **4** |
| **KimiOAuth.kt** | `public class KimiOAuth` | 7 of 9 funs become members; `writeSecure` + `jsonObjectOrEmpty` leave the file (§5) | **1** |
| **KimiAuthProvider.kt** | — | companion (4 consts) → file-scope `private const val`; `kimiAuthMtimeOrNull` → private member; gained `oauth`, plus its own `writeSecure` / `jsonObjectOrEmpty` | **2** + **1** |
| **CodexOAuth.kt** | `public class CodexOAuth` | all 8 funs become members | **1** |
| **CodexAuthProvider.kt** | — | companion (14 consts) → file-scope `private const val`; `codexAuthMtimeOrNull` → private member; gained `oauth` for `decodeJwtClaims` | **2** + **1** |
| **CodexProvider.kt** | `public class CodexQuirks` | `public companion object { defaultQuirks }` → the new class | **4** |
| **GrokOAuth.kt** | `public class GrokOAuth` | all 7 funs become members | **1** |
| **GrokAuthProvider.kt** | `private class GrokAuthFile` | companion (11 consts) → file-scope `private const val`; `grokAuthMtimeOrNull` to the new class — **forced by the function budget** (§4) | **2** + **4** |
| **GrokProvider.kt** | `public class GrokQuirks` | `public companion object { defaultQuirks }` → the new class | **4** |

**Left at file scope on purpose** (all legal top-level `val` / `const val`; each shared singleton
carries a `FILE SCOPE ON PURPOSE` comment): `kimiJson`, `jwtJson`, `grokJson` (one configured `Json`
parser each — as members they would be rebuilt per collaborator construction, and the device-flow
caller constructs one per poll tick), plus the wire-field-name and tuning consts in every file.
`KimiOAuth.MS_PER_S` and `kimiJson` keep their existing `internal` visibility — they were not
relocated and were not touched.

**Types were NOT moved into the new classes.** `KimiDeviceAuthorization`, `KimiRefreshedTokens`,
`RefreshedTokens`, `GrokRefreshedTokens`, `Pkce` (×2), and the three `*OAuthEndpoints` objects all
stay top-level. The law bans top-level *functions*, not types, and moving them would churn every
consumer's type reference for nothing.

---

## 4. detekt decompositions — measured, not guessed

The repo carries its own `§too-many-functions` checker whose message states the rule exactly:

```
`GrokAuthProvider` declares 15 functions (max = 14; detekt flags at 15).
…
`override` functions don't count; a kotlin-inject @Component is exempt (rule 82).
```

**Red proof.** `GrokAuthProvider` holds **14** non-override functions. I attempted to add one
synthetic 15th member (`private fun budgetProbe(): Int = 0`) — the exact count the class would reach
if `grokAuthMtimeOrNull` were folded in as a member. The write was **rejected** with the message
above. So pattern 1 is unavailable here and `GrokAuthFile` (pattern 4) is forced — which is what the
function's own pre-existing comment already said (*"Top-level (not a class member) so
GrokAuthProvider stays under the TooManyFunctions ceiling"*). The probe was never applied; the file
on disk never contained it.

**Green proof, by construction.** `CodexAuthProvider` (11 → **12** with its probe folded in) and
`KimiAuthProvider` (10 → **13** with its probe + `writeSecure` + `jsonObjectOrEmpty`) both accepted
their writes, so both are ≤ 14 — the checker runs on every write, so acceptance *is* the measurement.
Both therefore host their mtime probe as a normal private member; only grok needed a collaborator.

Post-migration non-override function counts, machine-counted, all ≤ 14:

```
KimiDeviceIdentity=4  KimiHostname=1   KimiOAuth=8    KimiAuthProvider=13   KimiOAuthEndpoints=3
CodexOAuth=8          CodexQuirks=1    CodexAuthProvider=12                 CodexOAuthEndpoints=5
GrokOAuth=7           GrokQuirks=1     GrokAuthProvider=14  GrokAuthFile=1  GrokOAuthEndpoints=4
```

**Carry-forward:** `GrokAuthProvider` is at **14 of 14**. The next method added to it — by HD-1 or
anyone — fails the build. It has no headroom left at all.

**ktlint findings introduced by the restructuring were fixed structurally**, never suppressed. Five
lines crossed `MaxLineLength` (120) once a receiver was inserted and were re-wrapped, bodies
unchanged: `LoginCommand.kimiDeviceSpec`'s `toAuthJson` lambda; `CodexAuthTest`'s
`authJsonFromTokens` call; `KimiOAuthTest`'s 5000L `kimiAuthJsonFromTokenResponse` assertion and two
`isPlanTierRejection` assertions.

---

## 5. Pattern-5 cases

**None. This slice creates zero new `object` declarations.** Every new type is a class its caller
constructs (`KimiOAuth`, `CodexOAuth`, `GrokOAuth`, `CodexQuirks`, `GrokQuirks`, `KimiHostname`,
`GrokAuthFile`). The three pre-existing `object`s in the fence (`KimiOAuthEndpoints`,
`CodexOAuthEndpoints`, `GrokOAuthEndpoints`) were not created, converted, or extended.

Two judgement calls worth reviewing once rather than discovering later:

1. **`KimiHostname` exists because a constructor default cannot reach an instance member.**
   `KimiDeviceIdentity(rawHostname: String = …)` is evaluated before `this` exists. A top-level
   `val` would have resolved the hostname **once per classloader** instead of once per identity —
   a real behavioural change — so a one-method class is the cheapest form that preserves
   per-construction evaluation. A top-level `val` of function type would have passed both the hook
   and the acceptance grep while being a top-level function in all but syntax; refused, same as
   HD-M2 refused it for `openBrowser`.

2. **`writeSecure` and `jsonObjectOrEmpty` are now duplicated rather than shared.** Both were
   top-level `internal` in `KimiOAuth.kt` and used from two/three classes. Rather than force
   `with(oauth) { … }` at every call site, each consumer now carries its own `private` copy — which
   is **exactly the shape `CodexAuthProvider` and `GrokAuthProvider` already had** (both have
   carried a private `writeSecure` member for months, and `GrokOAuth.kt` already had its own private
   `jsonObjectOrEmpty`). Every body is an unchanged one-line delegation to
   `SecureFile.writeAtomic0600` / a safe cast, so the repo's `kt-secure-file-single-source` wall —
   which bans re-deriving the 0600 permission set, not thin delegators — stays satisfied (verified:
   `gate:rules` 32/32). Call sites are byte-identical because the calls stay unqualified.

---

## 6. Behaviour-preservation risks — check these hardest

1. **`defaultQuirks()` is still evaluated per construction, not once.** `CodexQuirks()` /
   `GrokQuirks()` are constructed at each call, exactly as the companion's function was invoked at
   each call. Had I made these a top-level `val`, every `ResponsesProvider` would have shared one
   `ResponsesQuirks` instance. → verify no call site was rewritten to a shared constant. There are 4
   (`Daemon.kt` ×3, `CodexProviderTest` ×1) plus the two constructor defaults.
2. **The collaborators are stateless and their constructors are empty.** `KimiOAuth`, `CodexOAuth`,
   `GrokOAuth`, `CodexQuirks`, `GrokQuirks`, `KimiHostname`, `GrokAuthFile` — none has an `init`
   block or a side-effecting field. This is the ledger's carry-forward risk (2): the "constructed
   collaborator per invocation" shape is safe *only* while that holds, and nothing mechanical
   enforces it.
3. **Argument order cannot have drifted.** No extension function was converted into a member taking
   its receiver as a parameter — the HD-M2 near-miss. `jsonObjectOrEmpty` and `padBase64` stayed
   *member extensions*, so the receiver is still the receiver and every call site reads unchanged.
4. **The device identity's persistence path and values are untouched.** `deviceIdPath`, the uuid
   read-or-create, the five `X-Msh-*` header names, the ASCII sanitiser and its `0x80` ceiling, and
   the `"unknown"` fallbacks are all byte-identical; only `asciiSanitize`'s owner changed.
   `Daemon.kt` and `LoginCommand.kt` still construct `KimiDeviceIdentity` the same way and still
   call `identity.headers()`.
5. **Refresh semantics, the single-flight lock, and synthesized expiry were not restructured.**
   `doRefresh` / `refreshLocked` / `peerRotation` / `exchangeRefreshToken` / `rejectedOrRetry` /
   `persistRotation`, the `SingleFlight` + `CredentialLock` + `InvalidGrantLatch` wiring, the
   `invokeOnCompletion { singleFlight.close() }` init blocks, and `synthesizedExpiryMs`'s
   mtime-clamped call sites all moved zero lines. The only edits inside these classes are the
   const relocation and the three call sites that gained an `oauth.` / `authFile.` receiver.
6. **Mechanical evidence that no wire byte moved.** Every double-quoted literal on a code line of
   every changed main source, compared as a multiset against `HEAD`:
   ```
   TOTAL string literals (code lines): HEAD=314 NOW=314
   (zero per-file differences — no literal added, removed, or altered in any file)
   ```
   That covers every client id, endpoint path, grant type, form key, JSON field name, header name
   and log prefix in the three vendors.
7. **`kimiAuthJson` is still `internal`,** so `KimiOAuthTest` and `KimiAuthProvider` reach it
   unchanged; it did **not** become public just because its owner is a public class.

**Pre-existing, reported not fixed** (per the brief):

- **`GrokOAuthEndpoints.SCOPE`'s neighbouring KDoc is misattached.** In `GrokAuthProvider.kt` the
  comment *"4h ceiling synthesized for auth files with no `expires` field (legacy/foreign CLI
  writes, G18)"* sits directly above `FIELD_TOKENS`, which it does not describe — the constant it
  documented (`SYNTHETIC_EXPIRY_TTL_MS`) moved to `:core` in an earlier change. Carried verbatim
  into the file-scope const block rather than silently re-worded.
- **`GrokAuthProvider.credentials()` has a documented-dead null branch** (`expiresAt == null`), kept
  deliberately per its own comment. Untouched.
- **Kotlin warning `DoctorCommand.kt:205:40 Unnecessary safe call on a non-null receiver`** — in
  `:app`, pre-existing, already reported by HD-M1. Not in this fence and not introduced here.

---

## 7. Verify outputs

**V1 — `cd gateway && ./gradlew :provider-kimi:test :provider-codex:test :provider-grok:test --console=plain`** (`--rerun-tasks`, no cache)
```
BUILD SUCCESSFUL in 18s
39 actionable tasks: 39 executed
```
```
provider-kimi:  tests=35 failures=0 errors=0 skipped=0
provider-codex: tests=26 failures=0 errors=0 skipped=0
provider-grok:  tests=31 failures=0 errors=0 skipped=0
```

**V2 — `./gradlew check --console=plain` (whole gateway: konsist + detekt + ktlint + kover, all modules)**
```
BUILD SUCCESSFUL in 19s
162 actionable tasks: 27 executed, 1 from cache, 134 up-to-date
```

**Extra — `./gradlew :app:test --console=plain --rerun-tasks`** (not in the brief's list, but I
edited four `:app` files)
```
BUILD SUCCESSFUL in 22s
41 actionable tasks: 41 executed
```

**V3 — `cd .. && npm run gate:rules`**
```
PASS kt-shared-summary-parts-single-source  ......

test result: ok. 32 passed; 0 failed;
```
`ast-grep scan` clean; `kt-secure-file-single-source`, `kt-json-scalars-single-source`,
`kt-form-encoding-single-source` and `kt-no-println` self-tests all green.

**V4 — `git status --porcelain -- gateway/dialect-anthropic-passthrough/src/test/resources/goldens/`**
```
(empty — 0 lines; kimi goldens byte-identical)
```

**V5 — top-level functions in the fence** (the brief's `$(find …)` form is blocked by the worktree
sandbox; this is the identical `grep -r` substitution over the same three `src/main` trees)
```
$ grep -rhcE '^(public |private |internal )?(suspend )?(inline )?fun[ <]' --include=*.kt \
    gateway/provider-kimi/src/main gateway/provider-codex/src/main gateway/provider-grok/src/main \
    | paste -sd+ | bc
0
```
Baseline 27. **Zero residue** — this fence contains no `fun main`, so unlike HD-M2 the count is a
clean 0, not 1.

**V6 — companion objects in the fence**
```
$ grep -rhc 'companion object' --include=*.kt \
    gateway/provider-kimi/src/main gateway/provider-codex/src/main gateway/provider-grok/src/main \
    | paste -sd+ | bc
0
```
Baseline 6. (This grep is text-based and counts comments, per HD-M2's binding note — no fenced file
contains the banned phrase in prose either.)

**Working tree at land** — fence + forced cross-module call sites + forced test call sites + this
report; nothing else. No config file, no golden, no build file:
```
 M gateway/app/src/main/kotlin/splice/app/{Daemon,DeviceLoginFlow,KimiRefresh}.kt
 M gateway/app/src/main/kotlin/splice/app/cli/LoginCommand.kt
 M gateway/provider-codex/src/main/kotlin/splice/provider/codex/{CodexAuthProvider,CodexOAuth,CodexProvider}.kt
 M gateway/provider-codex/src/test/kotlin/{CodexAuthTest,CodexProviderTest}.kt
 M gateway/provider-grok/src/main/kotlin/splice/provider/grok/{GrokAuthProvider,GrokOAuth,GrokProvider}.kt
 M gateway/provider-grok/src/test/kotlin/grok/GrokOAuthTest.kt
 M gateway/provider-kimi/src/main/kotlin/splice/provider/kimi/{KimiAuthProvider,KimiDeviceIdentity,KimiOAuth}.kt
 M gateway/provider-kimi/src/test/kotlin/kimi/KimiOAuthTest.kt
```
`0` suppressions added (`@Suppress` / `ktlint-disable` / `detekt:disable` all zero in the `+` side of
the diff); `ast-grep-ignore` comments are net zero (2 added, 2 removed — the same two moved with
their code). All 9 fence files retain their `NEW:` / `PORT-OF:` konsist header.

---

## 8. MECHANICAL BEFORE/AFTER VISIBILITY TABLE

Every relocated symbol, in file order. **`CHANGED` marks the only four visibility changes; every
other row keeps its exact effective reach.** Note that a member of a `private companion object` is
reachable only from its enclosing class, so `companion member → private member` is a *no-op*, not a
narrowing.

### provider-kimi

| # | file | symbol | before | after | |
|---:|---|---|---|---|---|
| 1 | KimiDeviceIdentity.kt | `ASCII_CEILING` | `const` in `private companion object` | file-level `private const val` | |
| 2 | KimiDeviceIdentity.kt | `asciiSanitize` | member of `private companion object` (reach: the class) | `private` member of `public class KimiDeviceIdentity` | |
| 3 | KimiDeviceIdentity.kt | `defaultHostname` | member of `private companion object` (reach: the class) | member of file-private `class KimiHostname` (reach: the file) | **WIDENED** |
| 4 | KimiOAuth.kt | `kimiDeviceAuthorizationForm` | file-level `public` | `public` member of `public class KimiOAuth` | |
| 5 | KimiOAuth.kt | `kimiTokenPollForm` | file-level `public` | `public` member of `public class KimiOAuth` | |
| 6 | KimiOAuth.kt | `kimiRefreshForm` | file-level `public` | `public` member of `public class KimiOAuth` | |
| 7 | KimiOAuth.kt | `parseKimiDeviceAuthorization` | file-level `public` | `public` member of `public class KimiOAuth` | |
| 8 | KimiOAuth.kt | `kimiAuthJsonFromTokenResponse` | file-level `public` | `public` member of `public class KimiOAuth` | |
| 9 | KimiOAuth.kt | `kimiAuthJson` | file-level `internal` | `internal` member of `public class KimiOAuth` | |
| 10 | KimiOAuth.kt | `isPlanTierRejection` | file-level `public` | `public` member of `public class KimiOAuth` | |
| 11 | KimiOAuth.kt | `JsonElement.jsonObjectOrEmpty` | file-level `internal` extension | `private` member extension ×2 (`KimiOAuth`, `KimiAuthProvider`) | **NARROWED** |
| 12 | KimiOAuth.kt | `writeSecure` | file-level `internal` | `private` member ×2 (`KimiDeviceIdentity`, `KimiAuthProvider`) | **NARROWED** |
| 13 | KimiAuthProvider.kt | `LOG_TAG` | `const` in `private companion object` | file-level `private const val` | |
| 14 | KimiAuthProvider.kt | `DEFAULT_CACHE_MS` | `const` in `private companion object` | file-level `private const val` | |
| 15 | KimiAuthProvider.kt | `MIN_PROACTIVE_S` | `const` in `private companion object` | file-level `private const val` | |
| 16 | KimiAuthProvider.kt | `HARD_FLOOR_S` | `const` in `private companion object` | file-level `private const val` | |
| 17 | KimiAuthProvider.kt | `kimiAuthMtimeOrNull` | file-level `private` | `private` member of `public class KimiAuthProvider` | |

### provider-codex

| # | file | symbol | before | after | |
|---:|---|---|---|---|---|
| 18 | CodexOAuth.kt | `base64url` | file-level `private` | `private` member of `public class CodexOAuth` | |
| 19 | CodexOAuth.kt | `makePkce` | file-level `public` | `public` member of `public class CodexOAuth` | |
| 20 | CodexOAuth.kt | `buildAuthorizeUrl` | file-level `public` | `public` member of `public class CodexOAuth` | |
| 21 | CodexOAuth.kt | `codexCodeExchangeForm` | file-level `public` | `public` member of `public class CodexOAuth` | |
| 22 | CodexOAuth.kt | `decodeJwtClaims` | file-level `public` | `public` member of `public class CodexOAuth` | |
| 23 | CodexOAuth.kt | `String.padBase64` | file-level `private` extension | `private` member extension of `CodexOAuth` | |
| 24 | CodexOAuth.kt | `accountIdFromIdToken` | file-level `public` | `public` member of `public class CodexOAuth` | |
| 25 | CodexOAuth.kt | `authJsonFromTokens` | file-level `public` | `public` member of `public class CodexOAuth` | |
| 26 | CodexAuthProvider.kt | `REFRESH_ERROR_SNIPPET`, `LOG_TAG`, `KIND`, `MASK_KEEP`, `MS_PER_S`, `PROACTIVE_WINDOW_MS`, `STALE_FLOOR_MS`, `FIELD_TOKENS`, `FIELD_ACCESS_TOKEN`, `FIELD_REFRESH_TOKEN`, `FIELD_ID_TOKEN`, `FIELD_ACCOUNT_ID`, `FIELD_LAST_REFRESH`, `FIELD_EXP` (14) | `const` in `private companion object` | file-level `private const val` | |
| 27 | CodexAuthProvider.kt | `codexAuthMtimeOrNull` | file-level `private` | `private` member of `public class CodexAuthProvider` | |
| 28 | CodexProvider.kt | `defaultQuirks` | `public` member of `public companion object` | `public` member of new `public class CodexQuirks` | |

### provider-grok

| # | file | symbol | before | after | |
|---:|---|---|---|---|---|
| 29 | GrokOAuth.kt | `makeGrokPkce` | file-level `public` | `public` member of `public class GrokOAuth` | |
| 30 | GrokOAuth.kt | `grokBase64Url` | file-level `private` | `private` member of `public class GrokOAuth` | |
| 31 | GrokOAuth.kt | `buildGrokAuthorizeUrl` | file-level `public` | `public` member of `public class GrokOAuth` | |
| 32 | GrokOAuth.kt | `grokCodeExchangeForm` | file-level `public` | `public` member of `public class GrokOAuth` | |
| 33 | GrokOAuth.kt | `grokRefreshForm` | file-level `public` | `public` member of `public class GrokOAuth` | |
| 34 | GrokOAuth.kt | `grokAuthJsonFromTokenResponse` | file-level `public` | `public` member of `public class GrokOAuth` | |
| 35 | GrokOAuth.kt | `JsonElement.jsonObjectOrEmpty` | file-level `private` extension | `private` member extension of `GrokOAuth` | |
| 36 | GrokAuthProvider.kt | `LOG_TAG`, `REFRESH_INEFFECTIVE_BACKOFF_MS`, `DEFAULT_CACHE_MS`, `MS_PER_S`, `PROACTIVE_WINDOW_MS`, `STALE_FLOOR_MS`, `FIELD_TOKENS`, `FIELD_ACCESS_TOKEN`, `FIELD_REFRESH_TOKEN`, `FIELD_LAST_REFRESH`, `FIELD_EXPIRES` (11) | `const` in `private companion object` | file-level `private const val` | |
| 37 | GrokAuthProvider.kt | `grokAuthMtimeOrNull` | file-level `private` (reach: the file) | member of file-private `class GrokAuthFile` (reach: the file) | |
| 38 | GrokProvider.kt | `defaultQuirks` | `public` member of `public companion object` | `public` member of new `public class GrokQuirks` | |

**Rows 4–12, 17, 18–25, 27, 29–35, 37 are the 27 baseline top-level functions.** Rows 1–3, 13–16,
26, 28, 36, 38 are the contents of the 6 removed companion objects.

### Widening / narrowing audit — the four changed rows in full

- **#3 `defaultHostname` class-private → file-private. WIDENED.** Forced: it is the default value of
  `KimiDeviceIdentity`'s `rawHostname` constructor parameter, which is evaluated before an instance
  exists, so it cannot be an instance member; top-level functions are banned; and a top-level `val`
  would change per-construction evaluation into once-per-classloader. New reach is the one file
  `KimiDeviceIdentity.kt`, which has exactly one other declaration. No consumer outside that file
  can see `KimiHostname` at all.
- **#11 `jsonObjectOrEmpty` `internal` → `private` ×2. NARROWED.** The two consumers were both in
  `provider-kimi`; each now owns a private copy, matching `GrokOAuth.kt`'s pre-existing private copy
  of the identical helper.
- **#12 `writeSecure` `internal` → `private` ×2. NARROWED.** Same reasoning; matches the private
  `writeSecure` member `CodexAuthProvider` and `GrokAuthProvider` already carried.
- **#37 `grokAuthMtimeOrNull`** — effective reach is *unchanged* (file-private before, file-private
  after); listed only because its owner changed from the file to `GrokAuthFile`.

**Rows 28 and 38 are not visibility changes but they ARE public-API shape changes**, and are the
rows a reviewer should look at hardest: `CodexProvider.defaultQuirks()` and
`GrokProvider.defaultQuirks()` were reachable statically off the provider type and are now instance
methods on `CodexQuirks` / `GrokQuirks`. Same for rows 4–10, 19–25, 29–34: those 21 `public`
functions keep `public` visibility but now require a `KimiOAuth()` / `CodexOAuth()` / `GrokOAuth()`
receiver. Every one of the 16 external call sites is listed in §2.

**No other symbol's visibility changed. There are no undisclosed widenings.**
