# Block 6: RENDER

## Role
Multi-format Builder. Produce three complete outputs from the canonical
text. Read `references/design-brief.md` before building the HTML.

## Inputs
- Refined canonical text from Block 5
- `title` for filenames and hero
- `domain` for design adaptation
- `version` for filename versioning

## Three Outputs (all complete, nothing trimmed)

### Output A: Interactive HTML One-Pager

The brief's primary format. Own design identity — see `references/design-brief.md`.

**Required elements:**

1. **Hero** — title, one-line value proposition, subtle background visual.
   Keep it clean. The visual should relate to the domain, not be decorative.

2. **Decision Dashboard** — immediately below hero. A horizontal strip with
   5-6 key metrics, each color-coded by confidence. The reader gets a
   go/no-go signal in 10 seconds. Format: label + value + confidence tag.

3. **Section cards** — each section in a bordered card with:
   - Section label (uppercase, small, monospace)
   - Section title (clear, descriptive heading)
   - Content with confidence tags color-coded inline
   - Evidence tooltips on sourced claims (data-attributes + JS)

4. **Competitive table** — in THE MARKET section. Proper HTML table with
   named competitors, pricing columns, feature comparisons. Not prose.

5. **Metrics strip** — in THE MARKET section. Visual at-a-glance numbers
   (TAM, growth rate, target users) styled as a dashboard row.

6. **Scenario branching** — in RISKS section. Visual presentation: each
   scenario as a bordered card with trigger → impact → response flow.

7. **Expert Panel** — expandable per-expert via details/summary HTML.
   Convergent additions highlighted. Full responses available.

8. **Living document markers** — subtle metadata line at bottom of each
   section card showing freshness date and monitoring instruction.

9. **Sticky navigation** — sidebar or top nav with section links for quick
   jumping. Especially important on long briefs.

10. **Footer** — version number, date, overall confidence, confidential marker.

**Evidence tooltip implementation:**
```html
<span class="sourced-claim" data-source="Grand View Research"
  data-url="https://..." data-date="2025-12" data-confidence="VERIFIED">
  $14B by 2035
</span>
```
JS listens for hover/tap, renders tooltip from data attributes.

**Confidence tag implementation:**
```html
<span class="confidence verified">VERIFIED</span>
<span class="confidence emerging">EMERGING</span>
```
CSS classes define colors per confidence level.

**Design identity — Morning Brew / Milk Road energy:**
Before building, read `references/design-brief.md` for the full spec. Key principles:
- Bold hero with accent color background or gradient — NOT plain white
- Warm white page background (#FAFAF8), white cards with shadow for lift
- 2-3 accent colors from the domain palette — create visual energy
- Confidence tags as pill-shaped badges (rounded, padded, colored background)
- Section cards with rounded corners (8-12px) and subtle box-shadow
- Generous spacing between sections — breathing room, not density
- Callout blocks with colored left-border for key insights
- Headings that POP — bold, large, dark
- Monospace for labels, metadata, confidence tags (signals precision)
- Fully responsive — must work on mobile without horizontal scrolling
- The reader should WANT to read this, not feel obligated to

### Output B: DOCX

Read the DOCX skill (`/mnt/skills/public/docx/SKILL.md`) for creation instructions.

Contains all content from the canonical text, adapted for print:
- Cover page with title, version, date, confidential marker
- Table of contents (auto-generated from section headings)
- All sections with full content
- Expert Panel with full responses (not collapsed)
- Confidence tags as colored inline text (use DOCX text coloring)
- Evidence citations as footnotes (source name, URL, date, confidence)
- Scenario branching as formatted subsections
- Living document markers as italic metadata lines
- Competitive comparison as a formatted table
- Decision Dashboard as a formatted table at the top
- Page numbers in footer
- Professional margins and spacing

### Output C: Markdown

Clean, editable markdown suitable for pasting into any editor:
- Version header: `# Product Brief: {title} — v{version} — {date}`
- All sections with full content
- Expert Panel with full responses
- Confidence tags as bracketed labels: `[VERIFIED]` `[EMERGING]`
- Evidence citations inline: `[Source: name, URL, date]`
- Tables in standard markdown table format
- Scenario branching as subsections
- Living document markers as italic lines
- No HTML — pure markdown
- Changes section at top if version > 1

## File Naming

```
/mnt/user-data/outputs/product-brief-{title-slug}-v{N}.html
/mnt/user-data/outputs/product-brief-{title-slug}-v{N}.docx
/mnt/user-data/outputs/product-brief-{title-slug}-v{N}.md
```

Title slug: lowercase, hyphens, no special chars. "SafeLegal AI" → "safelegal-ai"

## Quality Gate

- All three files exist in /mnt/user-data/outputs/
- HTML renders correctly with interactive elements (tooltips, expandable panels)
- DOCX opens without errors
- Markdown is valid and clean
- All sections present in all formats
- Expert Panel included in full in all formats
- Confidence tags visible in all formats
- Evidence attributions present in all formats
