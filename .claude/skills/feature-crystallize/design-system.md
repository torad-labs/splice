# Feature Crystallize — Design System

You are composing a document, not filling a template. Visual weight proportional to finding significance. Reader-facing labels only. Use only components defined in this file — do not invent new CSS classes.

---

## A. Design Principles

1. **Composition over templates.** There is no fixed section order. Shape the document to its content. If one finding dominates, give it a full-width callout. If three assumptions share a pattern, the synthesis callout is more prominent than individual cards. A surprise that changes everything should not be in a 2-column grid alongside minor surprises.

2. **Visual weight = significance.** Important findings get larger containers (`.callout`, `.root-cause-card`, `.antibody-critical`). Minor findings get compact containers (`.finding-item`, list items). The reader should feel the hierarchy before reading a word.

3. **Reader-facing labels only.** Use the translation table in Section E. No engine jargon in HTML output. Engine names remain in SKILL.md, Blocks 1-5, and `output=raw`.

4. **Self-contained HTML.** Every document includes its own CSS inline. No external stylesheets except `/framework/blog-base.css` when explicitly needed. Copy foundation CSS verbatim from Section C.

5. **Mode accent colors.** Each mode has a distinct color identity (Section B). Apply consistently to hero labels, ELBO cards, callout borders, and emphasized blocks.

---

## B. Color Language

### Constants (all modes)

```css
:root {
    --bg: #fafaf8;
    --card: #ffffff;
    --card-border: #e4e2dc;
    --text: #1a1a1a;
    --text-muted: #6b6b6b;
    --accent: #0e8a7e;
    --accent-dim: #b4dfda;
    --amber: #c47a10;
    --amber-dim: #f5e6c8;
    --red-soft: #b84040;
    --red-soft-dim: #e8c4c4;
    --indigo: #4a5dbd;
    --indigo-dim: #d8daf0;
    --survived: #eaf5f3;
}
```

### Mode palettes

| Element | generate | verify | compare | diagnose |
|---------|----------|--------|---------|----------|
| Hero label color | `var(--accent)` teal | `var(--red-soft)` | `var(--indigo)` | `var(--amber)` |
| Callout bg | `#f0f9f8` | `#fdf8f0` | `#f0f0fa` | `#fdf8f0` |
| Callout border | `var(--accent)` | `var(--amber)` | `var(--indigo)` | `var(--amber)` |
| Callout strong | `var(--accent)` | `var(--amber)` | `var(--indigo)` | `var(--amber)` |
| ELBO bg | `#f0f9f8` | `#fdf8f0` | `#f0f0fa` | `#fdf0f0` |
| ELBO border | `var(--accent-dim)` | `var(--amber-dim)` | `var(--indigo-dim)` | `#e8c4c4` |
| ELBO score color | `var(--accent)` | `var(--amber)` | `var(--indigo)` | `var(--red-soft)` |
| ELBO iter.current bg | `var(--accent-dim)` | `var(--amber-dim)` | `var(--indigo-dim)` | — |
| ELBO iter.current color | `var(--accent)` | `var(--amber)` | `var(--indigo)` | — |
| `.idea-card em` color | `var(--accent)` | `var(--accent)` | `var(--indigo)` | — |

### Fixed accent rules

- **Rejection content** always uses `var(--red-soft)` regardless of mode — hero label, callout border, finding borders, pattern list backgrounds.
- **Quality bar fills:** `.strong` = `var(--accent)`, `.good` = `#3a9e94`, `.adequate` = `var(--amber)`, `.weak` = `var(--red-soft)`.
- **Surprise card badges** always use `var(--amber)`.

---

## C. Foundation CSS

Copy this block verbatim into every output document's `<style>` tag. Then append only the component CSS needed for that specific document from Section D.

```css
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

body {
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    line-height: 1.65;
    -webkit-font-smoothing: antialiased;
}

.container {
    max-width: 820px;
    margin: 0 auto;
    padding: 0 24px;
}

/* HERO */
.hero {
    padding: 80px 0 48px;
    border-bottom: 1px solid var(--card-border);
}
.hero-label {
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 2.5px;
    text-transform: uppercase;
    margin-bottom: 20px;
}
.hero h1 {
    font-size: 38px;
    font-weight: 700;
    line-height: 1.15;
    letter-spacing: -0.5px;
    margin-bottom: 14px;
}
.hero .subtitle {
    font-size: 18px;
    color: var(--text-muted);
    max-width: 580px;
}
.hero-meta {
    display: flex;
    gap: 20px;
    margin-top: 28px;
    flex-wrap: wrap;
}

/* META PILL */
.meta-pill {
    font-size: 12px;
    font-weight: 500;
    color: var(--text-muted);
    padding: 5px 14px;
    border-radius: 100px;
    border: 1px solid var(--card-border);
    background: var(--card);
}
.meta-pill span { color: var(--text); }

/* SECTIONS */
section {
    padding: 56px 0;
    border-bottom: 1px solid var(--card-border);
}
section:last-of-type { border-bottom: none; }
.section-label {
    font-size: 10px;
    font-weight: 600;
    letter-spacing: 2.5px;
    text-transform: uppercase;
    color: var(--text-muted);
    margin-bottom: 12px;
}
section h2 {
    font-size: 24px;
    font-weight: 700;
    margin-bottom: 20px;
    letter-spacing: -0.3px;
}
section h3 {
    font-size: 18px;
    font-weight: 600;
    margin-top: 36px;
    margin-bottom: 16px;
    color: var(--text);
}
section p { color: var(--text); margin-bottom: 16px; }
section p.muted { color: var(--text-muted); font-size: 15px; }

/* FOOTER */
.page-footer {
    padding: 40px 0 60px;
    text-align: center;
    color: var(--text-muted);
    font-size: 12px;
}
.page-footer a { color: var(--accent); text-decoration: none; }

/* NAV BACK (product sub-documents) */
.nav-back {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    margin-top: 32px;
    font-size: 13px;
    font-weight: 600;
    color: var(--text-muted);
    text-decoration: none;
    transition: color 0.15s ease;
}
.nav-back:hover { color: var(--text); }
.nav-back .arrow { font-size: 16px; }

/* RESPONSIVE */
@media (max-width: 640px) {
    .hero h1 { font-size: 28px; }
    .hero { padding: 48px 0 36px; }
    section { padding: 40px 0; }
}
```

---

## D. Component Catalog

Each component below shows its CSS and HTML example. Include only the CSS for components you actually use in a given document. Responsive overrides for each component go inside the `@media (max-width: 640px)` block.

---

### Content Containers

#### `.idea-card`

Prominent quoted block for the starting point, scope, or decision context. Mode accent on `em`.

```css
.idea-card {
    background: var(--card);
    border: 1px solid var(--card-border);
    border-radius: 12px;
    padding: 28px 32px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.idea-card p {
    font-size: 17px;
    line-height: 1.7;
    margin-bottom: 0;
}
.idea-card em { font-style: normal; font-weight: 600; }
/* Set .idea-card em color to mode accent */
```

```html
<div class="idea-card">
    <p>The original idea with <em>key phrases</em> emphasized.</p>
</div>
```

#### `.callout`

Accent-bordered highlight for synthesis statements, core insights, or pattern summaries. Background and border color adapt per mode (Section B).

```css
.callout {
    padding: 20px 24px;
    border-left: 3px solid; /* set border-color per mode */
    border-radius: 0 8px 8px 0;
    margin: 20px 0;
    font-size: 15px;
    line-height: 1.6;
}
/* Set .callout background and .callout strong color per mode palette */
```

```html
<div class="callout">
    <strong>Key insight:</strong> The synthesis statement.
</div>
```

#### `.failure-card`

Red-bordered block for failure descriptions (diagnose mode). Always uses `var(--red-soft)`.

```css
.failure-card {
    background: var(--card);
    border: 1px solid var(--card-border);
    border-left: 4px solid var(--red-soft);
    border-radius: 0 12px 12px 0;
    padding: 28px 32px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.failure-card p {
    font-size: 17px;
    line-height: 1.7;
    margin-bottom: 0;
}
.failure-card em { color: var(--red-soft); font-style: normal; font-weight: 600; }
```

```html
<div class="failure-card">
    <p>What went wrong with <em>key failure points</em> emphasized.</p>
</div>
```

---

### Cards

#### `.finding-item`

Standard card for individual findings, anti-patterns, divergence fixes, or boundaries. Default border is neutral. For rejection contexts, add `border-left: 3px solid var(--red-soft); border-radius: 0 10px 10px 0;`.

```css
.finding-item {
    padding: 22px 26px;
    background: var(--card);
    border: 1px solid var(--card-border);
    border-radius: 10px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.finding-item h4 {
    font-size: 15px;
    font-weight: 600;
    margin-bottom: 8px;
}
.finding-item p {
    font-size: 14px;
    color: var(--text-muted);
    margin: 0;
    line-height: 1.55;
}
```

```html
<div class="finding-item">
    <h4>Finding title</h4>
    <p>Rich description with <strong>key insight</strong> bolded.</p>
</div>
```

Wrap multiple in a vertical flex container:
```css
.findings-grid {
    display: flex;
    flex-direction: column;
    gap: 16px;
    margin-top: 20px;
}
```

#### `.assumption-card`

Side-by-side "assumed → found" card. Always uses red-soft for assumed side, accent for found side.

```css
.assumptions-grid {
    display: flex;
    flex-direction: column;
    gap: 16px;
}
.assumption-card {
    display: grid;
    grid-template-columns: 1fr auto 1fr;
    gap: 0;
    background: var(--card);
    border: 1px solid var(--card-border);
    border-radius: 12px;
    overflow: hidden;
    box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.assumed, .found { padding: 22px 24px; }
.assumed { background: #fdf6f6; }
.found { background: #f2faf9; }
.assumed-label, .found-label {
    font-size: 10px;
    font-weight: 600;
    letter-spacing: 2px;
    text-transform: uppercase;
    margin-bottom: 8px;
}
.assumed-label { color: var(--red-soft); }
.found-label { color: var(--accent); }
.assumed p {
    color: var(--text-muted);
    text-decoration: line-through;
    text-decoration-color: rgba(196, 92, 92, 0.4);
    font-size: 15px;
    margin: 0;
}
.found p { color: var(--text); font-size: 15px; margin: 0; }
.arrow-divider {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 48px;
    color: var(--card-border);
    font-size: 18px;
    background: var(--card);
}
```

Responsive:
```css
@media (max-width: 640px) {
    .assumption-card {
        grid-template-columns: 1fr;
        grid-template-rows: auto auto auto;
    }
    .arrow-divider {
        width: 100%;
        height: 32px;
        font-size: 14px;
        transform: rotate(90deg);
    }
}
```

```html
<div class="assumption-card">
    <div class="assumed">
        <div class="assumed-label">We assumed</div>
        <p>The default assumption</p>
    </div>
    <div class="arrow-divider">&rarr;</div>
    <div class="found">
        <div class="found-label">Evidence showed</div>
        <p>What the evidence actually supported</p>
    </div>
</div>
```

#### `.surprise-card`

Numbered badge card for emergent findings. Badge always amber.

```css
.surprise-card {
    padding: 22px;
    background: var(--card);
    border: 1px solid var(--card-border);
    border-radius: 10px;
    position: relative;
    box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.surprise-card::before {
    content: attr(data-num);
    position: absolute;
    top: -10px;
    left: 18px;
    background: var(--amber);
    color: #fff;
    font-size: 11px;
    font-weight: 800;
    padding: 2px 10px;
    border-radius: 100px;
}
.surprise-card h4 {
    font-size: 15px;
    font-weight: 700;
    margin-bottom: 8px;
    color: var(--text);
    line-height: 1.35;
}
.surprise-card p {
    font-size: 13px;
    color: var(--text-muted);
    margin: 0;
    line-height: 1.55;
}
```

Wrap in a grid. **Composition note:** If one surprise dominates, give it full width. Only use 2-column grid when surprises have roughly equal weight.

```css
.surprises-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 14px;
}
@media (max-width: 640px) {
    .surprises-grid { grid-template-columns: 1fr; }
}
```

```html
<div class="surprise-card" data-num="01">
    <h4>Surprise title</h4>
    <p>What was expected vs what emerged.</p>
</div>
```

#### `.component-card`

Small card for architectural components. `.new` border = accent, `.modify` border = amber.

```css
.components-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
    margin-top: 20px;
}
.component-card {
    padding: 16px 18px;
    background: var(--card);
    border: 1px solid var(--card-border);
    border-radius: 8px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.component-card.new { border-left: 3px solid var(--accent); }
.component-card.modify { border-left: 3px solid var(--amber); }
.component-tag {
    font-size: 9px;
    font-weight: 700;
    letter-spacing: 1.5px;
    text-transform: uppercase;
    margin-bottom: 6px;
}
.component-tag.new { color: var(--accent); }
.component-tag.modify { color: var(--amber); }
.component-card h4 { font-size: 14px; font-weight: 600; margin-bottom: 4px; }
.component-card p { font-size: 13px; color: var(--text-muted); margin: 0; line-height: 1.45; }
```

Responsive:
```css
@media (max-width: 640px) {
    .components-grid { grid-template-columns: 1fr; }
}
```

#### `.aligned-card`

Card for things that are working correctly. Always accent-tinted.

```css
.aligned-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
    margin-top: 16px;
}
.aligned-card {
    padding: 18px 20px;
    background: var(--survived);
    border: 1px solid var(--accent-dim);
    border-radius: 10px;
    border-left: 3px solid var(--accent);
    box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.aligned-card h4 { font-size: 14px; font-weight: 600; margin-bottom: 6px; color: var(--text); }
.aligned-card p { font-size: 13px; color: var(--text-muted); margin: 0; line-height: 1.5; }
```

Responsive:
```css
@media (max-width: 640px) {
    .aligned-grid { grid-template-columns: 1fr; }
}
```

#### `.doc-card`

Clickable navigation card for product index document links.

```css
.doc-nav {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 20px;
    margin-top: 24px;
}
.doc-card {
    display: block;
    text-decoration: none;
    color: inherit;
    background: var(--card);
    border: 1px solid var(--card-border);
    border-radius: 12px;
    padding: 28px 28px 24px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.04);
    transition: box-shadow 0.2s ease, transform 0.2s ease;
}
.doc-card:hover {
    box-shadow: 0 4px 16px rgba(0,0,0,0.08);
    transform: translateY(-2px);
}
.doc-card.rejection { border-top: 3px solid var(--red-soft); }
.doc-card.prd { border-top: 3px solid; /* mode accent */ }
.doc-card-label {
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 2px;
    text-transform: uppercase;
    margin-bottom: 10px;
}
.doc-card.rejection .doc-card-label { color: var(--red-soft); }
.doc-card.prd .doc-card-label { /* mode accent color */ }
.doc-card h3 { font-size: 18px; font-weight: 700; margin-bottom: 8px; line-height: 1.3; }
.doc-card p { font-size: 14px; color: var(--text-muted); margin: 0; line-height: 1.55; }
.doc-card .arrow {
    display: inline-block;
    margin-top: 14px;
    font-size: 13px;
    font-weight: 600;
    letter-spacing: 0.5px;
}
.doc-card.rejection .arrow { color: var(--red-soft); }
.doc-card.prd .arrow { /* mode accent color */ }
```

Responsive:
```css
@media (max-width: 640px) {
    .doc-nav { grid-template-columns: 1fr; }
}
```

---

### Comparison

#### `.solution-panel`

Side-by-side solution panels for compare mode. `.panel-a` = accent, `.panel-b` = indigo. In rejection context, use red-soft top border.

```css
.solutions-split {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 20px;
    margin-top: 24px;
}
.solution-panel {
    background: var(--card);
    border: 1px solid var(--card-border);
    border-radius: 12px;
    padding: 24px 28px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.solution-panel.panel-a { border-top: 3px solid var(--accent); }
.solution-panel.panel-b { border-top: 3px solid var(--indigo); }
.solution-panel.winner { border-top: 3px solid var(--accent); }
.solution-tag {
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 2px;
    text-transform: uppercase;
    margin-bottom: 10px;
}
.solution-tag.tag-a { color: var(--accent); }
.solution-tag.tag-b { color: var(--indigo); }
.solution-panel h4 { font-size: 17px; font-weight: 700; margin-bottom: 12px; }
.solution-panel p { font-size: 14px; color: var(--text-muted); margin: 0 0 10px; line-height: 1.55; }
.solution-panel p:last-child { margin-bottom: 0; }
.solution-panel .uncertainty {
    margin-top: 14px;
    padding-top: 14px;
    border-top: 1px solid var(--card-border);
}
.uncertainty-label {
    font-size: 10px;
    font-weight: 600;
    letter-spacing: 2px;
    text-transform: uppercase;
    color: var(--text-muted);
    margin-bottom: 6px;
}
```

Responsive:
```css
@media (max-width: 640px) {
    .solutions-split { grid-template-columns: 1fr; }
}
```

#### `.divergence-item`

Expected vs actual comparison card with severity pill (verify mode).

```css
.divergence-grid {
    display: flex;
    flex-direction: column;
    gap: 24px;
    margin-top: 24px;
}
.divergence-item {
    background: var(--card);
    border: 1px solid var(--card-border);
    border-radius: 12px;
    overflow: hidden;
    box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.divergence-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 16px 24px 12px;
    border-bottom: 1px solid var(--card-border);
}
.divergence-header h4 { font-size: 16px; font-weight: 700; margin: 0; }
.divergence-body {
    display: grid;
    grid-template-columns: 1fr auto 1fr;
}
.expected-side { padding: 18px 22px; background: #f2faf9; }
.actual-side { padding: 18px 22px; background: #fdf6f6; }
.expected-label, .actual-label {
    font-size: 10px;
    font-weight: 600;
    letter-spacing: 2px;
    text-transform: uppercase;
    margin-bottom: 8px;
}
.expected-label { color: var(--accent); }
.actual-label { color: var(--red-soft); }
.expected-side p, .actual-side p { font-size: 14px; color: var(--text); margin: 0; line-height: 1.55; }
.file-ref {
    padding: 10px 24px;
    font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
    font-size: 12px;
    color: var(--text-muted);
    border-top: 1px solid var(--card-border);
    background: #fafaf8;
}
```

Responsive:
```css
@media (max-width: 640px) {
    .divergence-body { grid-template-columns: 1fr; }
    .divergence-body .arrow-divider {
        width: 100%;
        height: 32px;
        transform: rotate(90deg);
    }
}
```

#### `.antibody-row`

Failure → prevention comparison row inside `.antibody-critical` block.

```css
.antibody-grid {
    display: flex;
    flex-direction: column;
    gap: 12px;
}
.antibody-row {
    display: grid;
    grid-template-columns: 1fr auto 1fr;
    gap: 0;
    background: var(--survived);
    border: 1px solid var(--accent-dim);
    border-radius: 10px;
    overflow: hidden;
}
.ab-failure-side { padding: 16px 20px; background: #f5eaea; }
.ab-prevention-side { padding: 16px 20px; background: var(--survived); }
.ab-failure-label, .ab-prevention-label {
    font-size: 9px;
    font-weight: 700;
    letter-spacing: 2px;
    text-transform: uppercase;
    margin-bottom: 6px;
}
.ab-failure-label { color: var(--red-soft); }
.ab-prevention-label { color: var(--accent); }
.ab-failure-side p, .ab-prevention-side p { font-size: 14px; margin: 0; line-height: 1.5; }
.ab-arrow {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 44px;
    color: var(--accent);
    font-size: 16px;
    background: var(--card);
}
```

Responsive:
```css
@media (max-width: 640px) {
    .antibody-row { grid-template-columns: 1fr; }
    .ab-arrow {
        width: 100%;
        height: 32px;
        transform: rotate(90deg);
    }
}
```

---

### Emphasized Blocks

#### `.root-cause-card`

Prominent card for root cause explanations. In diagnose single-document, uses amber. In rejection documents, uses red-soft.

```css
/* Diagnose mode (amber accent) */
.root-cause-card {
    margin-top: 28px;
    padding: 32px 36px;
    border-left: 5px solid;
    border-radius: 0 12px 12px 0;
}
.root-cause-card .rc-label {
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 2.5px;
    text-transform: uppercase;
    margin-bottom: 14px;
}
.root-cause-card h3 { font-size: 20px; font-weight: 700; margin: 0 0 14px; color: var(--text); }
.root-cause-card p { font-size: 16px; color: var(--text); margin: 0 0 12px; line-height: 1.65; }
.root-cause-card p:last-child { margin-bottom: 0; }
```

Context-specific styles:
- Diagnose single-doc: `background: #fdf8f0; border-color: var(--amber); border: 2px solid var(--amber-dim);` `.rc-label color: var(--amber);`
- Rejection doc: `background: #fdf6f6; border-color: var(--red-soft); border: 2px solid var(--red-soft-dim);` `.rc-label color: var(--red-soft);`

#### `.antibody-critical`

Visually dominant container for critical immune memory (diagnose mode).

```css
.antibody-critical {
    margin-top: 28px;
    padding: 32px 36px;
    background: var(--card);
    border: 2px solid var(--accent-dim);
    border-radius: 12px;
    box-shadow: 0 4px 16px rgba(14, 138, 126, 0.1);
}
.antibody-critical .ab-label {
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 2.5px;
    text-transform: uppercase;
    color: var(--accent);
    margin-bottom: 16px;
}
.antibody-critical h3 { font-size: 20px; font-weight: 700; margin: 0 0 16px; color: var(--text); }
.antibody-critical .immune-memory {
    margin-top: 20px;
    padding-top: 20px;
    border-top: 2px solid var(--accent-dim);
}
.antibody-critical .immune-memory p { font-size: 15px; color: var(--text); margin: 0; line-height: 1.65; }
.antibody-critical .immune-memory strong { color: var(--accent); }
```

#### `.recommendation-card`

Prominent card for comparison winner recommendation (compare mode).

```css
.recommendation-card {
    margin-top: 28px;
    padding: 28px 32px;
    background: var(--card);
    border: 2px solid var(--indigo-dim);
    border-radius: 12px;
    box-shadow: 0 2px 8px rgba(74, 93, 189, 0.08);
}
.recommendation-card .rec-label {
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 2.5px;
    text-transform: uppercase;
    color: var(--indigo);
    margin-bottom: 12px;
}
.recommendation-card h3 { font-size: 20px; font-weight: 700; margin: 0 0 12px; }
.recommendation-card p { font-size: 15px; color: var(--text); margin: 0 0 12px; line-height: 1.6; }
.recommendation-card p:last-child { margin-bottom: 0; }
.recommendation-card .trade-off-note {
    font-size: 14px;
    color: var(--text-muted);
    padding-top: 12px;
    border-top: 1px solid var(--card-border);
}
```

---

### Measurement

#### `.quality-grid` / `.quality-row` / `.quality-bar`

16-dimension quality measurement visualization.

```css
.quality-grid {
    display: flex;
    flex-direction: column;
    gap: 16px;
    margin-top: 8px;
}
.quality-row {
    display: grid;
    grid-template-columns: 140px 1fr 44px;
    align-items: center;
    gap: 16px;
}
.quality-label { font-size: 14px; font-weight: 500; color: var(--text); }
.quality-bar-track {
    height: 8px;
    background: var(--card);
    border-radius: 100px;
    overflow: hidden;
    border: 1px solid var(--card-border);
}
.quality-bar-fill {
    height: 100%;
    border-radius: 100px;
    transition: width 1.2s cubic-bezier(0.22, 1, 0.36, 1);
    width: 0;
}
.quality-bar-fill.strong { background: var(--accent); }
.quality-bar-fill.good { background: #3a9e94; }
.quality-bar-fill.adequate { background: var(--amber); }
.quality-bar-fill.weak { background: var(--red-soft); }
.quality-score {
    font-size: 14px;
    font-weight: 600;
    text-align: right;
    font-variant-numeric: tabular-nums;
}
.quality-score.strong { color: var(--accent); }
.quality-score.good { color: #3a9e94; }
.quality-score.adequate { color: var(--amber); }
.quality-score.weak { color: var(--red-soft); }
```

Score thresholds: `.70+` = strong, `.60-.69` = good, `.45-.59` = adequate, `<.45` = weak.

For diagnose mode with failed column, add a 4th grid column:
```css
.quality-row { grid-template-columns: 140px 1fr 44px 60px; }
```

Responsive:
```css
@media (max-width: 640px) {
    .quality-row { grid-template-columns: 110px 1fr 40px; }
    /* If using 4-column variant: */
    /* .quality-row { grid-template-columns: 110px 1fr 40px 50px; } */
}
```

#### `.elbo-card`

Overall quality score card. Colors adapt per mode (Section B).

```css
.elbo-card {
    margin-top: 28px;
    padding: 20px 24px;
    border-radius: 10px;
    display: flex;
    align-items: center;
    gap: 24px;
}
/* Set background, border (1px solid), and score color per mode palette */
.elbo-score {
    font-size: 40px;
    font-weight: 800;
    line-height: 1;
    font-variant-numeric: tabular-nums;
}
.elbo-detail { flex: 1; }
.elbo-detail p { font-size: 14px; color: var(--text-muted); margin: 0; }
.elbo-detail strong { color: var(--text); }
.elbo-progress {
    display: flex;
    gap: 8px;
    margin-top: 8px;
    align-items: center;
}
.elbo-iter {
    padding: 3px 10px;
    border-radius: 4px;
    font-size: 12px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
}
.elbo-iter.prev { background: #edeceb; color: var(--text-muted); }
/* Set .elbo-iter.current background and color per mode palette */
.elbo-arrow { color: var(--text-muted); font-size: 12px; }
```

Product index variant with dimension pills:
```css
.elbo-dims {
    display: flex;
    gap: 16px;
    margin-top: 10px;
    flex-wrap: wrap;
}
.elbo-dim {
    font-size: 12px;
    font-weight: 600;
    padding: 3px 12px;
    border-radius: 100px;
    font-variant-numeric: tabular-nums;
}
.elbo-dim.top { background: var(--survived); color: var(--accent); }
.elbo-dim.bottom { background: #fdf6f6; color: var(--red-soft); }
```

#### `.dimension-grid` (compare mode)

Side-by-side dimension bars for comparing two solutions.

```css
.dimension-grid {
    display: flex;
    flex-direction: column;
    gap: 20px;
    margin-top: 24px;
}
.dimension-row-simple {
    display: grid;
    grid-template-columns: 140px 1fr 60px;
    align-items: center;
    gap: 12px;
}
.dimension-label { font-size: 14px; font-weight: 500; color: var(--text); }
.dim-bars-stacked {
    display: flex;
    flex-direction: column;
    gap: 4px;
}
.dim-bar-track {
    height: 8px;
    background: var(--card);
    border-radius: 100px;
    overflow: hidden;
    border: 1px solid var(--card-border);
}
.dim-bar-fill-a {
    height: 100%;
    border-radius: 100px;
    background: var(--accent);
    transition: width 1.2s cubic-bezier(0.22, 1, 0.36, 1);
    width: 0;
}
.dim-bar-fill-b {
    height: 100%;
    border-radius: 100px;
    background: var(--indigo);
    transition: width 1.2s cubic-bezier(0.22, 1, 0.36, 1);
    width: 0;
}
.dim-score {
    font-size: 13px;
    font-weight: 600;
    text-align: center;
    font-variant-numeric: tabular-nums;
}
.dim-score.score-a { color: var(--accent); }
.dim-score.score-b { color: var(--indigo); }
```

Responsive:
```css
@media (max-width: 640px) {
    .dimension-row-simple { grid-template-columns: 1fr; gap: 6px; }
}
```

#### `.failed-pill`

Small pill indicating pass/fail status (diagnose mode quality grid).

```css
.failed-pill {
    font-size: 9px;
    font-weight: 700;
    letter-spacing: 1px;
    text-transform: uppercase;
    padding: 2px 8px;
    border-radius: 100px;
    text-align: center;
}
.failed-pill.yes { background: #fce8e8; color: var(--red-soft); }
.failed-pill.no { background: #edeceb; color: var(--text-muted); }
```

---

### Sequence

#### `.flow-steps` / `.flow-step` / `.flow-num` / `.flow-connector`

Numbered sequential steps for build sequences, data flows, fix plans.

```css
.flow-steps {
    display: flex;
    flex-direction: column;
    gap: 0;
    margin-top: 28px;
}
.flow-step {
    display: flex;
    align-items: flex-start;
    gap: 16px;
    padding: 14px 0;
}
.flow-num {
    flex: 0 0 32px;
    height: 32px;
    border-radius: 50%;
    background: var(--card);
    border: 1px solid var(--card-border);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 13px;
    font-weight: 700;
    color: var(--accent);
}
.flow-step p { font-size: 15px; margin: 0; padding-top: 4px; }
.flow-step p strong { color: var(--text); }
.flow-connector {
    width: 1px;
    height: 12px;
    background: var(--card-border);
    margin-left: 16px;
}
```

---

### Lists

#### `.patterns-list`

Shield-marked list for immune memory / pattern deposits. Default uses accent tint. In rejection documents, use red-soft tint: `background: #fdf6f6; border: 1px solid var(--red-soft-dim);` with `.shield { color: var(--red-soft); }`.

```css
.patterns-list {
    list-style: none;
    display: flex;
    flex-direction: column;
    gap: 10px;
}
.patterns-list li {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding: 14px 18px;
    background: #f2faf9;
    border: 1px solid var(--accent-dim);
    border-radius: 8px;
    font-size: 14px;
    color: var(--text);
    line-height: 1.5;
}
.patterns-list .shield {
    flex: 0 0 auto;
    color: var(--accent);
    font-size: 16px;
    margin-top: 1px;
}
```

```html
<ul class="patterns-list">
    <li>
        <span class="shield">&#9681;</span>
        <span><strong>Pattern name.</strong> Description.</span>
    </li>
</ul>
```

#### `.questions-list`

Question-mark list for open questions / honest limitations.

```css
.questions-list {
    list-style: none;
    display: flex;
    flex-direction: column;
    gap: 12px;
}
.questions-list li {
    padding: 16px 20px 16px 36px;
    background: var(--card);
    border: 1px solid var(--card-border);
    border-radius: 8px;
    font-size: 15px;
    color: var(--text);
    line-height: 1.5;
    position: relative;
}
.questions-list li::before {
    content: '?';
    position: absolute;
    left: 14px;
    top: 16px;
    color: var(--amber);
    font-weight: 800;
    font-size: 15px;
}
```

#### `.trade-off-list`

Diamond-marked list for trade-off catalogues (compare mode).

```css
.trade-off-list {
    list-style: none;
    display: flex;
    flex-direction: column;
    gap: 10px;
    margin-top: 16px;
}
.trade-off-list li {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding: 14px 18px;
    background: #f0f0fa;
    border: 1px solid var(--indigo-dim);
    border-radius: 8px;
    font-size: 14px;
    color: var(--text);
    line-height: 1.5;
}
.trade-off-list .marker {
    flex: 0 0 auto;
    color: var(--indigo);
    font-size: 16px;
    margin-top: 1px;
}
```

---

### Tables

#### `.summary-table`

Generic data table for medium-severity items, winning dimensions, etc.

```css
.summary-table {
    width: 100%;
    border-collapse: collapse;
    margin-top: 24px;
    font-size: 14px;
}
.summary-table th {
    text-align: left;
    font-size: 10px;
    font-weight: 600;
    letter-spacing: 2px;
    text-transform: uppercase;
    color: var(--text-muted);
    padding: 12px;
    border-bottom: 2px solid var(--card-border);
}
.summary-table td {
    padding: 14px 12px;
    border-bottom: 1px solid var(--card-border);
    vertical-align: top;
    line-height: 1.5;
}
.summary-table td strong { font-weight: 600; }
.summary-table .file-ref-inline {
    display: block;
    font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
    font-size: 11px;
    color: var(--text-muted);
    margin-top: 4px;
}
```

Responsive:
```css
@media (max-width: 640px) {
    .summary-table { font-size: 13px; }
    .summary-table th, .summary-table td { padding: 10px 8px; }
}
```

#### `.connection-table`

Architectural connection strength table (generate mode).

```css
.connection-table {
    width: 100%;
    border-collapse: collapse;
    margin-top: 20px;
    font-size: 14px;
}
.connection-table th {
    text-align: left;
    font-size: 10px;
    font-weight: 600;
    letter-spacing: 2px;
    text-transform: uppercase;
    color: var(--text-muted);
    padding: 10px 12px;
    border-bottom: 2px solid var(--card-border);
}
.connection-table td {
    padding: 12px;
    border-bottom: 1px solid var(--card-border);
    vertical-align: top;
    line-height: 1.5;
}
.connection-table td strong { font-weight: 600; }
```

Responsive:
```css
@media (max-width: 640px) {
    .connection-table { font-size: 13px; }
}
```

#### `.counterfactual-table`

Expected vs actual stage comparison (diagnose mode).

```css
.counterfactual-table {
    width: 100%;
    border-collapse: collapse;
    margin-top: 20px;
    font-size: 14px;
}
.counterfactual-table th {
    text-align: left;
    font-size: 10px;
    font-weight: 600;
    letter-spacing: 2px;
    text-transform: uppercase;
    color: var(--text-muted);
    padding: 10px 12px;
    border-bottom: 2px solid var(--card-border);
}
.counterfactual-table td {
    padding: 12px;
    border-bottom: 1px solid var(--card-border);
    vertical-align: top;
    line-height: 1.5;
}
.counterfactual-table .stage-label { font-weight: 600; color: var(--text); }
.counterfactual-table .expected-text { color: var(--accent); }
.counterfactual-table .actual-text { color: var(--red-soft); }
.counterfactual-table .diverge-text { font-size: 13px; color: var(--amber); font-weight: 500; }
```

Responsive:
```css
@media (max-width: 640px) {
    .counterfactual-table { font-size: 13px; }
}
```

#### `.clarity-table`

Clarity assessment table with status pills (verify mode).

```css
.clarity-table {
    width: 100%;
    border-collapse: collapse;
    margin-top: 20px;
    font-size: 14px;
}
.clarity-table th {
    text-align: left;
    font-size: 10px;
    font-weight: 600;
    letter-spacing: 2px;
    text-transform: uppercase;
    color: var(--text-muted);
    padding: 10px 12px;
    border-bottom: 2px solid var(--card-border);
}
.clarity-table td {
    padding: 12px;
    border-bottom: 1px solid var(--card-border);
    vertical-align: top;
    line-height: 1.5;
}
```

---

### Indicators

#### `.sev-pill`

Severity indicator for divergence items.

```css
.sev-pill {
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 1px;
    text-transform: uppercase;
    padding: 3px 10px;
    border-radius: 100px;
    white-space: nowrap;
    display: inline-block;
}
.sev-pill.high { background: #fce8e8; color: var(--red-soft); }
.sev-pill.medium { background: var(--amber-dim); color: var(--amber); }
```

#### `.clarity-pill`

Clarity status indicator for verify mode.

```css
.clarity-pill {
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 1px;
    text-transform: uppercase;
    padding: 3px 10px;
    border-radius: 100px;
    white-space: nowrap;
    display: inline-block;
}
.clarity-pill.clear { background: #eaf5f3; color: var(--accent); }
.clarity-pill.absent { background: #fce8e8; color: var(--red-soft); }
.clarity-pill.ambiguous { background: var(--amber-dim); color: var(--amber); }
.clarity-pill.mixed { background: #f0eaef; color: #8a5070; }
```

---

## E. Dimension Label Translation

Use reader-facing labels in all HTML and product output. Engine names remain in SKILL.md, Blocks 1-5, and `output=raw`.

### Quality dimensions

| Engine Name | Reader Label | Short Description |
|---|---|---|
| Resonance | Coherence | Theme consistency across angles |
| Diffusion | Spread | Architectural surface area |
| Gradient | Complexity flow | Complexity distribution |
| Annealing | Exploration | Solution space coverage |
| Entropy | Density | Information per unit structure |
| Phase transition | Readiness | Commitment barrier status |
| Lyapunov | Convergence | Iteration quality trajectory |
| Valence | Signal | Process-level felt quality |
| Collapse | Decisiveness | Deliberation value exhaustion |
| Delta | Contamination | Training-evidence divergence |
| g_trained | Bias | Residual training distortion |
| Antibody | Pattern memory | Immune deposit strength |
| Fascia | Integration | Connection to existing architecture |
| Uncertainty | Constraints | Tightness for implementation |
| KL divergence | Feasibility | Gap between desired and possible |
| Jensen bound | Lower bound | Conservative quality estimate |

### Section-level translations

| Engine Term | Reader Label |
|---|---|
| Immune Memory | Patterns Catalogued |
| Antibody Deposit | Pattern Memory |
| Training pull | Default assumption |
| ELBO | Overall quality score |
| Antibody deposit (section heading) | Patterns catalogued |

---

## F. Document Purpose Guidelines

Each document should accomplish a specific goal. The sections and components you select should serve that goal — not fill a predetermined skeleton.

### Single-document (`output=html`)

**Goal:** Orient the reader, deliver findings by significance, show where defaults were wrong, present the plan, provide honest measurement, catalogue patterns, surface surprises.

The document flows from context → discovery → evidence → plan → confidence → memory. But within that flow, proportion each section to the weight of its content. A section with one minor finding gets a single `.finding-item`. A section with one paradigm-shifting discovery gets a `.callout` or a full `.root-cause-card`.

### Product index (`output=product`, index.html)

**Goal:** Scannable in 30 seconds. Hub with navigation to companion documents.

Contains: hero, document navigation cards (`.doc-card`), executive summary (2-3 paragraphs synthesizing both pools), ELBO snapshot with top/bottom dimension pills, 2-3 key patterns. No deep content — that lives in the companion documents.

### Product rejection (`output=product`, rejection.html)

**Goal:** Story of boundaries. What NOT to do and why.

Always red-soft accent. The content varies by mode:
- **generate:** assumptions overturned, anti-patterns, boundaries, drift patterns
- **verify:** divergences, drift patterns, misaligned areas, what to stop
- **compare:** losing solution, why it lost, trade-offs accepted, rejected patterns
- **diagnose:** the failure, failed dimensions, root cause, anti-patterns to prevent

Include only the sections that have substantial content for the current mode.

### Product PRD (`output=product`, prd.html)

**Goal:** Story of what to build. Full confidence data. Build sequence. Pattern memory.

Uses mode accent color. The content varies by mode:
- **generate:** architectural center, components, data flow, connections, full quality profile, open questions, surprises, patterns
- **verify:** what's aligned (keep), what diverges (fix), fix sequence, full quality profile, honest limitations, patterns
- **compare:** winning solution, winning dimensions, implementation plan, full quality profile, surprises, patterns
- **diagnose:** counterfactual, prevention architecture, antibody deposit (CRITICAL), full quality profile, surprises

Include only the sections that have substantial content for the current mode.

### Mode-adaptive titles

| Mode | Rejection title | PRD title | Index doc-card labels |
|------|----------------|-----------|----------------------|
| generate | What NOT to Build | What to Build | "What NOT to Build" / "What to Build" |
| verify | What to Stop Doing | Fix Plan | "What to Stop Doing" / "Fix Plan" |
| compare | What We Rejected | What We Chose | "What We Rejected" / "What We Chose" |
| diagnose | What Failed | What to Build Instead | "What Failed" / "What to Build Instead" |

---

## G. Composition Rules

1. Every document starts with `.hero` (label, h1, subtitle, meta-pills).
2. Every section uses `.section-label` + `h2` pattern.
3. Quality measurement appears in every document (full 16-dimension in single-doc and PRD; snapshot in product index).
4. Patterns catalogued appears in every document.
5. Color system is absolute — no hex values outside the palette defined in Section B. Only exception: the specific tint values documented per component (e.g., `#f2faf9`, `#fdf6f6`).
6. Responsive breakpoint at 640px. Include responsive overrides for every multi-column component used.
7. Include the IntersectionObserver script (Section H) when quality bars or dimension bars exist.
8. Rejection content always uses red-soft accent.
9. Footer with Torad branding and navigation links.
10. Maximum 3 heading levels (h1/h2/h3). No h4 except inside card components.

---

## H. Script Block

Include this script at the end of `<body>` when the document contains `.quality-grid` or `.dimension-grid` components. It animates the quality bars on scroll.

```html
<script>
    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                const bars = entry.target.querySelectorAll('[data-width]');
                bars.forEach((bar, i) => {
                    setTimeout(() => {
                        bar.style.width = bar.dataset.width + '%';
                    }, i * 80);
                });
                observer.unobserve(entry.target);
            }
        });
    }, { threshold: 0.3 });

    document.querySelectorAll('.quality-grid, .dimension-grid').forEach(grid => {
        observer.observe(grid);
    });
</script>
```
