# .dev/web-console — the campaign's tools

Everything here runs from the worktree root. None of it writes to a source file, and none of it
prints the management key.

| tool | what it does |
|---|---|
| `look.mjs '<url>'` | **the one command a look-bearing row runs before calling itself done** — freezes the address, runs the rendered detector on it, runs `look-gate.mjs`, and reports the two area-weighted distributions below. Exits non-zero if any of the three blocks. |
| `snapshot.mjs '<url>' [out.html]` | freezes one address into ONE self-contained HTML file: the rendered DOM, every stylesheet's text, fonts inlined as data URIs, every `<script>` removed. |
| `look-gate.mjs` | the mechanical half of a design review: ladder steps, type and spacing distribution, scale-transform laundering, absence vocabulary, the field grid of a rack, tonal drift against the comp. `--selftest` mutation-proves each check. |
| `capture.mjs '<url>' <out.png>` | one screenshot of one address. |
| `gate.mjs`, `fixture-leak.mjs`, `comp-check.mjs`, `idle-watch.ts` | the campaign's other checks, each with its own header. |
| `lib/cdp.mjs` | the Chrome-over-CDP plumbing they share, and the one place the management key is read. |

## Why the console needs a snapshot to be measured

The console is behind the management-key gate, and a headless Chrome profile starts with an empty
`localStorage`, so a plain screenshot or a bare URL capture **the gate**, not the console. Driving
one Chrome over the protocol is the only way to put values into `localStorage` before the app
boots — which is also how a fixture address and a theme are set. `lib/cdp.mjs` reads the key from
`~/.claude-codex/state/mgmt-key`, seeds it, and never logs it. **The key must never appear in a
verify line, a note, a file name, or any output.**

## The rendered pass — two engines, and the trap in the third route

`look.mjs` runs TWO passes, because one engine cannot see everything.

**`dom`** — `detect.mjs` reading the snapshot **as a file**. A file target reaches the static-HTML
engine, which parses the markup *and resolves the cascade*, so it emits rules a source *directory*
cannot: a regex pass never sees a computed inset. This is the pass that found the board's flush cells
mechanically.

**`layout`** — the detector's own rendered rule set, run on **our** page over CDP. Those rules live in
one page-side bundle (`detect-antipatterns-browser.js`) that `detect-url.mjs` injects with
`page.evaluate` and then calls as `window.impeccableDetect(...)`. Nothing in it wants a puppeteer
handle: the rules run inside the page against the real DOM, so the only thing puppeteer provides at
that point is `evaluate` — which this campaign's CDP client already does. This is the only route to
the rules that need real geometry (`clipped-overflow-container`, `text-occlusion`, contrast by
rect), and it is why **no puppeteer install is needed**. The bundle is read from the skill at run
time and never copied into this repo — a vendored rule set goes stale silently.

**The trap.** Passing the snapshot as a `file://` URL routes to the browser engine, which needs
puppeteer; without it `detect.mjs` writes `Error: puppeteer is required for URL scanning` to stderr
and **exits 0** with an empty finding list, so a verify line chaining it reports a GREEN with nothing
rendered. `look.mjs` never takes that route, and it blocks when neither of its two passes produced a
finding.

## The two area-weighted distributions

A count of CSS declarations has no weight in it: a strip's internal 4px field padding is one
declaration and a few hundred pixels of a page, while the rack's 34px gap is one declaration and
most of what the eye reads as rhythm. Counting them equally measures the stylesheet, not the page —
measured, the comp-faithful hero scored *worse* than nine of the pages it sets the standard for.

`look.mjs` reports what the page actually shows:

- **type size by share of rendered text area** — every text node's own line boxes, summed, bucketed
  by the computed font size;
- **gap size by area between sibling boxes** — for each pair of adjacent laid-out siblings, the
  distance between them times the length of the edge it runs along.

Both are printed beside the same two questions asked of the approved comp (`team-board-a.png`). The
comp's side is a census of its measured region sizes and its scanned gaps; making *it* area-weighted
too needs its text masks, and saying which half is weighted is part of not flattering the answer.
A distance over 200px is labelled a layout void rather than a rung, and left in the denominator.

## Usage in a verify line

```
node .dev/web-console/look.mjs 'http://localhost:5173/#/teams?fixture=hero'
```

Fixture addresses put the fixture inside the hash for pages whose router reads
`useLocation().search` (`#/teams?fixture=hero`); a page reading `window.location.search` takes it in
the query (`?fixture=hero#/fleet`). `snapshot.mjs` and `look.mjs` handle both — the file name comes
from whatever follows the `#`.
