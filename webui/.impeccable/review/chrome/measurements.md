# M1-36 — the rail and the bar, measured against the comp

Comp of record: `webui/.impeccable/mocks/team-board-a.png`, 1536x1024, read at 1:1.
Build: `/teams?fixture=hero`, dark, captured at 1536x1024 and 3840x2160.
Artifact under test: `webui/dist/index.html` sha256 `b4dcc39a…f6f58231`.

"At comp scale" means the 3840 number divided by 2.5, so it can be compared with a comp
read at 1536.

## The five entries, by name

### N-3 — the rule bar reads as debris strung across a span. ANSWERED, and not by moving anything.

The entry asked what a 3840-wide bar carrying six short strings should BE: grouped, anchored,
or narrower. The comp's own top chrome answers none of those three. It divides.

Six vertical members in the comp's band, every one of them y5..58 of the 59px band (92% of its
height) and one to three pixels wide:

| member | comp x | comp % | the cell it bounds | this sheet's x |
|---|---|---|---|---|
| cap-left  | 6    | 0.39%  | opens the wordmark | 0.5% |
| d1        | 139  | 9.05%  | closes it          | 0.5 + 8.5 = 9.0% |
| d2        | 452  | 29.43% | closes the clocks  | 10 + 19 = 29.0% |
| d3        | 620  | 40.36% | closes health      | 30 + 10 = 40.0% |
| d4        | 1106 | 72.01% | opens the tail     | 72.0% |
| cap-right | 1529 | 99.54% | closes it          | 72 + 27 = 99.0% |

The comp's six x land on the five cells this sheet ALREADY positions, everywhere within half a
point except the right cap. That is the whole finding: M1-28 put the cells at the comp's own x
and recorded delta 0.00, and it was right to; what the band never had was the structure that
makes those positions read as cells instead of as gaps. Nothing moved to fix N-3.

Rendered, against the comp:

| member | comp % | build 1536 | d | build 3840 | d |
|---|---|---|---|---|---|
| 1 | 0.39  | 0.39  | +0.00 | 0.39  | +0.00 |
| 2 | 9.05  | 9.05  | +0.00 | 9.04  | -0.01 |
| 3 | 29.43 | 29.43 | +0.00 | 29.43 | +0.00 |
| 4 | 40.36 | 40.36 | +0.00 | 40.34 | -0.03 |
| 5 | 72.01 | 71.94 | -0.07 | 71.98 | -0.03 |
| 6 | 99.54 | 99.48 | -0.07 | 99.51 | -0.04 |

Height y5..58, 92% of the band at 1536 — the comp's own extent — and 90% at 3840.

This is the same entry splice-design raised as E-7 (comp four dividers, build zero). E-7 was
not a detail to add after the bar's shape was chosen; it WAS the shape, and it is why this
entry needed no judgment call after all.

### E-7 — the four dividers and two end caps. ANSWERED (see N-3 above).

Ink: `--hairline-strong`, `rgba(236,234,226,.22)`, which over `--room` resolves to (61,62,61).
The comp's six members measure, at p90, (61,64,58) (67,65,61) (62,61,59) (82,80,76) (52,52,50)
(65,64,61) — a median of 63.5. `--hairline` at .10 resolves to (34,36,35), a third of the
presence the comp draws, which is why the band had read as an undivided span. No new token: the
value the comp asks for was already in section 1 with no reader for it here.

Drawn as a background on `.myx-rule` rather than as six nodes — the rules are one ornament of
the band, they carry no state and no word, and a cell's own border could not draw them because
the cells are 58.6% of the band tall where these run 91.5%.

### N-4 — the rail plate's label is left-aligned with a dead tail. FIXED.

Comp: the word's centre sits at x62.0 of an 80px plate whose centre is x61.5 — half a pixel,
which is centred. Build before: `padding-inline-start: var(--space-4)`, the word hard against
the plate's left edge.

After, measured on all 13 plates through the DOM: **max |label centre − plate centre| = 0.01px**.

The clearance this had to survive is the longest address. "compaction" measures 61.0px inside a
plate whose word box is 61.4px; it clears each dot by 2.19px, against the 2.5px the comp leaves
the same word. Every other address has 6.9px or more.

### N-12 — the rail pin is a square at the plate's top edge. FIXED, and the cause was not the size.

`.myx-edge-mark` in `shared/ui/ui.css` carries only `width` and `background`, so inside a rail
tab it was a STATIC child of `.myx-edge` (`align-items: stretch`). Giving it an explicit height
cancelled the stretch and parked it at the top of the label's line box, and the
`inset-inline-start`, `top` and `margin-block-start` this rule had carried since it was written
applied to nothing at all. The render showed one mark straddling the plate's top border — see
`before-rail-6x.png`.

The comp prints TWO marks, one at each end, 4x5 at a 3px inset from the plate edge, centres at
y13.0 against the label's y12.5.

After:

| | comp 1536 | build 1536 | build 3840 |
|---|---|---|---|
| marks per plate | 2 | 2 | 2 |
| inset from plate edge | 3px both ends | 3.00px both ends (DOM) | 5px raster = 2.0 at comp scale |
| size | 4x5 | 4x4 round | 10x10 round |
| mark centre − plate centre | +1.0px | **0.00px** | 0.5px = 0.2 at comp scale |

The inset token is `--space-1` and not `--space-2` because an absolutely positioned mark measures
from the PADDING edge: 2px inside a 1px hairline is the comp's 3px exactly. At `--space-2` the
dots sat a pixel further in and left "compaction" clearing them by 0.19px, which reads as
touching. Height is 4px rather than the comp's 5 because the space scale has no 5; at this size a
4px square under a full radius is the comp's round dot. At 3840 the pair renders 10x10, against
the 9px round mark the entry reports there.

### N-9 — "the active-tab marker floats 40px clear of its own tab." WITHDRAWN.

The 40px is the 3840 frame. The comp floats its marker too:

| | marker w | gap from its plate |
|---|---|---|
| comp 1536 | 7px | **15px** |
| build 1536 | 7px | **15px** |
| build 3840 | 17px = 6.8 at comp scale | 39px = **15.6** at comp scale |

The gap is the comp's own and the entry is withdrawn. (An earlier note of mine retracted N-9 with
the figures "build 12px against the comp's 15px"; those were wrong — the numbers are the ones in
this table, and the conclusion that N-9 is not a defect is unchanged.)

The same measurement did find one real miss inside N-9's subject, and it is fixed: the rule
carried `width: 6.5%` under a comment that said "the comp's mark is 7px". 6.5% of an 80px plate
is 5.2px. It drew 5px at 1536 and 13px at 3840 where the comp asks for 7px. Now `8.75%`, which
renders 7px at 1536 and 6.8 at comp scale at 3840.

## What I did not change, and why

- **The rail plate is 78x26 where the comp's is 80x29.** Real, ~10% short in height, and it is
  `.myx-rail-tab { height: 3.66% }` in my own file — but it is none of the five entries this row
  names, and the plate's height is the rail column's spacing budget (M1-28's 29px tab / 33px
  gap). Reported, not touched.
- **The rule bar's type is larger than the comp's** across the wordmark, the clocks and the
  window figure — visible in `rule-band-stack.png`. That is M1-26's live scalar row and N-2's
  cap-height finding, not this row's.
- **The rail word is 0.32x the comp's.** The comp's word fills 49% of its label; the build's
  fills 16%. Raising it collides with M1-26's live scalar row, so no font size was changed here.

## Files

Before/after, same box, same frame, so the pair is comparable:

- `before-rail-6x.png` / `after-rail-6x.png` — the rail plates at 6x, box (10,55,160,300) of the
  1536 frame in both.
- `comp-rule-band-2x.png` / `before-rule-band-2x.png` / `after-rule-band-2x.png` and
  `rule-band-stack.png` — the top band, comp over before over after.
- `before-teams-dark-1536x1024.png` / `after-teams-dark-1536x1024.png`
- `before-teams-dark-3840x2160.png` / `after-teams-dark-3840x2160.png`

## Regenerating every number above

    npm run -w webui build
    node .dev/web-console/capture.mjs 'http://localhost:5173/#/teams?fixture=hero' \
      "$PWD/webui/.impeccable/review/chrome/after-teams-dark-1536x1024.png" 1536 1024
    node .dev/web-console/capture.mjs 'http://localhost:5173/#/teams?fixture=hero' \
      "$PWD/webui/.impeccable/review/chrome/after-teams-dark-3840x2160.png" 3840 2160

The divider census is a >55%-of-band-height ink-column scan of the top 5.8% of the frame; the
plate and mark numbers are read both off the raster and off the DOM through CDP, and the two
agree to within the antialiasing of a 1px border.

## A verify leg that cannot pass

This row's `verify` opens with `npm run -w webui typecheck`. There is no `typecheck` script in
`webui/package.json` — the scripts are `dev`, `build`, `lint`, `test`. `build` is
`tsc --noEmit && vite build`, so the typecheck runs, under another name. Every other leg of the
line was run as written and is green.
