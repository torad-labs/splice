---
name: splice console
description: A loopback-only operator console for a Claude Code gateway daemon, printed as a rack of strips in a graphite room.
colors:
  room: "#0B0E0E"
  room-deep: "#080A0A"
  bay: "#090D0D"
  ink: "#C9C3B4"
  ink-mute: "#9D9B8E"
  ink-strong: "#EAE8D9"
  strip: "#DED9C6"
  strip-ink: "#141414"
  strip-ink-mute: "#5A5749"
  strip-field: "#E4E0D1"
  strip-field-line: "#A8A392"
  plate: "#C3B6A0"
  plate-line: "#645F50"
  edge-green: "#548630"
  edge-amber: "#9A6E10"
  edge-red: "#E63521"
  edge-grey: "#6E6D68"
  scope: "#090C0C"
  scope-ink: "#EAE8D9"
  focus: "#3F76B4"
  hairline: "rgba(236,234,226,.10)"
  hairline-strong: "rgba(236,234,226,.22)"
typography:
  label:
    fontFamily: "Archivo, 'Helvetica Neue', Arial, sans-serif"
    fontSize: "var(--text-1)..var(--text-6)"
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: "normal"
  figure:
    fontFamily: "Archivo, 'Helvetica Neue', Arial, sans-serif"
    fontSize: "var(--text-1)..var(--text-6)"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
    fontFeature: "font-variant-numeric: tabular-nums"
  code:
    fontFamily: "'JetBrains Mono', ui-monospace, SFMono-Regular, monospace"
    fontSize: "var(--text-2)..var(--text-3)"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
rounded:
  all: "0px"
spacing:
  1: "2px"
  2: "4px"
  3: "8px"
  4: "12px"
  5: "16px"
  6: "24px"
  7: "32px"
  8: "48px"
components:
  key:
    backgroundColor: "{colors.strip}"
    textColor: "{colors.strip-ink}"
    rounded: "{rounded.all}"
    padding: "4px 8px"
  key-hover:
    backgroundColor: "{colors.strip}"
    textColor: "{colors.strip-ink}"
  key-primary:
    backgroundColor: "{colors.strip}"
    textColor: "{colors.strip-ink}"
  key-danger:
    backgroundColor: "{colors.strip}"
    textColor: "{colors.edge-red}"
  strip-field:
    backgroundColor: "{colors.strip-field}"
    textColor: "{colors.strip-ink}"
    rounded: "{rounded.all}"
    padding: "4px 8px 12px"
  bay-head:
    backgroundColor: "{colors.plate}"
    textColor: "{colors.strip-ink}"
    rounded: "{rounded.all}"
    padding: "4px 8px"
---

# Design System: splice console

## 1. Overview

**Creative North Star: "The Strip Bay"**

The console is a rack in a graphite room: opaque paper strips racked in steel bays, each strip a
printed record — an account, a head, a turn — carrying a colored holder edge that flags its
state. Nothing floats and nothing glows. A value is a printed figure in a boxed field, a label is
mute ink above a hairline, and the only saturated color in the frame is the holder edge itself,
because the world's one rule is that attention is a color and everything else is graphite and
paper. PRODUCT.md's own voice — "terse, lowercase, data-first... the loudest thing on screen is a
real warning" — is the strip bay's whole brief: this is an instrument panel for one operator
glancing between terminal sessions, not a marketing surface.

This system explicitly rejects the KPI-tile dashboard: no hero metric rendered at a jump in size,
no sparkline or progress ring standing in for content, no card-and-shadow SaaS vocabulary. It also
retired an entire earlier world (the "Torad plate" system: warm paper, cinnabar/vermilion accent,
serif numerals) — that world is gone from the shipped build; nothing below should be read against
it.

**Key Characteristics:**
- Flat, radius-0 paper-on-graphite construction; depth by tonal plane and hairline, never shadow.
- One functional accent per state (the holder edge's green/amber/red/grey); no second accent color anywhere in the room.
- Two faces only: a label/figure grotesque (Archivo, tabular figures) and a monospace for genuinely code-shaped content (JetBrains Mono).
- A width-driven root scalar (`--root-size: max(16px, 1.0417vw)`, `tokens.css`) ties root font size to the frame's own width, so the console is the comp's render scaled, not the comp's type sitting inside a wider layout. (`light-dark()` is unrelated to this: it themes three surviving legacy color tokens — `--surface-sunk`, `--line-mute`, `--data-neg` — and touches no size.)
- Every spacing and text value is a token (`--space-N`, `--text-N`); raw px in a component rule is a wall violation (`webui-css-tokens-only`).

## 2. Colors

The palette is a near-black graphite room holding pale, warm-cream paper; the only chroma is four holder-edge hues and one focus blue, each reserved for a single functional role.

### Primary
- **Strip Paper** (`#DED9C6` dark / `#F5EDDD` light): the printed record itself — every strip, key and field box is this paper or one step off it. It carries the console's entire content layer.

### Secondary
- **Plate Cream** (`#C3B6A0` dark / `#D6D8D3` light): the bolted head plate over a bay — a third, distinct cream reserved for the rack's own label plate and the shell's modal gate, never for content strips.

### Tertiary (holder-edge accents; each is a state, not a decoration)
- **Edge Green** (`#548630` dark / `#1F882C` light): healthy / ok.
- **Edge Amber** (`#9A6E10`, both themes): degraded / warn.
- **Edge Red** (`#E63521`, both themes): failed / error.
- **Edge Grey** (`#6E6D68`, both themes): neutral / no-state, the pin every strip prints when it carries no holder edge.

### Neutral
- **Graphite Room** (`#0B0E0E` dark / `#E0E2DF` light): the page ground.
- **Room Deep / Bay** (`#080A0A`–`#090D0D` dark / `#ABB0AC` light): the rail and rack floor, a deeper matte than the room so a bay reads as a container.
- **Rule Ink** (`#C9C3B4` dark / `#161A19` light) and **Ink Strong** (`#EAE8D9` dark / `#0A0C0B` light): text printed directly on the room (the top rule, page heads, settings options).
- **Strip Ink** (`#141414`, both themes) and **Strip Ink Mute** (`#5A5749`, both themes): text printed on paper — deliberately near-black in both themes, because paper carries dark ink regardless of which room it sits in.
- **Focus** (`#3F76B4` dark / `#2869D5` light): the one blue in the room, reserved for the focus ring.

### Named Rules
**The One Accent Rule.** Color is never decorative. A holder edge is the only place attention is carried by color, it always ships with a text label (color is never the sole signal), and there is no second accent anywhere else in the frame — a former vermilion/cinnabar accent is "the one pigment the world forbids by name" (`ui.css`).

**The Own-Ground-Own-Ink Rule.** Every plane declares its own ink and a shared label inherits it (`color: inherit`) rather than hard-coding a room ink into a primitive that also prints on paper. Two separate defects shipped from binding a room ink into a shared class that was later reused on paper (`M2R-01`, `M3-04`) — a token is landed against the plane it prints on, never borrowed.

**The Plane-Measured Contrast Rule.** Hairline and mute-ink strengths are allowed to differ between the dark and light themes because each is measured against its own ground to the same target band, not equalized numerically. Splitting the difference between two grounds 200 luminance points apart would either under-articulate one room or over-articulate the other.

**PROVENANCE NOTE — an unapproved value in the shipped system.** The light theme's `--scope` token (`tokens.css`, the `[data-theme="light"]` block, the `SCOPE` group) is measured off `.impeccable/mocks/decision/assigned.png`, a decision-round comp that was never approved as the comp of record. Every other light-theme value in that file is re-derived from the approved comp (`team-board-a.png`) or from `assigned.png`'s own measured planes under an explicit re-derivation ruling (M1-11); `--scope` alone carries no such ruling — it is simply read off the unapproved image. Record this as what it is: a value backed by an unapproved decision-round comp, not by the approved comp, kept because it is the only measurement on hand, not because its provenance was cleared.

## 3. Typography

**Label/Figure Font:** Archivo (variable, wght 100–900, wdth 62–125), with 'Helvetica Neue', Arial, sans-serif as fallback.
**Code Font:** JetBrains Mono (variable), with ui-monospace, SFMono-Regular, monospace as fallback.

**Character:** One compact, condensed grotesque carries every label and every printed value in the room; a genuinely monospace face is reserved for content that is actually monospace-shaped (a TOML body, a log line, a file path). There is no third, larger display face — the system has no hero-metric size jump; a figure is marked by its tabular face, not by a size increase.

### Hierarchy
- **Figure** (400, `inherit` size, tabular-nums): any number or identifier that must line up in a column — strip values, config values, chart bases. One size, set by its container; the design explicitly rejects a hero-metric ladder (value/unit/basis at three different sizes) because it "made the top of every page look broken."
- **Bay Label** (400, `--text-3`/17px at root 16px, `--strip-ink`): the rack's own plate label.
- **Strip Value** (400, `--text-3`, `--strip-ink`): the strip's printed record value.
- **Strip Label / Field Label** (400, `--text-1`/12px, `--strip-ink-mute`): the mute caption above a boxed field's hairline rule.
- **Body** (400, `--text-3`, line-height 1.55, `--ink`): running page text on the room ground.

### Named Rules
**The One Size Rule.** A Figure is one size at any call site; what marks a value as a figure is the tabular numeral face, never a size jump. A page that wants a genuinely large number sets that size on its own container — the primitive itself never does.

**The Regular-Weight Rule.** Strip labels and strip values are weight 400, not 600/bold, measured against the comp's own recorded text regions (which read regular, not semibold, at both 12px and 16px). A bold weight on a narrow strip column costs more truncation than it buys emphasis.

## 4. Elevation

The system is flat by construction: **radius is 0 everywhere** and depth is conveyed by tonal plane and hairline outline, never by `box-shadow` blur/offset. Where the comp shows a three-part paper edge (an outer dark outline, a shaded border, a brighter inner lip) or a recessed bolt hole, those are built from stacked zero-blur `box-shadow` rings and `color-mix()` against the plane they sit on — a printed edge, not a drop shadow. A "shadow" in this system reads as ink, not as light falling on an elevated card.

### Shadow Vocabulary (paper-edge rings, not ambient shadows)
- **Strip Border** (`border: var(--hair) solid color-mix(in srgb, var(--strip) 76%, white)`): the paper's own brighter inner lip.
- **Strip Outline Ring** (`box-shadow: 0 0 0 var(--hair) color-mix(...)`): the paper's shaded mid-tone, one ring out from the border.
- **Strip Room Ring** (`box-shadow: 0 0 0 calc(var(--hair)*2) color-mix(in srgb, var(--bay) …%, black)`): the darkest outline, mixed against the plane (the bay) it is drawn on, so it never needs a per-theme hex.
- **Bay Inset Ring** (`inset 0 0 0 var(--hair) color-mix(in srgb, var(--room) 27%, black)`): the recessed frame of a rack.

### Named Rules
**The Flat-By-Default, Ink-Not-Light Rule.** No radius, no ambient drop shadow anywhere in the room. Where the comp shows depth (a paper edge, a bolt recess, a stile highlight), it is reproduced as stacked hairline rings mixed against the exact plane they sit on, never as a `box-shadow` blur simulating a light source.

## 5. Components

### Buttons (Key)
- **Shape:** radius 0, `var(--hair)` hairline border — "a printed strip that can be pressed."
- **Primary:** same box as the default key; the world has no second accent to promote a button with, so a primary key is the identical printed key with the room's bright ink border (`--ink-strong`).
- **Hover / Focus:** hover lifts only the border color to `--strip-ink` (no fill change, no edge color) so a still-hovering pointer after a click can tell hover from armed; focus is always the world's 2px `--focus` ring at 1–2px offset, on the element itself, never the browser default.
- **Danger / Armed (Confirm):** resting danger is an outlined key in `--data-neg`; armed is visibly more urgent — a filled 16% danger tint plus the same danger border — so a scan of the bench cannot mistake an armed control for an idle one.

### Strip / StripField (the signature component)
The strip is the one row module the whole console is built from: an opaque paper rack row, cut to the width of its own fields (never stretched to its container), carrying a `HolderEdge` mark in a fixed 6ch-plus-mark column at its start. A `StripField` is a boxed cell with a label 4px above a mute hairline and a tabular value 4px below it; its width is a fixed `ch` count resolved against the value's own face, so columns stay aligned strip to strip and never shrink under flex pressure. An unedged strip still prints a small ink pin where its holder-edge mark would be, so every strip — stateful or not — reads as printed paper with a fixed geometry.

### Bay (rack container)
- **Corner Style:** radius 0, two 16px stile rails (a repeating gradient carrying both the rail face and its punched bolt holes), a ribbed unprinted floor at a 31–32px pitch.
- **Background:** `--bay`, one deliberate step below the room so the rack recedes rather than merely differs.
- **Head Plate:** a discrete, centered, paper plate (`--plate`) bolted over the rack, carrying the rack's label, count and actions, with four corner pins and the same three-part paper edge as a strip.
- **Internal Padding:** `--space-7` gap between strips, `--space-7 0 --space-3` rack padding.

### Fields / Inputs (Field, Input)
- **Style:** a printed field box — `--strip-field` paper, `--hair` hairline in `--strip-field-line`, radius 0, tabular figure face for the value.
- **Focus:** the world's 2px `--focus` ring, 1–2px offset — never a glow or a color-shifted border.
- **Error / Disabled:** an invalid field shifts its border to `--strip-ink`, never to a red fill — color is never the sole signal, so the reason is printed in text.

### Choice (select) and Flag (checkbox)
- **Style:** the same printed field box as Input, value left / state word right, no chevron — "the world has no icon set." Options print in flow, as a bay of one-field strips; there is no floating popover layer or z-index stack, because a list printed in flow cannot land off-screen.
- **State:** the chosen option's border goes to `--strip-ink`; the pointer's own hovered position is a separate, lighter border state.

### Navigation (Rail / Rule)
- **Style:** a top rule (5.8% of frame height, type-derived so it scales with the root rather than the viewport) and a left rail (9vw, 118px floor) of address tabs, both drawn on the room. On mobile (≤720px) the rail collapses to a bottom strip of addresses within thumb reach, and the rack rails (crossing hairlines keyed to the tab pitch) stop drawing because the pitch they're keyed to no longer exists.
- **Mobile treatment:** a page's detail column becomes a full-screen swell over the room rather than a side column, arriving with the world's one authored entrance (`--dur-2` fade + translateY), and closes with a printed key shown only in that mode.

### ScopeInset (chart frame)
A recessed, always-dark inset (`--scope`, `#090C0C`/`#1F2422`) with a ruled grid, used for every chart in both themes — the one place the console deliberately does not follow the room/paper split. The approved comp is dark and draws no chart; the light theme's dark inset is read off the decision-round comp `assigned.png`, whose chart insets are dark on paper (see the provenance note at the end of section 2).

## 6. Do's and Don'ts

### Do:
- **Do** keep every color, spacing and type value bound to a token (`--space-1`..`--space-8`, `--text-1`..`--text-6`, the section-1 palette tokens); the `webui-css-tokens-only` wall fails a raw px or hex in a component rule.
- **Do** carry state exclusively through the four holder-edge colors, each always paired with a text label.
- **Do** measure a new plane's ink and border against the exact background it prints on, and record the reading in a comment, the way every existing token in `tokens.css` does.
- **Do** keep radius at 0 and depth as tonal plane plus hairline everywhere.
- **Do** use the tabular figure face for anything that lines up in a column (a number, an id, a config value); use the mute label face for captions; use the monospace face only for genuinely monospace content (TOML, logs, paths).

### Don't:
- **Don't** introduce a second accent color. The room's holder edges and the focus blue are the whole of its chroma; a former vermilion/cinnabar accent is "the one pigment the world forbids by name."
- **Don't** build a hero-metric tile (a value at a jumped-up size over a small unit and basis). PRODUCT.md's anti-goal is explicit: this system rejects "the KPI-tile dashboard of the category."
- **Don't** add a card-and-shadow surface, a sparkline, a progress ring, or a soft-shadowed rounded rectangle standing in for real content — the room has no shadow vocabulary and no radius to support them.
- **Don't** use a dashed-box, centered-text empty state; an empty rack prints as a strip carrying no data, same paper, same hairline, same field origin as a populated one.
- **Don't** reintroduce a floating popover/menu layer with a z-index stack; this world's options print in flow.
- **Don't** invent a kicker or an eyebrow label, a hard-offset neobrutalist shadow, a system display face, or a Unicode/emoji glyph standing in for an icon — none of these appear anywhere in the shipped build, and none should be added to extend the system (see documenter's note below on why they are not canonized as absence-driven rules either).

---

**Known residuals of this shipped build (facts, not rules to inherit):**
1. **The typeface ceiling.** Archivo at its wdth-axis floor still sets roughly 1.25–1.42x the comp's traced width at matching ink height, so board labels print about 20–27% shorter (by ascender/digit height) than the comp, and strip values still elide in narrow columns. This is an open operator decision (campaign follow-up F3: choosing a genuinely narrower face), not a rule this document prescribes going forward.
2. **Authored short column names.** The builder head bay prints short, hand-authored column names (`acct`, `wndw`, `tok out`, `ctx left`) because a column name prints once per rack and the comp's full names (`account`, `window`, `tokens out`, `context left`) overflow that bay's measured columns by 1.3–5.3px in Archivo; the lead bay, whose columns hold them, prints the comp's words. Treat these as accepted shipped labels, not as a naming convention to extend to new columns without re-measuring space.
