# type-spec — what the comp says the type is, and what it covers

Row M1-26 (and the half of M1-22 that survives its withdrawn premise). This is the document the row
that applies a ladder is built and graded from, so every entry carries its measurement and the share
of the comp's ink it speaks for. **An entry without a measurement is worth less than no entry.**

## 1. The frame is part of the type (M1-26)

The ladder and the space scale are in `rem`; `app.css` derives the root as
`max(16px, 1.0417vw)` — 16px per 1536px of frame — so the console at any viewport is the
1536×1024 render **scaled**. Measured on the hero, four frames, one browser session
(`.dev/web-console/scale.mjs`):

| frame | root | strip label | bay | text % of frame | gap % |
|---|---|---|---|---|---|
| 1536×1024 | 16px | 14px | 430 | 15.30 | 5.72 |
| 1920×1080 | 20px | 17.5px | 538 | 18.04 | 6.31 |
| 2560×1440 | 26.7px | 23.3px | 717 | 17.81 | 6.34 |
| 3840×2160 | 40px | **35px** | 1075 | **17.97** | 6.32 |

Before the fix the label was 14px at every frame and text fell to 2.90% at 3840. Flat within 0.25
points across 1920/2560/3840 afterwards, and 1536 byte-identical to the before run.

**Linear, no cap** (orchestrator's ruling, 2026-09-18). At 3840 on a 32-inch 4K panel (0.184mm per
px) 35px is a 6.4mm em, a 3.4mm x-height: 0.30° or 18 arcminutes at 650mm, inside the 16–20 arcminute
band. A cap would have to be argued *down* from what the geometry asks, with no measurement behind
it — a fourth authored number. The floor is the comp frame itself, and that is its provenance.

**Not everything wanted to scale with the face.** The hand-off lift (three tilted slabs) scaled with
the type when it should be a comp-frame constant — routed to M1-24. Any future measurement of this
row should treat "did it scale?" as a question per object, not a property of the world.

## 2. What the comp actually measures, and how little of it that is

The scaffold `layout.css` declares 28 regions and **sizes ten**. Those ten carry **2.6% of the
comp's ink** (measured by masking each region's text against its own modal ground). The other 97.4%
— every bay body, every strip, the rail labels, the hand-off — has **no font size in the scaffold at
all**. So anything below is the comp's **chrome**, not its body; a claim about the comp's *type
ladder* cannot be made from these numbers, and the earlier sentence "the comp runs 14…23px" was
withdrawn for exactly that reason.

Three of the ten sized regions are the 4px and 5px rows where the matcher resolved no glyphs
(72% of the sized sliver between them). **The seven real rows** — cap heights are the measurement;
the font sizes are derived from them:

| region | cap | font | role |
|---|---|---|---|
| `wordmark` | 15.90 | 23 | the wordmark |
| `health` | 12.40 | 18 | rule: daemon health |
| `clocks` | 12.00 | 17 | rule: local/utc |
| `no-window` | 12.00 | 17 | rule: no-window count |
| `nearest-window` | 11.70 | 17 | rule: nearest window |
| `bay-deepseek-label` | 10.9 | 16 | bay label |
| `chat-label` | 9.8 | 14 | panel label |

`bay-claude-label` is the **same role as `bay-deepseek-label`, sized twice in the scaffold, and its
twin is one of the junk rows** — two values for one role, one real and one noise. Take the real one.

The build currently renders `clocks`, `nearest-window` and `no-window` at 20px where the comp
measured 17, and `health` at 16 where the comp measured 18. The ladder has no 17, so the answer is
not in the rule's own file (M1-30) — it is this table.

## 3. The body, which has never been measured

The census that matters for the operator's complaint is the body, and **no measurement of the comp's
body type exists yet**. What is known, from M1-12's pixel scans of the strips:

- the strip cell's label line is a **7px x-height** band, the value line an **8–10px** band, with the
  divider at **y+21** from the strip's top and the module **62px** at the comp's frame;
- the rail's label lines sit at a **31px pitch** (M1-22's band run, and consistent with the 31–32px
  rack slot rule M1-12 measured).

Those are bands, not font sizes: converting them needs a face ratio, and the same conversion is what
produced the 4px junk rows. The next step is a per-line mask (ground taken band by band, not per
region) so each body role's size comes from its own glyphs — and every number it produces must carry
its coverage beside it.

## 4. Two censuses, two absolute numbers, one ratio

M1-16 measured the hero's text share as 23.08% at 1536 and 4.78% at 3840; M1-26 measured 15.30% and
2.90%. **The absolute numbers are not comparable** — the tree moved between the runs (M1-17's fonts,
M1-20's pages) and the two censuses count ink differently. **The ratio is comparable, and it matches
to two digits** (4.83× and 5.28×). Neither number is wrong; anyone quoting one as *the* figure is
quoting a different question.
