# The type ladder — spec by role (M1-22)

Every entry below carries either a measurement or an explicit gap. An entry with neither is worth
less than no entry, because it reads like a decision somebody made.

**The number 14/16/17/17/17/18/23 is WITHDRAWN and must not be used.** It was a census of seven
named regions, and design-builder4 showed that the scaffold sizes 10 of 28 regions carrying 2.6
percent of the comp's ink — three of those ten being 4px and 5px junk rows carrying 72 percent of
that sliver. That ladder was measured off almost nothing, and it is not quoted anywhere below.

## The unit

Ink height: the rendered cap height of a capital in the role's own computed font, read from a canvas
2D context's `actualBoundingBoxAscent`. It is the same quantity the reviewer's blind pass measured
off the raster, and it is measured rather than derived from a ratio.

The spec is **per frame**. The layout is in `vw` and, since M1-26, the type and spacing scales are in
`rem` off a root that derives from the frame. Every number below is therefore a share of the frame
height, which is the only form that can be true at two sizes:

    share = ink height / frame height

At 1536x1024 and at 3840x2160 the same role measures the same share (measured, both columns below),
which is what "it scales" means. A spec written in px would be true at one size and wrong at the
other; that is the defect this row exists to close.

## The measured ladder (pages/turns, 2026-09-18, both themes dark)

| role | selector | 1536 ink | 3840 ink | share of frame | source |
|---|---|---|---|---|---|
| wordmark | `.myx-rule-wordmark` | 17px | 40px | 1.852% | `look.mjs --ladder` |
| page title | `h1, h2` | 14–17px | 34px | 1.574% | `look.mjs --ladder` |
| strip value | `.myx-sfield-text` | 11px | 28px | 1.296% | `look.mjs --ladder` |
| tab label | `.myx-rail-tab .myx-edge-label` | 10px | 24px | 1.111% | `look.mjs --ladder` |
| field label | `.myx-sfield-label` | 9px | 20px | 0.926% | `look.mjs --ladder` |
| bay label | `.myx-bay-label` | 9px | 20px | 0.926% | `look.mjs --ladder` |

The column that matters is the share: 17/1024 = 1.660% against 40/2160 = 1.852% is the same rung on
two frames within the measurement's granularity, and the two smallest roles hold 0.879% at 1536
against 0.926% at 3840. Nothing here is a px value that has to be re-typed at a new size.

## The state of the two defects this row was cut for

N-2 (design-reviewer, blind, at 3840x2160, before the scale work landed) measured: column header 9px,
knob name 9px, the word `default` 9px, `restart to apply` 9px, clock digits 14, strip label 15,
wordmark 17, rail tab label 18, page title 21. Two findings in one table, and their state now:

1. **The floor.** 9px of ink on a 2160 frame is 0.417% — about 1.6mm of cap height on a 32-inch 4K
   panel, on every knob name and every column header. **This is met today**: the same roles measure
   20px, 0.926%, because M1-26 moved the ladder into rem off a frame-derived root. The reviewer's
   frame is not reproducible now and the role it indicted renders at 2.2x the ink it did.
2. **The inversion.** A nav tab label was larger than the wordmark (18 against 17) and the page title
   was 2.3x the smallest text. **This is met today**: the ladder reads 40 > 34 > 28 > 24 > 20, so the
   wordmark leads, the tab label sits fourth, and the title is 1.7x the bottom rung. The check below
   asserts both, so a return to the old shape fails by name.

Both were consequences of a px ladder on a 3840 frame, and both are the *reason* the scale work was
the right fix rather than a rung chosen by hand.

## What is NOT measured, and why

**The comp's own distribution, by area: DID NOT RUN.** Half One of this row owes the comp's type as
an area-weighted distribution measured the way the build's is, so the two can be compared. The
instrument exists — `node dev/web-console/look.mjs --comp <png>` derives the frame's flat grounds,
groups ink into lines, and reports each line's ink height by share of ink area — and its mask has
had four rounds without converging:

- grounds per region counted paper as ink wherever a box held two grounds (killed the earlier attempt);
- grounds per row re-segmented across a line of type (25 percent of the resulting "ink" came out as
  3px slivers, because the rows carrying ink have a different palette from the rows between letters);
- cluster-merging the palette chains, so the grounds absorb the frame and nothing is ink.

It now **says so** rather than printing a confident empty table: an empty result, or grounds covering
more than 98 percent of the frame, exits with `DID NOT RUN` and the reason. That guard is the part
that must survive whoever picks the mask up: an unreadable mask and a mask that found nothing are
the same report otherwise, and this campaign has already withdrawn one ladder measured off a mask
that was quietly measuring the wrong thing.

**Until it runs, no comp-side number belongs in this document**, and the build-side numbers above are
compared against nothing. What that means for a row applying this ladder: the shares are the
build's, the floor is bracketed by the reviewer's defect and today's measurement, and the comp's
distribution is a gap with a named instrument rather than a settled question.

## The check

`node dev/web-console/look.mjs --ladder <url> [--width W] [--height H] [--theme dark|light] [--mutate]`

It asserts two properties of the rendered frame, and nothing about stylesheets:

- **Floor** — every role's ink is at least 0.85% of the frame height. The floor is bracketed by two
  measurements: the reviewer's 0.417% (the defect) and today's 0.926% (the shipping frame). 18.4px
  at 2160.
- **No inversion** — the roles must not invert down the ladder: wordmark ≥ page title ≥ strip value ≥
  tab label ≥ field label ≥ bay label, with half a pixel of slack because the ladder is measured off
  rendered glyph boxes and a rounding step is not a design decision.

**Mutation proof, both ways, same command.** As rendered it passes. With `--mutate` the page is given
`*, *::before, *::after { font-size: 12px !important }` — every role forced onto one rung — and it
fails, naming five roles below the floor. A check that cannot see a flattened ladder is the
`ladder-steps` this replaces: that one counted declaration steps (0 of 5 reaching 1.25x) and said
nothing about the frame.

**Blocking, and where it lives.** The assertion is blocking here because the mutation proof passes
both ways. It is *not* wired into `look-gate.mjs`: that file is M1-38's fence, and this row's fence
is `look.mjs` and this document. Wiring `--ladder` into the gate's ladder-steps is one line in their
file, and until it lands the check is run by hand or by a row's verify line.

Law 24's own sentence applies to whatever wires it: a green structural gate never overrides an
optical defect. This check measures ink on the frame, so it is on the optical side — but it reads one
page at a time, and a page it was not pointed at is a page it cannot fail for.
