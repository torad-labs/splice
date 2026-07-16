# Torad Design System — Carousel Specs

## Source of Truth

The carousel uses the **Torad blog framework** (`framework/`):

- `blog-animations.js` — `setupCanvas()`, `trailClear()`, `palette`, `rgba()`
- `blog-base.css` — CSS variables, typography, color palette
- `blog-components.css` — structural component patterns
- `blog-tempo.css` — pacing and reveal animations

The carousel is a self-contained HTML file, so these functions are
**inlined** — but they come from the framework. Same design language,
same visual identity. Read the framework files before building.

## Dimensions

- 1080 x 1350px per slide (Instagram carousel portrait)
- Single self-contained HTML file, all CSS/JS inline
- N slides stacked vertically for browser preview (count from 9c)

## Typography

Same font stack as blog (`blog-base.css`). Carousel sizes scaled for
1080px fixed width instead of responsive viewport:

```css
.huge {
  font:
    900 100px/1.05 "Fraunces",
    serif;
} /* big numbers, hooks */
.hl {
  font:
    800 64px/1.12 "Fraunces",
    serif;
} /* headlines */
.sh {
  font:
    700 46px/1.2 "Fraunces",
    serif;
} /* subheads */
.bd {
  font:
    400 36px/1.5 "Source Serif 4",
    serif;
} /* body prose */
.st {
  font:
    400 30px "IBM Plex Mono",
    monospace;
  color: var(--cyan);
} /* stats */
.lbl {
  font:
    400 20px "IBM Plex Mono",
    monospace;
  letter-spacing: 0.15em;
  text-transform: uppercase;
} /* labels */
```

## Color Palette

From `blog-base.css` — same semantic colors, same hex values:

```css
/* Backgrounds */
--bg: #08090c;
--bg-raised: #0e1017;
--bg-elevated: #131620;
/* Text */
--text: #c4c8d4;
--text-bright: #e8eaf0;
--text-dim: rgba(196, 200, 212, 0.5);
/* Semantic accents — meaning is fixed across blog AND carousel */
--gold: #d4a853; /* primary insight trail */
--cyan: #4ecdc4; /* technical detail, attribution */
--violet: #9b72cf; /* void, thresholds, discomfort */
--green: #7bc47f; /* positive findings */
--red: #e85d5d; /* warnings, breaks */
```

JS palette object (from `blog-animations.js`):

```js
const palette = {
  gold: { r: 212, g: 168, b: 83 },
  cyan: { r: 78, g: 205, b: 196 },
  violet: { r: 155, g: 114, b: 207 },
  red: { r: 232, g: 93, b: 93 },
  green: { r: 123, g: 196, b: 127 },
  text: { r: 196, g: 200, b: 212 },
  bg: { r: 8, g: 9, b: 12 },
};
```

## Slide Structure Types

**Full content** — label + stats/headline + body + optional quote.
Used for: Cold Open, Near-Miss, Evidence slides, Mechanism, Protocol.

**Bridge** — 2-3 lines of text centered, ANIMATION does the work.
Used between evidence clusters. Reinforces with visuals, not words.
Text at bottom (`justify-content:flex-end; padding-bottom:150px`).

**Pivot** — short dramatic text, centered, emotional turn.
Used for: Kuleshov moment.

**Crossbar** — headline + body revealing the core finding.
Used for: Stained Glass / crossbar insight.

**Matchbook** — meta-reveal + CTA. Self-referential.

## Data Display Patterns

**Stat boxes** (evidence slides):

```css
display: flex;
gap: 30px;
flex-wrap: wrap;
/* Each box: */
background: rgba(COLOR, 0.08);
border: 1px solid rgba(COLOR, 0.2);
border-radius: 8px;
padding: 20px 30px;
text-align: center;
/* Big number in .st, small label in .lbl below */
```

**Protocol list** (barb slide):

```css
border-left: 3px solid var(--gold);
padding-left: 40px;
/* Each item: */
font: 400 28px "IBM Plex Mono";
padding: 16px 0;
border-bottom: 1px solid rgba(212, 168, 83, 0.1);
/* Number span: color: var(--gold); font-size: 22px */
```

## Layered Rendering (per slide)

```
.slide (1080x1350, position:relative, overflow:hidden)
  → canvas (position:absolute, z:0, full-size)
    → .vig (radial-gradient, transparent 30% → rgba(bg,0.6), z:1)
      → .grain (SVG feTurbulence noise, opacity 0.025, z:2)
        → .sc (slide-content, position:relative, z:3, flex column, padding:60px)
```

## Animation Architecture

### Foundation (from blog framework)

Inline these from `blog-animations.js`:

- **`setupCanvas(canvas)`** — DPR-aware canvas init, returns `{ctx, w, h}`
- **`trailClear(ctx, w, h, alpha)`** — alpha-blended trail (NOT clearRect).
  Alpha 0.05 = long ethereal trails. 0.12 = standard. 0.14 = crisp.
- **`palette`** — semantic color object (see above)
- **`rgba(color, alpha)`** — helper for canvas color strings

### Carousel extension: heartbeat particle system

The blog provides the foundation. The carousel extends it with a
heartbeat-synced ambient particle system:

- `createField(n, W, H)` — n particles with radius 1.2-3.7, phase,
  speed 0.2-0.7, drift ±0.25. Every slide gets 15-90 as background.
- `drawField(ctx, particles, t, rgb, W, H)` — heartbeat-synced
  `Math.pow(Math.sin(t*1.2*PI),16)` (sharp 72BPM spike), multi-pass
  rendering (halo gradient + solid core), upward drift, phase entrainment.

### Per-slide IIFE pattern

Each slide gets its own animation loop (NOT a shared loop):

```js
(function () {
  const ctx = setup("cN"),
    W = 1080,
    H = 1350;
  let t = 0;
  const bg = createField(COUNT, W, H);
  // slide-specific state init — generated live from content
  function draw() {
    t += 0.016;
    trailClear(ctx, W, H, ALPHA);
    drawField(ctx, bg, t, "R,G,B", W, H);
    // slide-specific animation — built from THIS slide's concept
    requestAnimationFrame(draw);
  }
  draw();
})();
```

Content-supporting animations are generated live from the content
(Block 9h decision: explanatory or atmospheric). They are NOT
predefined — the AI builds them from the concept on each slide.

## Fixed Elements (every slide)

- `.num` — slide number, top left, IBM Plex Mono 20px, dim
- `.mark` — "torad.ai", bottom right, gold 30% opacity
- Slide 1 only: `.swipe` "swipe →" bottom left
