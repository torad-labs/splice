# The console before the viewport scalar, at the size it is actually used

`hero-3840-before-scalar.png` is the teams hero at 3840x2160, captured 2026-09-18 at the tip
immediately before M1-26 landed. **It cannot be regenerated** — the tree has moved — which is
why it is tracked here rather than left in a scratch directory with the rest of the
regenerable pipeline bytes.

It exists because for twenty-nine rows nobody on this campaign had looked at this console at
the size it is used. Every capture, comp-diff, hero score, zone split, tonal census and
contact sheet was taken at 1536x1024 (the comp's own frame) or 1280x800. The operator's
monitors are 3840x2160. That is the whole explanation for two days in which every instrument
reported a match and the operator reported that it looked disgusting: both were true, about
different pictures.

The mechanism, measured on the live page across four frames in one browser session:

| frame | bay | strip height | strip label | rendered text as share of frame |
|---|---|---|---|---|
| 1536x1024 | 430px | 51.2 | 14px | 15.30% |
| 1920x1080 | 538px | 54.0 | 14px | 11.61% |
| 2560x1440 | 717px | 72.0 | 14px | 6.53% |
| 3840x2160 | 1075px | 108.0 | 14px | **2.90%** |

The layout was in `vw` and the type and spacing were in `px`. Containers tracked the frame —
430 to 1075 is exactly 3840/1536 — and every glyph stayed 14px. Bigger boxes, same type:
"the font size is too small" and "there is no proper spacing" are one defect with one cause.

M1-26 makes the root font size track the frame and moves the ladder and space scale into
`rem`, so the console at any viewport is the 1536 render scaled. The after is in
`review/scale/`.

Read this one beside it before trusting any number either of us produces about how the
console looks.
