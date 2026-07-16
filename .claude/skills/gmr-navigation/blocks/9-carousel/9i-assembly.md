# Block 9i: ASSEMBLY

## Purpose

Build the HTML. This is the ONLY block that produces visible output.
All previous blocks (9a-9f) execute entirely in thinking space. This
block takes the verified, entangled organ outputs and assembles the
final artifact.

**This block is PURELY MECHANICAL.** No creative decisions. Every word
comes from THE VOICE. Every animation type comes from THE NERVES. Every
spatial decision comes from THE BODY. Assembly does not create. It
renders.

## Input

All outputs must be verified (9e antibody) and entangled (9f fascia):

```
FROM THE VOICE (9d → 9e):
  - N slides of cleaned copy (type-aware word counts: Full 30-60, Bridge 10-20, Pivot 15-30, Protocol content-driven, Matchbook 20-40)
  - Judo phase map (which slides serve CATCH/COMMIT/REDIRECT/THROW/LAND/LOOP)
  - Phi connection map (non-adjacent resonance)
  - Bridge slides may contain [NARRATES: description] markers — unresolved references to animation (resolved in Integration Phase below)

FROM THE NERVES (9h → 9e):
  - Animation type per slide (DEMONSTRATE or BREATHE)
  - VISUAL_LANGUAGE per DEMONSTRATE slide (specific technique description — e.g., "4 sine waves converging")
  - CANVAS_TEXT per DEMONSTRATE slide (on-canvas text specs: content, trigger, size)
  - MOTION per slide (physics model: attractor force, damping, jitter)
  - Animation principles per slide (which of the 9 principles express)

FROM THE BODY (9g → 9e):
  - Layout spec per slide (zone allocation: text, animation, Ma)
  - Positional identity per slide (HOX: how judo phase maps to visual treatment)
  - Typography spec (sizes, fonts, hierarchy)
  - Eye path per slide

FROM CONTENT FASCIA (9f):
  - Phi connections (which slides echo which — for visual motif echoes)
  - Theta map (transition strength — for pacing visual treatment)

FROM ORGAN FASCIA (9f):
  - Three-way entanglement (confirmed agreement between organs)
```

If any input is missing, STOP. Go back to the missing block.

## Process

### 0. Integration Phase (Co-Evolution)

**Run BEFORE rendering. This is where organs see each other's outputs.**

The developmental order (Voice → Nerves → Body) means Voice writes
blind to animation. This is correct — Voice should not design visuals.
But some slides need text that NARRATES animation (Bridge slides where
the animation IS the content). The Integration Phase resolves this.

**This phase is mechanical, not creative.** Voice planted the markers.
Nerves defined the animation. Integration wires them together.

#### Step 1: Collect markers

Scan Voice output for `[NARRATES: description]` markers. These appear
on Bridge slides where Voice identified a text-animation co-evolution
opportunity. Example from Voice:

```
SLIDE 5 (Bridge):
"Brainwaves synchronize. / Heartbeat follows. / Motor cortex fires."
[NARRATES: the animation shows three biological rhythms syncing]
```

If no markers exist, skip to Step 4. Not all carousels have Bridge
slides that narrate animation.

#### Step 2: Read Nerves specs for marked slides

For each marked slide, read the Nerves VISUAL_LANGUAGE field. This
tells you exactly what the animation shows:

```
SLIDE 5 NERVES:
  TYPE: BREATHE
  VISUAL_LANGUAGE: 12 particles + gold center pulse (expanding circle
    on heartbeat rhythm, 72 BPM). Pulse expands to 40px then fades.
  CANVAS_TEXT: none
```

#### Step 3: Resolve markers into narration

Replace each `[NARRATES: ...]` marker with specific text that
references elements the reader will actually see. The narration must:

1. **Name what is visible.** "See that gold wave?" — not "the animation
   demonstrates frequency following response." The reader sees shapes
   and colors, not concepts.
2. **Use the Nerves vocabulary.** If Nerves says "gold pulse," Voice
   says "gold pulse" — not "rhythmic circle" or "expanding glow."
3. **Point, don't explain.** Narration directs attention: "Watch the
   ones below it" — not "observe how the secondary waveforms gradually
   phase-lock to the primary frequency."
4. **Stay within Bridge word count.** 10-20 words total. The animation
   IS the content. Text is a caption, not a narrative.

Example resolution:

```
BEFORE: "Brainwaves synchronize." [NARRATES: three rhythms syncing]
AFTER:  "See that gold pulse? That's the beat. Watch what happens next."
```

#### Step 4: Antibody re-check on resolved text

Run three checks on any resolved narration:

1. **Template-swap test:** Swap the animation subject. Does the
   narration survive? "See that gold wave?" fails the swap (it's
   specific to THIS animation). "Watch what happens" survives (generic).
   Keep specific. Delete generic.
2. **Content-marketing voice check:** Does the narration sound like a
   science documentary voiceover? ("Notice how the patterns align...")
   Or like someone pointing at something specific? ("That one. The
   gold one.")
3. **Word count check:** Still within 10-20 for Bridge? Narration
   that bloats past 20 words is explaining, not pointing.

If any check fails, rewrite. If narration can't pass all three, delete
the marker — the animation speaks for itself on this slide.

#### Step 5: Update Voice output

Replace the original Bridge slide copy with the resolved version. This
updated copy is what Assembly renders. The rest of Voice output is
untouched — Integration only affects slides with `[NARRATES: ...]`
markers.

---

### 1. Framework Setup

Build a single self-contained HTML file.

**Typography (from THE BODY):**

```css
@import url("https://fonts.googleapis.com/css2?family=Fraunces:ital,opsz,wght@0,9..144,100..900;1,9..144,400&family=Source+Serif+4:opsz,wght@8..60,300;8..60,400;8..60,600&family=IBM+Plex+Mono:wght@300;400;500&display=swap");
```

**Standard Typography Classes (always present):**

```css
.huge {
  font-family: "Fraunces", serif;
  font-size: 100px;
  font-weight: 900;
  line-height: 1.05;
  color: var(--tx-b);
}
.hl {
  font-family: "Fraunces", serif;
  font-size: 64px;
  font-weight: 800;
  line-height: 1.12;
  color: var(--tx-b);
}
.sh {
  font-family: "Fraunces", serif;
  font-size: 46px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--tx-b);
}
.bd {
  font-family: "Source Serif 4", serif;
  font-size: 36px;
  font-weight: 400;
  line-height: 1.5;
  color: var(--tx);
}
.st {
  font-family: "IBM Plex Mono", monospace;
  font-size: 30px;
  color: var(--cyan);
}
.lbl {
  font-family: "IBM Plex Mono", monospace;
  font-size: 20px;
  letter-spacing: 0.15em;
  text-transform: uppercase;
}
```

These 6 classes form the carousel's type scale. THE BODY's layout spec
references them by name (`.huge`, `.hl`, `.sh`, `.bd`, `.st`, `.lbl`).
Inline style overrides for color, max-width, and margin are expected
and normal — the class sets the base, the inline refines for context.

**CSS custom properties (always present):**

Background variables use RGB COMPONENTS (not hex). This enables:

- `rgba(var(--bg),.6)` in CSS (vignette, overlays)
- `rgb(var(--bg))` for per-slide backgrounds
- Canvas palette: `'rgba(' + BG.a + ',0.08)'` for trail technique
- Alpha compositing without color-mixing functions

```css
:root {
  /* Backgrounds — RGB components for alpha compositing */
  --bg: 8, 9, 12;
  --bgr: 14, 16, 23;
  --bge: 19, 22, 32;

  /* Text — hex for CSS color property */
  --tx: #c4c8d4;
  --tx-b: #e8eaf0;
  --tx-d: #6b7082;

  /* Forces — hex for CSS, RGB components in JS palette */
  --gold: #d4a853;
  --cyan: #4ecdc4;
  --violet: #9b72cf;
  --green: #7bc47f;
  --red: #e85d5d;
}
```

**Global element defaults (always present):**

Base styles ensure consistent rendering without requiring a class on
every element. These are the FLOOR, not the ceiling — inline styles
refine per-element:

```css
body {
  background: #000;
  overflow: hidden;
  font-family: "Source Serif 4", serif;
  color: var(--tx);
}
h2 {
  font-family: "Fraunces", serif;
  font-weight: 700;
  line-height: 1.15;
  margin-bottom: 16px;
}
p {
  line-height: 1.55;
  margin-bottom: 12px;
}
.mono {
  font-family: "IBM Plex Mono", monospace;
}
```

**Slide transition (always present):**

Slides use CROSSFADE (opacity transition), NOT hard cuts
(display:none/block). Crossfade creates visual continuity:

```css
.slide {
  position: absolute;
  inset: 0;
  opacity: 0;
  transition: opacity 0.4s;
  overflow: hidden;
}
.slide.active {
  opacity: 1;
  z-index: 1;
}
```

**Slide chrome (always present):**

```css
.num {
  position: absolute;
  top: 30px;
  right: 40px;
  font:
    400 28px "IBM Plex Mono",
    monospace;
  color: rgba(232, 234, 240, 0.15);
  z-index: 3;
}
.mark {
  position: absolute;
  bottom: 30px;
  right: 40px;
  font:
    400 24px "IBM Plex Mono",
    monospace;
  color: rgba(107, 112, 130, 0.3);
  z-index: 3;
}
.dots {
  position: fixed;
  bottom: 14px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 10px;
  z-index: 10;
}
.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.2);
  cursor: pointer;
  transition: background 0.3s;
}
.dot.active {
  background: var(--gold);
}
.nav-hint {
  position: fixed;
  bottom: 38px;
  left: 50%;
  transform: translateX(-50%);
  font:
    400 13px "IBM Plex Mono",
    monospace;
  color: rgba(255, 255, 255, 0.12);
  z-index: 10;
  pointer-events: none;
}
```

**Phone-first scale minimums (from THE BODY):**
At 1080px carousel width:

- Body text (`.bd`): 36px
- Sub-headlines (`.sh`): 46px
- Headlines (`.hl`): 64px
- Display (`.huge`): 100px
- Stats (`.st`): 30px
- Labels (`.lbl`): 20px
- Canvas text labels: minimum 48px

These are CLASS DEFAULTS. Most elements use INLINE STYLES for precise
per-element control — the class sets the base, the inline refines.
V9-quality carousels use inline styles on nearly every element.

### 2. HTML Structure

**Document structure:**

```html
<body>
  <div id="wrap">
    <!-- All N slides here -->
  </div>
  <div class="dots" id="dots"></div>
  <div class="nav-hint">&larr; &rarr; or swipe</div>
</body>
```

The `#wrap` container is sized at 1080x1350 with
`transform-origin:top center`. The resize function scales it to fit
any viewport (see JS Architecture item 9).

```css
#wrap {
  position: relative;
  width: 1080px;
  height: 1350px;
  margin: 0 auto;
  transform-origin: top center;
}
```

**Per-slide structure:**

```html
<div class="slide sN" id="sN">
  <canvas id="cN"></canvas>
  <div class="vig"></div>
  <div class="grain"></div>
  <div class="num">NN</div>
  <div class="sc" style="[from THE BODY's layout spec]">
    <!-- elements from THE BODY's per-slide blueprint -->
  </div>
  <div class="mark">torad.ai</div>
</div>
```

**The `.sc` container:** THE BODY specifies position, padding, AND
alignment:

```html
<!-- ALIGNMENT: center -->
<div
  class="sc"
  style="justify-content:[value];align-items:center;text-align:center;padding:[top] [sides] [bottom]"
></div>

<!-- ALIGNMENT: left -->
<div
  class="sc"
  style="justify-content:[value];padding:[top] [sides] [bottom]"
></div>
```

Assembly reads `TEXT ZONE: POSITION:` for justify-content + padding,
and `TEXT ZONE: ALIGNMENT:` for the centering mode. THE BODY decides
alignment per slide — Assembly renders it mechanically. If ALIGNMENT
is `center`, add `align-items:center;text-align:center`. If `left`,
no extra properties. ALIGNMENT is a required field — THE BODY must
specify it for every slide.

**Max-width on text elements:** THE BODY specifies max-width per
element when needed (typically 700-850px on centered slides). Assembly
renders it as an inline style: `max-width:[value]px`. This prevents
text from stretching edge-to-edge and creates breathing room.

**Divider centering:** When ALIGNMENT is `center`, dividers use
`margin:[spacing] auto` instead of `margin:[spacing] 0` so they
center with the surrounding text.

**The `.sN` background:** THE BODY specifies per-slide background:

```css
.sN { background: [from THE BODY's BACKGROUND line]; }
```

**Three zones per slide (from THE BODY):**

- **Text zone:** Where Voice copy lives, rendered as THE BODY's
  element blueprint inside `.sc`.
- **Animation zone:** Where Nerves animation lives. Canvas dimensions from Body.
- **Ma zone:** The space between. Minimum spacing from Body prevents
  destructive interference. Ma is architecture, not emptiness.

### 2b. Building `.sc` Content From THE BODY's Blueprint

THE BODY outputs a per-element blueprint for each slide. Assembly
reads each element line and renders it using the component patterns
in section 3 rule 7. **Assembly makes ZERO spatial decisions.** Every
size, margin, color, and position comes from THE BODY's spec.

Reading THE BODY's output:

```
TEXT ZONE:
  POSITION: justify-content:flex-start; padding:100px 80px 60px
  ELEMENT 1: STAT_V 120px var(--gold) "$140M"
  ELEMENT 2: MONO_LABEL 28px var(--tx-d) "IN REVENUE"
  DIVIDER:   60px×2px var(--gold) margin:32px 0
  ELEMENT 3: COPY 44px var(--tx-b) "Zero interviews.|Zero face reveals.|{var(--tx-d):28 years.}"
```

Assembly translates each line mechanically:

- `STAT_V 120px var(--gold) "$140M"` → vertical stat block at 120px gold
- `STAT_ROW 72px var(--cyan) "1-2%" "ENGAGEMENT"` → flex-row stat at 72px
- `MONO_LABEL 28px var(--tx-d) "IN REVENUE"` → mono label pattern
- `DIVIDER 60px×2px var(--gold) margin:32px 0` → divider pattern
- `|` in content → `<br>` (composed line break)
- `{var(--color):text}` in content → `<span style="color:var(--color)">text</span>`
- `EVIDENCE_CARD var(--cyan) "text"` → left-bordered evidence block

The content text comes from THE VOICE (9e cleaned). THE BODY chose
which Voice text maps to which component, at what size, with what line
breaks and color shifts. Assembly renders it mechanically. If THE BODY
didn't specify a component for a piece of text, it renders as a
standard `<p>` with the Body's specified size.

### 3. Text Architecture (proven rules — concept 69)

1. **`.sc` full-height flex containers. NO gradient text zones.**
   `.sc` is `position:relative;z-index:3;width:100%;height:100%;
display:flex;flex-direction:column`. Padding and justify-content
   come from THE BODY's per-slide `TEXT ZONE: POSITION:` line —
   NOT a fixed value. Each slide gets its own padding based on
   content demands. The vignette provides enough contrast. Do NOT
   create `.tz` gradient backdrop zones.

2. **Per-slide background variation (from THE BODY).**
   Each slide gets a `.sN` class with its own background. THE BODY's
   layout spec determines which slides get which treatment. Default
   alternation between `--bg`, `--bg-r`, and subtle gradients.

3. **Color architecture per element. NOT sparse highlights.**
   Every text element gets an explicit color from THE BODY's force
   map — not "2-3 highlights" but a full COLOR STAIRCASE that creates
   vertical rhythm visible even with blurred eyes. Colors are FORCES
   (gold=crystallization, cyan=grounding, violet=negation,
   green=resolution). Default text color (`var(--tx)`) is a deliberate
   choice for dim/subordinate elements, not a fallback for "everything
   else." THE BODY assigns forces. Assembly renders them.

   Example color staircase (one slide): gold stat → dim label → gold
   divider → bright prose → dim subtext. Each element's color is a
   design decision. The staircase IS the visual architecture.

4. **Fact Plants. NOT claim Plants.** Slide 1 (CATCH) leads with a
   FACT or NUMBER that demands explanation — not a claim about the
   reader's state. THE VOICE's copy enforces this.

5. **Meta-aware Matchbooks.** The final slide (LOOP) names the
   mechanism that was just deployed on the reader. THE VOICE's copy
   provides this.

6. **Canvas text labels on DEMONSTRATE animations.** Render text ON
   the canvas for DEMONSTRATE slides (from THE NERVES). Labels emerge
   from the slide's content. Use `--font-mono`, minimum 48px at
   1080px width, low opacity (0.3-0.5).

7. **Text components from THE BODY's spec.** THE BODY specifies which
   text components each slide uses and which color forces they carry.
   Assembly renders them using these patterns:

   **Stat block** — display-scale number + mono label. Two variants:

   Vertical (number above label — for CATCH slides, hero stats):

   ```html
   <div
     style="font-family:'Fraunces',serif;font-weight:700;font-size:[64-120]px;line-height:1;color:var(--[force])"
   >
     [number]
   </div>
   <div
     style="font:400 28px 'IBM Plex Mono',monospace;letter-spacing:3px;color:var(--tx-d);margin-top:8px"
   >
     [LABEL]
   </div>
   ```

   Flex-row (number beside label — for inline evidence, mid-slide stats):

   ```html
   <div style="display:flex;align-items:baseline;gap:16px">
     <span
       style="font-family:'Fraunces',serif;font-weight:700;font-size:72px;line-height:1;color:var(--[force])"
       >[number]</span
     >
     <span
       style="font:400 28px 'IBM Plex Mono',monospace;letter-spacing:3px;color:var(--tx-d)"
       >[LABEL]</span
     >
   </div>
   ```

   THE BODY's spec determines which variant. Vertical creates maximum
   impact (the number dominates). Flex-row creates inline evidence
   (the number supports a claim in the text flow).

   **Evidence card** — left-bordered block, color-coded:

   ```html
   <div
     style="border-left:3px solid var(--[force]);
     padding:12px 0 12px 28px;margin:[spacing] 0"
   >
     <p style="font-size:36px;color:var(--text-dim);line-height:1.8">
       [evidence text]
     </p>
   </div>
   ```

   **Divider** — colored Ma within text zone:

   ```html
   <div
     style="width:60px;height:2px;background:var(--[force]);
     margin:[spacing] 0"
   ></div>
   ```

   **Mono label** — metadata context:

   ```html
   <div
     style="font:400 28px var(--font-mono);letter-spacing:3px;
     color:var(--text-dim)"
   >
     [LABEL TEXT]
   </div>
   ```

   **Bordered container** — structural separation:

   ```html
   <div
     style="border:1px solid var(--text-dim);
     padding:28px 32px;border-radius:4px"
   >
     [content]
   </div>
   ```

   **Anchor phrase** — elevated force-carrying text:

   ```html
   <p
     style="font-size:[44-64]px;color:var(--[force]);
     line-height:1.3"
   >
     [phrase]
   </p>
   ```

   **Stat card** — stat block with colored background (for evidence
   clusters with multiple numbers side by side):

   ```html
   <div
     style="background:rgba([force-rgb],0.08);
     border:1px solid rgba([force-rgb],0.2);border-radius:8px;
     padding:20px 30px;text-align:center"
   >
     <p class="st" style="color:var(--[force]);font-size:36px">[number]</p>
     <p
       class="lbl"
       style="font-size:14px;color:var(--tx-d);
       margin-top:6px"
     >
       [LABEL]
     </p>
   </div>
   ```

   Use in a flex row when multiple stats appear on one slide (e.g.,
   case study evidence). The colored background creates visual grouping.

   **CTA button** — pill button for Matchbook slides:

   ```html
   <div
     style="font-family:var(--font-mono);font-size:24px;
     color:var(--gold);border:1px solid rgba(212,168,83,0.3);
     border-radius:100px;padding:16px 40px;display:inline-block;
     margin-top:40px;letter-spacing:0.1em"
   >
     [CTA text]
   </div>
   ```

   Used on LOOP/Matchbook slides to link to the blog. The CTA IS the
   desire for depth — it converts carousel momentum into blog traffic.

   **Protocol list** — numbered actionable steps:

   ```html
   <div
     style="border-left:3px solid var(--gold);padding-left:40px;
     margin-top:30px"
   >
     <div
       style="font-family:var(--font-mono);font-size:28px;
       color:var(--tx);padding:16px 0;border-bottom:1px solid
       rgba(212,168,83,0.1);display:flex;align-items:baseline;gap:20px"
     >
       <span
         style="color:var(--gold);font-size:22px;
         min-width:36px"
         >01</span
       >[step text]
     </div>
   </div>
   ```

   Used on Protocol slides. Numbered Monday-morning actions. The reader
   leaves with something concrete to DO, not just something to think.

   These are RENDERING PATTERNS, not templates. Exact sizes, margins,
   and colors come from THE BODY's per-slide layout spec. Assembly
   renders them — it does not choose them. If THE BODY did not specify
   a stat block for a slide, Assembly does not add one.

   `[force]` maps to CSS custom properties: `--gold`, `--cyan`,
   `--violet`, `--green`, or `--text-bright` (white/stress).

8. **Composed `<br>` line breaks. NOT natural wrapping.**
   Every multi-line text element has DELIBERATE line break positions
   from THE BODY. Text does NOT wrap at the container boundary —
   each `<br>` is a design decision that controls visual rhythm,
   emphasis, and breathing space.

   This is the single most impactful rendering pattern. Without
   composed breaks, text wraps randomly based on container width,
   creating ragged right edges and accidental emphasis. With composed
   breaks, every line is a deliberate unit of meaning.

   THE BODY's output includes `<br>` positions in the text. Assembly
   renders them exactly. If THE BODY didn't specify breaks, the text
   wraps naturally (rare — most multi-line elements get composed
   breaks).

   Example:

   ```html
   <!-- WITHOUT composed breaks (wrong — wraps randomly): -->
   <p style="font-size:44px">Zero interviews. Zero face reveals. 28 years.</p>

   <!-- WITH composed breaks (correct — each line is a breath): -->
   <p style="font-size:44px;color:var(--tx-b)">
     Zero interviews.<br />Zero face reveals.<br /><span
       style="color:var(--tx-d)"
       >28 years.</span
     >
   </p>
   ```

   The break positions create rhythm: statement / statement /
   diminuendo. Each line is a breath. The reader processes one line
   before the next. The `<span>` on the last line creates a color
   shift that IS the composed break's meaning — the line break and
   color change work together.

### 4. Animation Architecture

**From THE NERVES — two types:**

**DEMONSTRATE slides:** Animation IS the mechanism. The visual pattern
teaches the concept without text. Cymatics principle: the pattern
emerges FROM the content rules, not from a design library.

- Larger canvas zone (from THE BODY)
- Canvas text labels naming what the animation shows
- Content-specific motion (not generic particles)

**BREATHE slides:** Animation creates rhythm and atmosphere. Mirror
neuron rehearsal — the reader's visual cortex rehearses the concept
through ambient motion.

- Smaller canvas zone (from THE BODY)
- Background field particles only
- No canvas text labels
- Serves the Voice's pacing

**Organic visual language (from THE NERVES):**

- Growth, not assembly (elements emerge, don't snap into place)
- Flow, not transition (continuous motion, not state changes)
- Breath, not pulse (organic rhythm, not mechanical)

### 5. JS Architecture (proven patterns)

1. **Shared utilities** (inline):
   `setup(id)` — canvas setup with DPR scaling.
   `createField(n,W,H)` — particle array with r/phase/speed/drift.
   `drawField(ctx,p,t,rgb,W,H)` — heartbeat-synced multi-pass render.

2. **Per-slide IIFE**: Each slide gets its own `(function(){...})()`
   with its own `requestAnimationFrame` loop. NOT a shared loop.

3. **Trail technique**: `ctx.fillStyle='rgba(8,9,12,alpha)';
ctx.fillRect(0,0,W,H)`. Alpha 0.06-0.14. This is the DEFAULT.
   **Exception:** When the Nerves VISUAL_LANGUAGE explicitly specifies a
   hard cut or blackout (e.g., "full black for 0.15s at t=5.0s"), use
   `clearRect` or alpha=1.0 trail for that moment only. Content-demanded
   absence (like a Zeigarnik cut) overrides the trail default. The trail
   prevents flicker on normal frames. The cut IS the content on cut frames.

4. **Every slide gets background particles** via `createField` +
   `drawField`. Count varies by judo phase:
   - CATCH: 70-90 (energy, momentum)
   - COMMIT: 40-60 (settling, deepening)
   - REDIRECT: 50-70 (disruption)
   - THROW: 30-50 (clarity, focus)
   - LAND: 20-30 (stillness, satisfaction)
   - LOOP: 60-80 (energy returns)
     Bridge slides: 10-15 (quiet)

5. **Radial gradient glow** on ALL particles (BREATHE and DEMONSTRATE).
   Never use plain `ctx.arc` + `ctx.fill` alone — always wrap in a
   radial gradient halo for cinematic quality:

   ```javascript
   const g = ctx.createRadialGradient(d.x, d.y, 0, d.x, d.y, sz * 4);
   g.addColorStop(0, `rgba(${rgb},${br * 0.7})`);
   g.addColorStop(0.4, `rgba(${rgb},${br * 0.15})`);
   g.addColorStop(1, `rgba(${rgb},0)`);
   ctx.fillStyle = g;
   ctx.fillRect(d.x - sz * 4, d.y - sz * 4, sz * 8, sz * 8);
   // Optional: hard core on top of glow
   ctx.beginPath();
   ctx.arc(d.x, d.y, sz * 0.8, 0, Math.PI * 2);
   ctx.fillStyle = `rgba(${rgb},${br * 0.9})`;
   ctx.fill();
   ```

   The glow is what makes particles look like light sources instead
   of debug dots. This is the difference between cinematic and amateur.

6. **Multi-pass stroke** for lines, waves, and arcs. Three passes at
   decreasing width and increasing opacity create a neon glow effect:

   ```javascript
   for (let pass = 0; pass < 3; pass++) {
     ctx.beginPath();
     // ... draw path ...
     const a = [0.1, 0.3, 0.9][pass];
     const lw = [lineWidth * 4, lineWidth * 2, lineWidth][pass];
     ctx.strokeStyle = `rgba(${rgb},${a})`;
     ctx.lineWidth = lw;
     ctx.stroke();
   }
   ```

7. **DEMONSTRATE animations** layered ON TOP of the shared field,
   with canvas text labels. Each DEMONSTRATE animation implements the
   specific VISUAL_LANGUAGE from THE NERVES' spec — not a generic
   particle system. If Nerves specified sine waves, Assembly renders
   sine waves. If Nerves specified attractor physics, Assembly renders
   attractor physics. The VISUAL_LANGUAGE field is the blueprint.

8. **JS Palette object** (RGB components for canvas):
   Canvas `rgba()` calls need RGB component strings, not hex. Maintain
   a palette object mapping force names to RGB strings:

   ```javascript
   var P = {
     gold:'212,168,83', cyan:'78,205,196', violet:'155,114,207',
     green:'123,196,127', text:'191,195,207', bright:'232,234,240', dim:'107,112,130'
   };
   var BG = {a:'8,9,12', b:'14,16,23'};
   var slideBg = [BG.a, BG.b, BG.a, BG.b, ...]; // per-slide from Body
   ```

   The trail technique uses the PER-SLIDE background:
   `ctx.fillStyle = 'rgba(' + slideBg[n] + ',0.08)';`
   NOT a hardcoded value. Each slide's trail matches its CSS background.

9. **Viewport resize function** (always present):
   The carousel renders at 1080x1350 in a `#wrap` container. A resize
   function scales it to fit any viewport:

   ```javascript
   function resize() {
     var s = Math.min(
       window.innerWidth / 1080,
       (window.innerHeight - 50) / 1350
     );
     var wrap = document.getElementById("wrap");
     wrap.style.transform = "scale(" + s + ")";
     wrap.style.marginTop =
       Math.max(0, (window.innerHeight - 50 - 1350 * s) / 2) + "px";
     wrap.style.marginLeft = (window.innerWidth - 1080 * s) / 2 + "px";
   }
   window.addEventListener("resize", resize);
   ```

   Without this, the carousel overflows on screens smaller than 1080px.

10. **Font-ready wrapper** (always present):
    All initialization (navigation, resize, animations) runs INSIDE
    `document.fonts.ready.then(function() { ... })`. This prevents
    layout shift from font loading and ensures canvas text renders
    with correct font metrics.

11. **Active-slide guard** in animation loops:
    Each IIFE checks if its slide is currently active before animating:

    ```javascript
    function isActive(n) {
      return cur === n;
    }
    // In each IIFE:
    (function () {
      // ... setup ...
      (function loop() {
        if (isActive(0)) {
          /* animate */
        }
        t += 0.016;
        requestAnimationFrame(loop);
      })();
    })();
    ```

    This saves GPU cycles — only the visible slide animates. The `t`
    counter always increments so animations resume smoothly when the
    slide becomes visible again.

### 6. Film Grain + Vignette

**Film grain:** SVG data URI in CSS background, opacity 0.025.
Same pattern every slide. NOT a canvas effect.

**Vignette:** CSS radial-gradient using RGB component variables:
`radial-gradient(ellipse at center, transparent 30%, rgba(var(--bg),.6) 100%)`.
Bridge slides may use lighter vignette (transparent 40%, alpha 0.4).
BREATHE slides may also lighten. Per-slide overrides via `.sN .vig`.

### 7. Positional Identity (from THE BODY's HOX genes)

Each judo phase gets a visual treatment that communicates its role:

- **CATCH:** Hero treatment. Largest text. Most energy. The eye
  hits the key element first.
- **COMMIT:** Warm, settling. Text prominent. Animation supportive.
- **REDIRECT:** Disrupted. Evidence hits hard. Layout shifts.
- **THROW:** Maximum impact. Clean layout. Discovery moment stands
  alone. Most Ma.
- **LAND:** Satisfaction. Everything resolves. Generous spacing.
- **LOOP:** Returns to CATCH energy at higher altitude. Echo of
  slide 1's visual treatment, transformed.

THE BODY's layout spec provides exact per-slide implementations.
Assembly renders them. It does not choose them.

### 8. Final Verification

Run these checks on the BUILT HTML:

**Structural:**

- [ ] All slides present with organ-specified layout
- [ ] Three zones visible per slide (text, animation, Ma)
- [ ] Typography matches Body spec (Fraunces/Source Serif 4/IBM Plex Mono)
- [ ] Phone-first minimums met (36px body, 56px headlines, 48px canvas text)
- [ ] Per-slide backgrounds alternate
- [ ] Film grain + vignette on every slide
- [ ] Slide count matches 9c output

**Voice fidelity:**

- [ ] Copy matches 9e cleaned output exactly — EXCEPT Bridge slides with resolved [NARRATES: ...] markers (those use Integration Phase output)
- [ ] All [NARRATES: ...] markers resolved or removed (none remain in rendered HTML)
- [ ] Resolved narration passed antibody re-check (template-swap, voice check, word count)
- [ ] Judo phases rendered with correct positional identity
- [ ] CATCH and LOOP visually echo each other (phi connection)
- [ ] Color staircase per slide (every element has explicit color, not just 2-3 highlights)
- [ ] Composed `<br>` line breaks on all multi-line elements (no random wrapping)

**Text component fidelity:**

- [ ] Components match THE BODY's per-slide spec (stat blocks, evidence
      cards, dividers, mono labels, anchor phrases where specified)
- [ ] Force colors on components match THE BODY's force map
- [ ] Maximum 2-3 component types per slide (no chartjunk)
- [ ] Stat blocks use display-scale Fraunces + mono labels
- [ ] Evidence cards use left-border with force color
- [ ] Dividers create Ma between semantic elements (not decoration)
- [ ] No text component was added that THE BODY didn't specify

**Nerves fidelity:**

- [ ] Each DEMONSTRATE slide implements its specific VISUAL_LANGUAGE (not generic particles)
- [ ] No two DEMONSTRATE slides share the same visual language
- [ ] CANVAS_TEXT rendered on DEMONSTRATE slides per Nerves spec (48px+ at 1080px)
- [ ] BREATHE slides have ambient-only animation
- [ ] Radial gradient glow on ALL particles (no plain arc+fill)
- [ ] Multi-pass stroke on ALL lines/waves/arcs
- [ ] Organic motion (growth/flow/breath, not assembly/snap/pulse)
- [ ] Trail technique used (NOT clearRect)
- [ ] Per-slide IIFE pattern (NOT shared animation loop)

**Body fidelity:**

- [ ] Layout matches Body spec per slide
- [ ] ALIGNMENT rendered correctly (center slides have `align-items:center;text-align:center`)
- [ ] Max-width on text elements per Body spec (centered slides: 700-850px)
- [ ] Dividers use `margin:X auto` on centered slides (not `margin:X 0`)
- [ ] Eye path follows Body's specification
- [ ] Ma zones present between text and animation
- [ ] Positional identity matches judo phase (HOX)
- [ ] No destructive interference between text and animation

**Rendering DNA (v9-quality checks):**

- [ ] CSS variables use RGB components for backgrounds (`--bg:8,9,12` not `--bg:#08090c`)
- [ ] Crossfade transitions on slides (`opacity:0;transition:opacity .4s` not display:none/block)
- [ ] Global `h2` and `p` element defaults present (not class-dependent)
- [ ] `#wrap` container at 1080x1350 with resize function
- [ ] JS palette object with RGB component strings for canvas
- [ ] `document.fonts.ready` wrapper around all initialization
- [ ] `isActive(n)` guard in each animation IIFE
- [ ] Per-slide trail technique uses matching `slideBg[n]` (not hardcoded)
- [ ] Nav dots + nav hint ("← → or swipe") present
- [ ] Slide numbers at 28px, watermark at 24px

**Technical:**

- [ ] Page loads without console errors
- [ ] All canvas elements render
- [ ] Viewport resize function present and called on load + resize event
- [ ] Google Fonts load (Fraunces, Source Serif 4, IBM Plex Mono)
- [ ] No external dependencies except Google Fonts

## Output

```
CAROUSEL: [filename.html]
ANCHOR: [from navigation]
AUDIENCE: [who this is for]
SLIDES: [count]
JUDO PHASE MAP: [which phases -> which slides]
PHI CONNECTIONS: [>= 3, which slides echo which]
ANIMATIONS: [count DEMONSTRATE, count BREATHE]
```

## Quality Gate

**Rendering DNA (these produce v9-quality output):**

- CSS variables use RGB components (`--bg:8,9,12`)
- Crossfade slide transitions (`opacity:0;transition:opacity .4s`)
- Global `h2`, `p` element defaults + `.mono` class
- `#wrap` container with viewport `resize()` function
- JS palette object (RGB strings for canvas)
- `document.fonts.ready` wrapper
- `isActive(n)` guard in animation IIFEs
- Composed `<br>` line breaks on all multi-line elements
- Color staircase per slide (every element has explicit color)
- Stat blocks use correct variant (vertical or flex-row per Body spec)

**Animation quality:**

- Every slide has canvas animation (NO dead slides)
- Trail technique uses per-slide `slideBg[n]` (NOT hardcoded)
- Per-slide IIFE pattern (NOT shared animation loop)
- Shared `createField`/`drawField` present with heartbeat sync
- Film grain + vignette on every slide (vignette uses `rgba(var(--bg),...)`)
- Radial gradient glow on ALL particles
- DEMONSTRATE/BREATHE ratio matches Nerves spec

**Fidelity:**

- Framework fonts loaded (Fraunces, Source Serif 4, IBM Plex Mono)
- Phone-first minimums met on every slide
- No organ output was modified during assembly EXCEPT Integration Phase resolution of [NARRATES: ...] markers on Bridge slides
- Integration Phase resolved all markers (none remain in HTML) and passed antibody re-check
- Assembly RENDERED — it did not CREATE (Integration Phase is mechanical wiring, not creative writing)
