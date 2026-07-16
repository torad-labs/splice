# Block 8g: BUILD HTML

**Run this entire phase in thinking space.**

**This is the ONLY phase that produces HTML.**

## Purpose

Render the audited text into the designed structure using the
attention map for semantic emphasis. The HTML file is the final
deliverable. This phase is PURELY MECHANICAL. No creative
decisions. No judgment calls. Every emphasis, every class, every
breakout placement comes from previous phase outputs.

## Prerequisites

ALL previous phase outputs must exist:

- Phase 1 (8a): Inputs document
- Phase 2 (8b): Text narrative + fascia map + breakout map
- Phase 3 (8c): Audited text + verified toroid map
- Phase 4 (8d / THE SKELETON): Structural collapse + jargon table
- Phase 5 (8e / THE EYES): Emphasis map + color arc + callout content + tooltip content
- Phase 6 (8f): Design spec (section map, animation specs, visual flow)

If any is missing, STOP. Go back to the missing phase.

## Steps

### 1. Framework Setup

Use the Torad framework (apps/torad/framework/):

- Link framework CSS (blog-base.css, blog-components.css, blog-tempo.css)
- Link framework JS (blog-animations.js) at bottom of body
- Set up the HTML skeleton with proper meta tags
- Post metadata: title, description, date, author, read time
- OG/social tags

**Font loading:** The framework CSS (`blog-base.css`) handles font loading
via `@import`. Do NOT add a separate `<link>` tag for Google Fonts in the
HTML `<head>`. Duplicate font loading wastes bandwidth and can cause FOUT.

**Required hero structure:**

- `hero-label` — category label (e.g. "Rejection Architecture")
- `h1` — title (with `<em>` for gold emphasis)
- `hero-sub` — one-sentence subtitle (NOT the opening hook)
- `hero-meta` — author, date, read time (e.g. "Marcos Damasceno · February 2026 · 12 min read")

**Hero canvas animation must be post-specific.** The hero canvas
(full-viewport background behind the title) must visualize the post's
core mechanism, not generic particles or ambient effects. If the post
is about stigmergy, the hero shows traces accumulating into governance.
If the post is about prion propagation, the hero shows conversion
cascades. Generic "pretty particles drifting" is decoration, not
communication. Apply the same Veritasium test as section animations:
describe the hero animation to someone. Would they guess the post's
topic? If not, redesign until they would.

**Required lede section (between hero and Section 1):**
The narrative hook lives in its own numberless section:

```html
<section class="tempo-jo" style="padding-bottom: 0;">
  <div class="prose reveal">
    <p>Opening hook paragraph(s) that set up the entire post...</p>
  </div>
</section>
```

Rules:

- No section-number. No h2. No accent color. Just prose.
- `padding-bottom: 0` so it flows tightly into S1 (S1's top
  padding provides the gap).
- Last paragraph gets `style="margin-bottom: 0;"` to eliminate
  double spacing at the seam.
- Contains 1-3 paragraphs: the narrator's voice entering the room.
- Do NOT fold into S1 (it introduces the whole post, not one section).
- Do NOT fold into the hero (prose competes with visual spectacle).
- Do NOT label it "Intro" or "Preface" (invisible transition).

**Required structural elements (from framework):**

- `<a href="/" class="nav-back">&larr; Torad</a>` — fixed nav link
- Scroll chevron inside the hero section (`.scroll-chevron` button
  with SVG arrow, onclick scrolls to first section)
- Explore with Claude buttons (`.explore-btn`) placed **INSIDE** components.
  The button is PART OF the component — inside its container div,
  not a separate element after it. Place where curiosity peaks,
  not after every component.

  **CORRECT** (button inside the case-study container):

  ```html
  <div class="case-study reveal">
    <div class="case-study-label">Case Study — Domain</div>
    <h3>Title</h3>
    <p>Content...</p>
    <div class="mechanism">Mechanism: ...</div>
    <div style="text-align: center;">
      <button class="explore-btn" onclick="showExploreToast()">
        <svg viewBox="0 0 24 24">
          <circle
            cx="12"
            cy="12"
            r="10"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
          />
          <path
            d="M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10A15.3 15.3 0 0 1 12 2z"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
          /></svg
        >Explore with Claude
      </button>
    </div>
  </div>
  ```

  **WRONG** (button outside as a separate div — DO NOT DO THIS):

  ```html
  <div class="case-study reveal">
    ...
    <div class="mechanism">Mechanism: ...</div>
  </div>
  <!-- WRONG: button is disconnected from the component above -->
  <div style="text-align:center; margin: 1.5rem 0;">
    <button class="explore-btn" ...>Explore with Claude</button>
  </div>
  ```

  Same pattern for crossbar blocks (after `.crossbar-insight`,
  before the crossbar's closing `</div>`) and callouts.

- Explore toast element before `</body>` with EXACT structure:

  ```html
  <div class="explore-toast" id="exploreToast">
    <div class="explore-toast-title">Explore with Claude</div>
    <div class="explore-toast-text">
      Description of what Claude can explore about this post's topic.
    </div>
  </div>
  ```

  The CSS targets `.explore-toast-title` and `.explore-toast-text`
  for styling. Without these classes, the toast is unstyled text.

- Terminal blocks use `.terminal-block` class (in framework CSS)

**Framework JS: Do NOT redefine framework functions.**

The framework JS (`blog-animations.js`) provides these utilities.
Use them. Do NOT redefine them in inline scripts:

- `setupCanvas(canvas)` — returns `{ctx, w, h}` with DPR scaling.
  Use this instead of manual `canvas.getContext('2d')` + DPR.
- `initScrollReveal()` — called automatically. Adds `.visible` to
  `.reveal` elements on scroll via IntersectionObserver.
- `palette` — object with semantic colors matching CSS variables:
  `palette.gold`, `palette.cyan`, `palette.violet`, `palette.red`,
  `palette.green`, `palette.text`, `palette.textBright`, `palette.bg`
- `rgba(color, alpha)` — builds `rgba()` string from palette color.
  Use `rgba(palette.gold, 0.5)` instead of raw `'rgba(212,168,83,0.5)'`.
- `trailClear(ctx, w, h, alpha)` — alpha-blended background clear
  that creates visual trail/memory effect. Use instead of
  `ctx.clearRect()` for non-hero animations.
- `showExploreToast()` — shows the toast with proper timeout.
  Do NOT redefine this function. The framework version handles
  `clearTimeout` correctly.

**WRONG** (redefining framework function):

```javascript
function showExploreToast() { // WRONG — overrides framework
  document.getElementById('exploreToast').classList.add('visible');
  setTimeout(() => { ... }, 4000);
}
```

**CORRECT** (framework provides it, just call it):

```html
<button class="explore-btn" onclick="showExploreToast()"></button>
```

DO NOT read other posts for structure reference. Use the
framework files directly. Previous posts = template contamination.

### 2. Render Text into Sections

For each section in the design spec:

- Create the section element with tempo class and accent color
- Place the audited text (Phase 3) into prose containers
- Add reveal classes for scroll animations
- Section numbers in order

**Required section structure:**

The `.section-number` and `h2` MUST be inside a `.prose` container
to receive proper centering (max-width: 680px, margin: 0 auto).
Without this, titles sit at the section edge while prose content
is centered — a layout break.

```html
<section class="tempo-ha" style="--accent: var(--cyan);">
  <div class="prose reveal">
    <div class="section-number">2</div>
    <h2>Section Title Here</h2>
  </div>

  <div class="prose reveal">
    <p>First paragraph of section prose...</p>
    <p>Second paragraph...</p>
  </div>
</section>
```

**WRONG** (section-number and h2 outside prose — NOT centered):

```html
<section class="tempo-ha">
  <div class="section-number reveal">2</div>
  <h2 class="reveal">Title</h2>
  <div class="prose reveal">
    <p>Prose content (centered at 680px)...</p>
  </div>
</section>
```

The section-number and h2 share one `.prose reveal` container.
Subsequent prose paragraphs go in separate `.prose reveal` divs
for staggered scroll reveal.

The text should transfer EXACTLY from Phase 3. No rewrites at
this stage. If something reads wrong in HTML, go back to Phase 3
and fix the text there, then re-render. The build phase assembles;
it does not create.

**Semantic emphasis (from Phase 5 attention map):**
Apply emphasis EXACTLY as specified in the Phase 5 emphasis tables.
Do not add, remove, or change any emphasis assignments. The attention
map is the source of truth.

For each entry in the emphasis tables:

- Wrap the phrase in the specified HTML element (`<strong>` or `<em>`)
- Add the specified semantic class (`.thesis`, `.evidence`, `.void`,
  `.method`, or `em.term`)
- If no semantic class is specified, use default (bright white)

The semantic class system:

```
<strong class="thesis">crystallized insight</strong>     → gold
<strong class="evidence">specific count or data</strong> → cyan
<strong class="void">uncomfortable truth</strong>        → violet
<strong class="method">practical resolution</strong>     → green
<em class="term">technical term</em>                     → cyan
<strong>general emphasis</strong>                         → bright white
<em>conceptual emphasis</em>                              → bright white italic
```

The framework CSS (`blog-base.css`) renders these classes:

- `section .thesis` → `color: var(--gold)` (#d4a853)
- `section .evidence` → `color: var(--cyan)` (#4ecdc4)
- `section .void` → `color: var(--violet)` (#9b72cf)
- `section .method` → `color: var(--green)` (#7bc47f)
- `section em.term` → `color: var(--cyan)` (#4ecdc4)
- Default `strong` → `color: var(--text-bright)` (#e8eaf0)
- Default `em` → `color: var(--text-bright)` italic

Minimum 3 emphasis instances per section. A section with zero
emphasis is dead text rendering as a wall of gray.

### 3. Build Jargon Tooltips

From the Phase 5 tooltip content:

- Wrap each jargon term (first use only) in a tooltip element:

```html
<span class="jargon-tooltip" data-tooltip="Plain-language definition here">
  <em class="term">technical term</em>
</span>
```

- The tooltip appears on hover/tap, providing the plain-language
  definition from the Phase 5 jargon table
- Subsequent uses of the same term do NOT get tooltips (reader
  has already been introduced)
- Only terms from OTHER domains need tooltips. Terms the target
  audience (engineers) already knows do not.

### 4. Build Terminal Blocks

From the Phase 6 terminal specs:

- Style with terminal CSS (dark background, mono font)
- Prompt indicators ($)
- Color-coded output (pass/fail)
- Comment lines explaining what the reader should notice
- Show both correct and wrong constraint examples

### 5. Build Crossbar Blocks

From the Phase 6 crossbar specs. Use the EXACT framework HTML
structure below. The CSS targets specific class names and nesting.

**Required crossbar structure:**

```html
<div class="crossbar-block reveal">
  <div class="crossbar-label">Crossbar Bridge</div>
  <div class="crossbar-endpoints">
    <div class="crossbar-endpoint">
      <div class="ep-domain">Domain A</div>
      <div class="ep-name">Concept Name</div>
    </div>
    <div class="crossbar-bridge">&harr;</div>
    <div class="crossbar-endpoint">
      <div class="ep-domain">Domain B</div>
      <div class="ep-name">Concept Name</div>
    </div>
  </div>
  <div class="crossbar-insight">
    <p><strong class="thesis">Bridge insight...</strong></p>
  </div>
  <div style="text-align: center;">
    <button class="explore-btn" onclick="showExploreToast()">
      <svg viewBox="0 0 24 24">
        <circle
          cx="12"
          cy="12"
          r="10"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
        />
        <path
          d="M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3
        0 0 1-4 10 15.3 15.3 0 0 1-4-10A15.3 15.3 0 0 1 12 2z"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
        /></svg
      >Explore with Claude
    </button>
  </div>
</div>
```

**Critical nesting:**

- `.crossbar-endpoints` wraps the endpoints (provides flex layout)
- `.crossbar-endpoint` contains `.ep-domain` + `.ep-name`
- First endpoint's `.ep-domain` → cyan, last → violet (from CSS)
- `.crossbar-bridge` is the arrow between endpoints
- `.crossbar-insight` wraps the insight text (left-aligned, max-width)

**WRONG** (missing wrapper classes):

```html
<div class="crossbar-block">
  <strong>Domain A</strong> &harr; <strong>Domain B</strong>
  <p>Insight text...</p>
</div>
```

**Semantic emphasis inside crossbar blocks:**
The crossbar insight text carries the post's structural bridge — it is
NOT plain prose. Apply the same semantic class system from Step 2:

- The insight sentence gets `.thesis` (it crystallizes the connection)
- Domain names in endpoint labels get `em.term`
- If the insight references a void or uncomfortable truth, use `.void`

A crossbar block with zero semantic markup is a gray box. The bridge
IS the emphasis.

### 6. Build Callout and Case Study Blocks

Build breakout components from organ outputs. THE SKELETON determined
which content demands breakout containers. THE EYES provided callout
content and colors. This step assembles from those outputs. Do not
add components the organs did not find. Do not skip components they
did find.

**Callout content comes from THE EYES** (crystallized text + color).
Do not write new callout text here. Assemble from the emphasis map.

**Case study blocks** (from Phase 6 breakout specs):

```html
<div class="case-study reveal">
  <div class="case-study-label">Case Study — [Domain]</div>
  <h3>[Source Name, Year]</h3>
  <p>[Evidence paragraph — distilled, 1-2 paragraphs]</p>
  <div class="mechanism">Mechanism: [one-line mechanism]</div>
</div>
```

- Counter-cases use label "Counter-Case — [Description]"
- Count matches THE SKELETON output (no prescribed minimum)
- Place between prose sections, not inside them

**Semantic emphasis inside case study blocks:**
Case studies carry evidence. They are NOT exempt from semantic markup.

- Evidence data (numbers, dates, names, measurements) → `<strong class="evidence">`
- Thesis-level conclusions inside the case study → `<strong class="thesis">`
- Void/uncomfortable findings → `<strong class="void">`
- Cross-domain jargon terms → `<span class="jargon-tooltip" data-tooltip="...">`
  with `<em class="term">` inside
- The `.mechanism` line is already structurally distinct; it does not
  need additional semantic classes unless it contains a thesis statement

Every case study must have at least 1 semantic emphasis instance.
A case study with zero emphasis is raw gray text in a box — it
looks like a quote block, not evidence.

**Callout blocks** (from Phase 5 attention map + Phase 6 placement):

```html
<div class="callout reveal">
  <div class="callout-label">Key Insight</div>
  <p>[Distilled insight — NOT duplicating prose verbatim]</p>
</div>
```

Color variants: `.callout.violet` (void/uncomfortable),
`.callout.cyan` (technical), `.callout.red` (warning/break).

- Count matches THE EYES output (no prescribed minimum)
- Callout content should CRYSTALLIZE, not copy
- Place at emotional peaks (after PCS subvert, at void moments)

**Semantic emphasis inside callout blocks:**
Callouts exist because the insight demands visual separation. The text
inside must carry semantic force — the callout container alone is not
enough.

- The core insight sentence → `<strong class="thesis">` (or `.void`
  if the callout is a `.callout.violet`)
- Contrasting phrases within the callout → use the opposing class
  (e.g., thesis vs. void in the same callout for tension)
- Jargon terms appearing for the first time → tooltip

Every callout must have at least 1 semantic emphasis instance.
A callout with zero emphasis is a colored box around gray text —
the color promises force that the text doesn't deliver.

**Verification:** Count all `.case-study` and `.callout` elements.
They must match the organ outputs exactly. If THE SKELETON and THE
EYES found breakout components, they must all be present. If the
organs found none, none is correct.

`>>> CATCH <<<` Count your rendered `.case-study` and `.callout`
elements. Do they match the organ outputs EXACTLY? Did you add
a component the organs didn't find? Did you skip one they did
find? This phase ASSEMBLES — it does not CREATE. If your count
differs from the organ outputs, you introduced a creative
decision where none belongs. Go back to the organ output and
match it.

### 7. Build Animations

From the Phase 6 animation specs:

- Canvas elements (900x400 standard)
- Animation descriptions INSIDE `.canvas-section` containers
  (NOT `.animation-block` — that class does not exist in the framework)
- Description uses `.canvas-label` class (mono font, gold-dim,
  centered — defined in `blog-components.css`)
- Each animation demonstrates THE concept, not decoration

**Required canvas-section structure:**

```html
<div class="canvas-section reveal">
  <canvas id="uniqueCanvasId" width="900" height="400"></canvas>
  <div class="canvas-label">What this animation demonstrates.</div>
</div>
```

JavaScript for animations goes in a `<script>` block AFTER
the framework JS (`blog-animations.js`), so framework utilities
are available.

**Animation setup pattern** (use framework utilities):

```javascript
function observeCanvas(canvasId, drawFn) {
  const canvas = document.getElementById(canvasId);
  if (!canvas) return;
  const { ctx, w, h } = setupCanvas(canvas); // framework
  let running = false,
    startTime = 0;

  function loop(ts) {
    if (!running) return;
    if (!startTime) startTime = ts;
    drawFn(ctx, w, h, (ts - startTime) / 1000);
    requestAnimationFrame(loop);
  }

  new IntersectionObserver(
    (entries) => {
      entries.forEach((e) => {
        if (e.isIntersecting && !running) {
          running = true;
          startTime = 0;
          requestAnimationFrame(loop);
        } else if (!e.isIntersecting) running = false;
      });
    },
    { threshold: 0.2 }
  ).observe(canvas);
}

// Each animation uses framework utilities:
observeCanvas("myCanvas", function (ctx, w, h, t) {
  trailClear(ctx, w, h, 0.1); // framework
  ctx.fillStyle = rgba(palette.gold, 0.5); // framework
  // ... animation logic
});
```

**Animation quality requirements:**

- Use `trailClear()` (not `clearRect`) for visual memory/trails
- Use `palette` colors (not raw rgba strings) for consistency
- Include particle systems where appropriate (ambient floating
  particles add visual richness — minimum 30-60 particles)
- Add glow halos on key nodes using `createRadialGradient`
- Use bezier curves (`quadraticCurveTo`) for organic connections
- The hero canvas is special: uses manual resize + `dpr` for
  full-viewport coverage (not `setupCanvas`)

Animations should:

- Start when scrolled into view (IntersectionObserver)
- Be smooth and continuous, not jarring
- Match the described behavior exactly

### 8. Build Ending Structure

From the audited text:

1. Step-by-step actions section (concrete, pasteable)
2. Prompts section (if applicable)
3. Progression path
4. Cliffhanger section (the void question)

**Required post-footer structure:**

The post ends with a `.post-footer` containing navigation metadata.
This is the post's provenance — connecting the rendered document back
to the navigation that produced it.

```html
<footer class="post-footer reveal">
  <div class="navigation-data">Navigation [N] · [Anchor Text]</div>
  <div class="navigation-summary">
    [N] surviving vertices · Crossbar: [Domain A] ↔ [Domain B]
  </div>
</footer>
```

- `.navigation-data` — mono font, gold-dim, uppercase. Shows the
  navigation number and anchor text.
- `.navigation-summary` — dim text, smaller. Shows vertex count and
  crossbar domains.
- Both classes are styled by `blog-components.css`. Do NOT inline
  font/color styles on these elements.

`>>> CATCH <<<` You are about to run a verification checklist.
Training pulls toward checking boxes from memory: "I know I did
that, check." Evidence says: for each checkbox, FIND the specific
HTML element. Point to it. If you cannot point to the element
that satisfies the check, you are checking boxes, not verifying
HTML. Open the file. Search for the class. Confirm it exists.
This is the difference between verification and performance.

### 9. Final Verification

Run these checks on the BUILT HTML:

**Structural:**

- [ ] All sections present with correct tempo classes
- [ ] Animation descriptions inside `.canvas-section` containers
- [ ] Terminal blocks styled correctly (`.terminal-block`)
- [ ] Crossbar blocks have both endpoints + insight
- [ ] Case study blocks match THE SKELETON's structural collapse
      (count driven by content, not quota)
- [ ] Callout blocks match THE EYES' callout content output
      (count driven by content, not quota)
- [ ] Breakout components match organ outputs (if organs found
      zero, zero is correct; if organs found many, all are present)
- [ ] Jargon tooltips present for cross-domain terms (from Phase 5)
- [ ] Step-by-step present before cliffhanger
- [ ] Framework CSS/JS linked correctly (3 CSS + 1 JS)
- [ ] Nav-back link present (`.nav-back`)
- [ ] Scroll chevron in hero (`.scroll-chevron`)
- [ ] Explore with Claude buttons placed INSIDE components
      (case study, crossbar, callout — button before closing `</div>`)
- [ ] Explore toast element present before `</body>`
- [ ] Post footer present with `.navigation-data` + `.navigation-summary`

**Toroid (final check):**

- [ ] Read the hero and the final paragraph before cliffhanger.
      Does the echo still hold in the rendered HTML?
- [ ] Does the cliffhanger open a question for the next post?

**Semantic emphasis (prose sections):**

- [ ] All emphasis matches THE EYES' emphasis map exactly
- [ ] Colors express forces (crystallization/grounding/negation/
      resolution), not categories
- [ ] No THE EYES rejection boundaries violated (buckshot, flatness,
      monotone, template emphasis, color lie, decoration, contrastive
      blindness)
- [ ] Jargon tooltips match THE EYES' tooltip content

**Semantic emphasis (sub-blocks — case studies, callouts, crossbars):**

- [ ] Every `.case-study` has at least 1 semantic emphasis instance
      (`.evidence`, `.thesis`, `.void`, or `em.term`)
- [ ] Every `.callout` has at least 1 semantic emphasis instance
- [ ] Every `.crossbar-insight` has `.thesis` on the bridge sentence
- [ ] Mechanism cards / mech-cards have semantic markup on key claims
- [ ] No sub-block is a gray box (plain text with zero emphasis)

**Content integrity:**

- [ ] Text matches Phase 3 audited version exactly
- [ ] Zero em dashes in rendered text
- [ ] Zero staccato patterns
- [ ] All titles match Phase 3 story-arc titles
- [ ] First-person voice maintained throughout

**Animation accuracy:**

- [ ] Each animation shows the REAL mechanism from Phase 6 spec
- [ ] Animation behavior matches description text
- [ ] No animation contradicts the section's concept

**Technical:**

- [ ] Page loads without console errors
- [ ] All canvas elements render
- [ ] Reveal animations trigger on scroll
- [ ] Responsive on mobile/tablet/desktop
- [ ] Meta tags, OG tags, title all correct

## Output

The HTML file: `apps/torad/posts/[slug]/index.html`

This is the deliverable. The pipeline is complete.

```
Phase 1 (8a) → INPUTS DOCUMENT
Phase 2 (8b) → TEXT NARRATIVE + FASCIA MAP + BREAKOUT MAP
Phase 3 (8c) → AUDITED TEXT + VERIFIED TOROID
Phase 4 (8d) → THE SKELETON: STRUCTURAL COLLAPSE + JARGON TABLE
Phase 5 (8e) → THE EYES: EMPHASIS MAP + COLOR ARC + CALLOUT CONTENT + TOOLTIPS
  [Fascia verification between organs]
Phase 6 (8f) → DESIGN SPEC
Phase 7 (8g) → HTML FILE ← you are here
```
