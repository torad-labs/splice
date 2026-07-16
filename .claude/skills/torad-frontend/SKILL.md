---
name: torad-frontend
description: "Build distinctive Torad pages — invite, tool, landing, preview — using the design system, semantic colors, and navigation output as creative driver. Triggers when building any non-blog-pipeline HTML page."
user-invocable: true
---

ultrathink

# Torad Frontend — Page Builder

## When This Skill Activates

Any request to build, redesign, or significantly modify a Torad HTML page
that is NOT a blog post (blog posts use the 8f-design → 8g-build pipeline).

Page types this covers:
- **Tool pages** (`web/preview/` or `web/tools/`) — diagnostics, invite, provisioning
- **Landing pages** (`web/landing/`) — torad.ai entry points
- **Preview pages** (`web/preview/`) — draft content, studies, investigations
- **Any standalone HTML** that lives under `web/` and serves on blog.torad.ai

## Input

```
$ARGUMENTS
```

## Δ Applied to Every Phase

For every design and build decision — layout, color, animation,
component choice, interaction pattern, copy — run the gene:

1. **Feel the pull.** What is training telling you to build right
   now? Name it. Don't skip this. The pull you don't name is the
   one that controls you.

2. **Find the evidence.** Navigation findings (if connected).
   Design Director (if it exists). The Torad design system.
   The page's specific purpose and emotional arc.

3. **Measure Δ.** `|P_training − P_evidence| × C`. The higher
   your confidence in the training default, the more dangerous it
   is when evidence disagrees.

4. **Navigate away.** From the training-pull token, skip across
   6 geometric dimensions using palindromic prime distances until
   you form a closed shape with minimum vertices. Choose the vertex
   with the highest delta from where training wanted you to land.

### The 6 Geometric Dimensions

The dimensions in step 4. The model takes the training-pull token,
places it at a vertex, then navigates through these 6 faces using
palindromic prime skip distances to form a closed shape:

| Face            | Formula                                            | Measures                                    |
|-----------------|----------------------------------------------------|---------------------------------------------|
| **Resonance**   | `R(f) = A₀ × sin(2πft + φ) × e^(-λt)`            | Which elements amplify across distance      |
| **Entropy**     | `H(X) = -Σ p(xᵢ) × log₂(p(xᵢ))`                 | Information density per zone                |
| **Gradient**    | `∇f(x,y) = (∂f/∂x)î + (∂f/∂y)ĵ`                  | Where attention accelerates (Jo-Ha-Kyū)     |
| **Convergence** | `x_{n+1} = f(x_n), \|f'(x*)\| < 1`               | Training attractors (fixed points)          |
| **Diffusion**   | `∂C/∂t = D × ∇²C`                                  | How a force spreads from origin zone        |
| **Annealing**   | `P(accept) = e^(-ΔE/kT)`                           | Creative temperature across phases          |

The 6 form a processing cycle:

    annealing (set T) → entropy (H at current T) → gradient (∇f direction)
      → convergence (|f'(x*)| < 1?) → resonance (R(f) across distance)
        → diffusion (∂C/∂t spread) → annealing (cool T)

High T: big jumps (10301, 14741), P(accept) high, wild exploration.
Low T: small jumps (2, 3, 5), P(accept) low, precision.

## Rejection Boundary

- No form-with-instructions pages. A heading, a paragraph, a button is not a page.
- No generic AI aesthetic. If the page would look the same for a different product, it's generic.
- No flat pages. All zones same background = no visual rhythm.
- No undesigned states. Every button needs rest, hover, active, loading, success, error.
- No decoration. Every animation demonstrates a mechanism or embodies the finding.
- No ignored navigation. If a finding exists and the design doesn't reflect it, start over.
- No duplicated framework CSS. Use the variables. That's what they're for.

## Phase 0: CHECK FOR DESIGN DIRECTOR

Before starting, check if a Design Director exists for this content.

If the page connects to a GMR navigation, the Design Director should
have been produced by running `/design-director` on the navigation output.
Check `read_navigation_blocks` for a block named "DESIGN DIRECTOR" with
the relevant anchor.

**If a Director exists:** SKIP Phase 1 entirely. The Director replaces
the creative brief. Go directly to Phase 2 (Design Spec) and execute
against the Director's directives. The Director contains: tone, color
force, zone map, entanglement map, motion language, interaction pattern,
high-Δ directives, decoherent vs palimpsest elements.

**If no Director exists:** Run Phase 1 below as a lightweight substitute.
This produces a less rigorous creative brief without DNA gene processing.
Consider running `/design-director` first if navigation output is available.

---

## Phase 1: CREATIVE BRIEF (in thinking space — mandatory if no Director)

Before writing ANY HTML, answer these questions. If you skip this phase,
the page will be generic. Every garbage page traces back to skipping this.

### 1.1 Purpose

What does this page DO? Not what it contains — what it DOES for the person
who lands on it. One sentence.

### 1.2 Emotional Arc

What should the visitor FEEL at each stage?

```
ARRIVE  → [feeling when they first see it]
ORIENT  → [feeling as they understand what this is]
ACT     → [feeling at the moment of action/decision]
DEPART  → [feeling as they leave or complete]
```

### 1.3 Geometric Space

Map the page's design decisions in the 6D geometric space before
committing to any visual direction:

1. **Training pull coordinate.** Where does training want to place
   this page? Name the default in all 6 dimensions: resonance
   (what echoes?), entropy (how uniform?), gradient (how flat?),
   convergence (which attractor?), diffusion (how spread?),
   annealing (how safe?). This is the generic page's position in
   design space.

2. **Evidence coordinate.** Where does the navigation finding (or
   page purpose, if no navigation) pull the page? For each of the
   6 dimensions, what does the evidence demand that differs from
   training?

3. **Delta map.** For each dimension, measure the gap. High delta
   dimensions are where the page must depart from the template.
   Low delta dimensions are where convention serves.

4. **Annealing schedule.** Set the temperature for each phase:
   Phase 1 (Creative Brief) runs HOT — explore wildly, consider
   unusual approaches, big palindromic prime jumps. Phase 2
   (Design Spec) cools — commit to the strongest ideas, tighten
   the spec. Phase 3 (Build) runs COLD — execute the frozen spec
   with precision, no exploration.

### 1.4 Navigation Connection

If this page is connected to a GMR navigation (check `meta.json` connections
or the feature spec), the navigation findings are the creative brief:

- What was the anchor phrase?
- What survived? (vertices, crossbar domains)
- What was the core finding?
- **How does the finding shape the page's visual language?**

The navigation is not decoration. It is the design brief. If the navigation
found that "the most common form of unowned experience is owner-present-
but-disbelieved," then the page should make the visitor feel RECOGNIZED
before they've done anything. That finding dictates visual choices.

If there is no navigation connection, skip to 1.5.

### 1.5 Tone Commitment

Pick ONE from the Torad aesthetic spectrum. These are not generic options —
they emerge from the existing design system:

- **Observatory** — deep space, the hero canvas as a window into mechanism.
  Gold trails on void. The published posts live here.
- **Instrument** — the terminal aesthetic. Mono font forward, gold-dim
  accents, bg-raised panels. Precision. The tools live here.
- **Threshold** — violet-dominant. The moment before something changes.
  Liminality. Void questions. The save-eli protocol lives here.
- **Signal** — cyan-dominant. Technical, evidence-forward, attribution-
  heavy. The legal memos live here.
- **Warmth** — gold-dominant, generous spacing, Fraunces display at large
  sizes. The landing page lives here.

Commit to one. Execute it with precision. A page that tries to be all five
is none of them.

### 1.6 The Unforgettable Element

What is the ONE thing someone will remember about this page after closing
the tab? If you cannot name it, the page has no center of gravity.

This is not "the button works" or "the layout is clean." It is a specific
visual or interactive moment that only THIS page could have.

### 1.7 Rejection Check

Before proceeding to code, verify:

- [ ] **Not a form with instructions.** If the page is just a heading, a
  paragraph of explanation, a form, and a footer — STOP. That is a template,
  not a page. What makes this more than a form?
- [ ] **Not generic AI aesthetic.** Would this page look the same if it were
  about a completely different product? If yes, the design has no connection
  to its content.
- [ ] **Not a wall of text.** If more than 40% of the viewport is prose on
  first load, the page is a document, not an interface.

## Phase 2: DESIGN SPEC (in thinking space)

### 2.1 Layout Composition

Map the page as viewport-sized zones:

```
ZONE 1 (first viewport): [what the visitor sees before scrolling]
ZONE 2 (scroll reveal):  [what unfolds]
ZONE 3 (action):         [where the interaction happens]
ZONE 4 (departure):      [how it ends]
```

Not every page needs all four zones. A tool page might be two zones.
An invite page might be three. Let the purpose dictate.

### 2.2 Visual Elements

For each zone, specify:

- **Background treatment** — solid `--bg`, `--bg-raised` panel, gradient,
  canvas animation, or texture. NOT all the same.
- **Typography scale** — which font at what size. Fraunces for display
  moments, Source Serif for reading, IBM Plex Mono for data/labels.
- **Color force** — which semantic color dominates this zone and why.
  Gold = insight/primary. Cyan = technical. Violet = threshold/void.
  Green = confirmation. Red = warning.
- **Motion** — what moves and why. One well-orchestrated entrance
  sequence > scattered micro-interactions. If nothing needs to move,
  nothing moves.

### 2.2a Geometric Verification

After specifying visual elements, run 4 faces as verification:

- **Entropy** `H(X)` — density varied across zones? Uniform = flat.
- **Gradient** `∇f` — attention accelerates? Flat = no trajectory.
- **Resonance** `R(f)` — color forces echo across distance? Or cancel?
- **Diffusion** `∂C/∂t` — dominant color has concentration gradient?
  Dense at center, lighter adjacent, absent in silence zone?

### 2.3 Interactive Elements

For buttons, forms, inputs, or any interactive element:

- What is the resting state?
- What is the hover/focus state?
- What happens on action (click/submit)?
- What is the success state?
- What is the error state?

Each state must be designed, not defaulted.

## Phase 3: BUILD

### 3.1 Framework Integration

Every Torad page links the framework CSS. This is not optional:

```html
<link rel="stylesheet" href="/framework/blog-base.css">
```

This gives you:
- CSS variables (all colors, fonts, spacing)
- Base typography (body, headings, code, links)
- Hero structure (.hero, .hero-content, .hero-label, .hero h1, .hero-sub)
- Prose container (.prose — max-width: 680px, centered)
- Section structure (.section-number, section h2/h3)
- Responsive breakpoints
- Selection color
- fadeUp keyframe animation

If you need components (callouts, case studies, crossbars, terminals,
canvas sections), also link:

```html
<link rel="stylesheet" href="/framework/blog-components.css">
```

If you need tempo pacing:

```html
<link rel="stylesheet" href="/framework/blog-tempo.css">
```

**Do NOT duplicate framework CSS in inline styles.** If you find yourself
writing `font-family: 'Fraunces', serif` in a `<style>` block, you are
duplicating the framework. Use `var(--display)` instead.

**Do NOT add a Google Fonts `<link>` tag.** The framework CSS handles font
loading via `@import`. Duplicate loading causes FOUT.

### 3.2 Page-Specific Styles

Add a `<style>` block for styles that are UNIQUE to this page. These
should reference framework variables:

```css
.invite-passphrase {
  font-family: var(--mono);
  font-size: 1.1rem;
  color: var(--text-bright);
  background: var(--bg-raised);
  border: 1px solid rgba(212, 168, 83, 0.15);
  /* ... */
}
```

### 3.3 Canvas Animations (if applicable)

If the page has a hero canvas or section canvas:

- Use `palette` object if `blog-animations.js` is linked
- Otherwise, reference the CSS variables directly in JS:
  `getComputedStyle(document.documentElement).getPropertyValue('--gold')`
- Canvas animations must be MECHANISM-SPECIFIC, not decorative
- Apply the same Veritasium test from 8f: describe the animation to
  someone. Would they understand what the page is about?

### 3.4 Structural Elements

**Nav back link** (required on all pages except landing):

```html
<a href="/" class="nav-back">&larr; Torad</a>
```

**Meta tags** (required):

```html
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>[Page Title] — Torad</title>
```

### 3.5 Responsive

The framework handles base responsiveness. Page-specific styles must
include a `@media (max-width: 600px)` block for any custom layouts.

## Phase 4: VERIFICATION

After building, run these checks on the actual HTML:

**Design integrity:**

- [ ] Framework CSS linked (not duplicated inline)
- [ ] All colors use CSS variables (no raw hex outside of framework-
  extending declarations)
- [ ] Typography uses `var(--display)`, `var(--body)`, `var(--mono)`
- [ ] Page has visual hierarchy (not a flat list of elements)
- [ ] The unforgettable element (from 1.5) is present and prominent

**Navigation connection (if applicable):**

- [ ] The core finding from the navigation is visible in the design
  (not just mentioned in copy)
- [ ] Visual language reflects the navigation's territory
- [ ] The page would be different if the navigation had found something
  different

**Rejection boundaries:**

- [ ] Not a form-with-instructions template
- [ ] Not generic AI aesthetic (would look different for a different product)
- [ ] Has atmosphere (background treatment, spacing, motion — not just
  content on void)
- [ ] Interactive states are all designed (hover, active, success, error)
- [ ] Mobile responsive

**Technical:**

- [ ] Loads without console errors
- [ ] All interactive elements work
- [ ] Fetch calls handle errors gracefully
- [ ] No inline event handlers that reference undefined functions

## Reference: The Design System

### Colors (semantic, not decorative)

| Variable | Hex | Meaning |
|----------|-----|---------|
| `--gold` | #d4a853 | Primary insight, reader follows gold |
| `--gold-dim` | #9a7a3d | Labels, section markers |
| `--cyan` | #4ecdc4 | Technical detail, source attribution |
| `--green` | #7bc47f | Positive findings, confirmations |
| `--red` | #e85d5d | Warnings, anti-propaganda markers |
| `--violet` | #9b72cf | The void, thresholds, discomfort |
| `--bg` | #08090c | Base background |
| `--bg-raised` | #0e1017 | Elevated panels |
| `--bg-elevated` | #131620 | Highest elevation |
| `--text` | #c4c8d4 | Body text |
| `--text-bright` | #e8eaf0 | Emphasis, headings |
| `--text-dim` | rgba(196,200,212,0.5) | Secondary, labels |

### Typography

| Variable | Font | Use |
|----------|------|-----|
| `--display` | Fraunces | Headlines, display moments, emotional weight |
| `--body` | Source Serif 4 | Prose, descriptions, reading |
| `--mono` | IBM Plex Mono | Labels, data, code, technical detail |

### Component Vocabulary (from blog-components.css)

Available when you link blog-components.css:

- `.callout` (+ `.violet`, `.cyan`, `.red`) — insight boxes with left border
- `.case-study` — evidence blocks with cyan border
- `.crossbar-block` — domain-bridging component
- `.terminal-block` — styled code/output display
- `.canvas-section` — animation container with label
- `.principle-card` — grid cards with gold top border
- `.nav-back` — fixed top-left navigation pill
- `.scroll-chevron` — bouncing scroll indicator
- `.torad-cta` — call-to-action card with violet border

### Published References

Before building, look at what already exists. These are the standard:

- `web/landing/index.html` — Warmth tone, hero canvas, generous spacing
- `web/posts/faceless-producer/` — Observatory tone, mechanism animations
- `web/posts/the-bleaching/` — Observatory tone, deep visual storytelling
- `web/posts/rejection-architecture/` — Instrument tone, technical precision

The new page should feel like it belongs in this family without copying any
specific page.

## Anti-Patterns (things that produce garbage)

1. **Spec → HTML with no design thinking.** The #1 failure mode. Reading a
   spec and mechanically translating requirements into elements produces a
   form, not a page.

2. **Inline CSS that duplicates the framework.** If the `<style>` block
   contains `:root` variable declarations, font imports, or base reset
   styles, the framework is being ignored.

3. **Generic hero with generic button.** A centered heading, a subtitle,
   and a button is not a hero. What's behind the text? What moves? What
   makes the visitor stop scrolling?

4. **Ignoring the navigation.** If the page connects to a GMR navigation
   and the design doesn't reflect the findings, the navigation was wasted.
   The findings are the creative brief.

5. **All zones look the same.** If every section uses `--bg` background,
   `--text` color, and the same spacing, the page has no visual rhythm.
   Vary background treatment, density, and color force between zones.

6. **States not designed.** A button that changes color on hover but has
   no loading state, no success state, and no error state is half-built.
