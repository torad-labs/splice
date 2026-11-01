# splice.torad.ai's website

One page whose product parts are the console's own markup, on the console's own stylesheets, with made-up data. The
worker beside this folder (`../worker.ts`) serves what the build writes at every path but `/download`.

```sh
# build (the film, its captions and the social card are too large for the repository; the torad repository holds them)
bun tools/release/downloads/website/build.ts tools/release/downloads/site \
  --media <torad-main>/apps/website/build/held/products/splice/assets
# test
bun test tools/release/test/website.test.ts
# deploy, with wrangler's own login
bunx wrangler@4 deploy --config tools/release/downloads/wrangler.jsonc
```

`--bands commands,team` builds the page with only those bands, to look at them alone.

## Files

- `page.html` is the page: head, header, first screen, closing band, footer, film window. A line `<!-- band NAME -->`
  is replaced by `bands/NAME.html`, in that order.
- `page.css` and `page.js` hold what every band shares. The build appends each band's `bands/NAME.css` and
  `bands/NAME.js` to make `/site.css` and `/site.js`.
- `keep.json` lists every command, path, question, link and control the page must keep. The tests read it.

## Writing a band

- The band is `<section class="pg-band" id="ANCHOR" aria-labelledby="PREFIX-h">`, holding `<div class="pg-in">` (the
  page's one container), an `<h2 class="pg-h2" id="PREFIX-h">` of two to five words and one `<p class="pg-line">`.
- Every class the band adds starts with its prefix: commands `cm-`, same-setup `ss-`, accounts `ac-`, resume `rs-`,
  team `tm-`, dashboard `db-`, more `mo-`, setup `su-`, questions `qa-`; the page's own parts use `fr-` and `pg-`.
  The console's stylesheet already styles common names (`.bar`, `.sr`, `.row`, `.card` and more), so an unprefixed
  class is a console class, used as the console uses it.
- Product parts are copied from the console's markup and keep its classes. A band names the console page stylesheets
  it needs on its first line, `<!-- console: accounts.css teams.css -->`, and the build links each once in the head.
- Widths: `main` is a size container named `pg`. Write `@container pg (max-width: 1800px)` for the 1440 sizes and
  `@container pg (max-width: 640px)` for phones.
- Reveal: give an element `data-reveal` and `page.js` adds `is-seen` once, the first time it scrolls into view. Write
  the "before" state under `.pg-js` inside `@media (prefers-reduced-motion: no-preference)`, so a visitor without
  scripts, or who asks for less motion, sees everything at rest.
- Tabs and steps are radio inputs with labels, rows that open are `<details>`, and a copy button is
  `<button type="button" class="act" data-copy="TEXT">Copy</button>`, which `page.js` handles. A band that needs more
  writes `bands/NAME.js`, touching only its own elements.
- A control the picture draws but the page cannot run (Allow, Deny, Save, a preset, a field, a tool button) carries
  `data-drawn` and `tabindex="-1"`. `page.css` takes the pointer off it, so it never looks pressable and the Tab key
  skips it. The tests fail on any other button or field that does nothing.
- A copy button the page draws with both words, as the first screen's does, holds `<span data-copy-label>` (Copy) and
  `<span data-copied-label aria-hidden="true">` (Copied) and starts `hidden`; `page.js` keeps the markup and adds
  `is-copied` for 1.6 s, and the page's css slides the labels. A plain button's text is replaced by Copied.
- The frame (`fr-`) owns what stands outside `<main>`: the fixed header and its menu (`[data-menu]`, opened by `page.js`),
  the footer and the film window (`<dialog id="fr-film">`, opened by `[data-film]` links). Those answer to the viewport
  (`@media`), not to `@container pg`, and take the container as `.fr-wrap` (the same widths as `.pg-in`). Its anchors
  `#commands`, `#teams`, `#console`, `#questions` and `#setup-install` are the ids bands give their sections. `main` and the
  footer sit at `z-index: 1`, above the console's wall (body's grain and light shaft).
- Names and figures are made up. No real address, key, token or machine path goes on the page; the tests check.
- A fact about splice itself is not made up: the page states what the release does. Where splice's source defines the
  number, write it as `{{name}}` and the build fills it from that source: `{{tested-claude-code}}`,
  `{{newer-claude-code}}` (above it, for the "is newer than" status line) and `{{history-days}}` (how long a fresh
  install keeps prompts and answers). A name the build does not know fails it; add new ones to `facts` in `build.ts`.
- Files on this host: the console's under `/console/` (for example `/console/brand/splice-horizontal-night.svg`), the
  film's under `/landing/products/splice/assets/`.
