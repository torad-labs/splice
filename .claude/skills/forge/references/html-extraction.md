# HTML Extraction Reference

CSS class and structure mapping for crystallized product HTML. Used by the
Ingest phase to extract structured data from the three product files.

## Contents

- [File Map](#file-map)
- [Extraction Schema](#extraction-schema)
  - [index.html](#indexhtml)
  - [prd.html](#prdhtml)
  - [rejection.html](#rejectionhtml)
  - [meta.json](#metajson)
- [Ingest Output Schema](#ingest-output-schema)

## File Map

| File | Purpose | Key Sections |
|------|---------|-------------|
| `index.html` | Overview + executive summary | Hero, document nav, executive summary, ELBO, patterns |
| `prd.html` | Implementation plan | Components grid, data flow, connections table, build sequence, quality profile, ELBO, open questions, emergent findings, immune memory |
| `rejection.html` | Boundaries + rejected approaches | Assumptions overturned, anti-patterns, migration risks, rejected patterns |
| `meta.json` | Structured metadata | title, slug, type, subtitle, date, status |

## Extraction Schema

### index.html

| Section | Container | Data Points |
|---------|-----------|-------------|
| Hero | `.hero` | `.hero-label` (mode label), `h1` (title), `.subtitle` (one-liner), `.meta-pill span` (mode, quality, iterations, accuracy, complexity) |
| Executive summary | `section` with `.section-label` = "Executive Summary" | `.callout` (core insight), following `p` elements (narrative) |
| ELBO card | `.elbo-card` | `.elbo-score` (overall score), `.elbo-detail strong` (accuracy, complexity), `.elbo-progress .elbo-iter` (iteration history) |
| ELBO dimensions | `.elbo-dims` | `.elbo-dim.top` (high scores), `.elbo-dim.bottom` (low scores) |
| Patterns | `.patterns-list li` | `strong` (pattern name), text content (description) |

### prd.html

| Section | Container | Data Points |
|---------|-----------|-------------|
| Hero | `.hero` | Same as index.html hero |
| Core design | `.idea-card` | `p` with `em` tags (key terms), `.callout` (key insight) |
| Components | `.components-grid .component-card` | `.component-tag` class (`new` or `modify`), `h4` (component name), `p` (description) |
| Data flow | `.flow-steps .flow-step` | `.flow-num` (step number), `p strong` (action), `p` (description) |
| Connections | `.connection-table tbody tr` | `td:1 strong` (connection name), `td:1` rest (description), `td:2` (strength), `td:3` (load-bearing assessment) |
| Build sequence | section with `.section-label` = "Build Sequence", `.flow-steps .flow-step` | `.flow-num` (step number), `p strong` (step name), `p` (description) |
| Quality profile | `.quality-grid .quality-row` | `.quality-label` (dimension name), `.quality-bar-fill` `data-width` (score × 100), `.quality-score` (score text) |
| ELBO | `.elbo-card` | Same as index.html |
| Open questions | `.questions-list li` | `strong` (question title), text (question detail) |
| Emergent findings | `.surprises-grid .surprise-card` | `data-num` (number), `h4` (finding title), `p` (description) |
| Immune memory | `.antibody-critical .antibody-grid .antibody-row` | `.ab-failure-side p` (default/failure), `.ab-prevention-side p` (evidence-based choice) |

### rejection.html

| Section | Container | Data Points |
|---------|-----------|-------------|
| Hero | `.hero` | Same structure, `.hero-label` color is `--red-soft` |
| Assumptions overturned | `.assumptions-grid .assumption-card` | `.assumed p` (what was assumed), `.found p` (what evidence showed) |
| Anti-patterns | `.findings-grid .finding-item` | `h4` (anti-pattern name), `p` (description) |
| Migration risks | `.findings-grid .finding-item` (second section) | `h4` (risk name), `p` (description with severity) |
| Rejected patterns | `.patterns-list li` | `strong` (pattern name), text (description) |

### meta.json

```json
{
  "title": "string",
  "slug": "string",
  "type": "string",
  "subtitle": "string",
  "date": "string (YYYY-MM-DD)",
  "status": "string",
  "audio": "boolean",
  "reef": "string | null",
  "source": "string",
  "derivatives": "array",
  "connections": "array"
}
```

## Ingest Output Schema

The Ingest block must produce output in this structure. Use MISSING for any
section not found in the HTML.

```
PRODUCT: [title from meta.json]
SLUG: [slug from meta.json]
DATE: [date from meta.json]
STATUS: [status from meta.json]

EXECUTIVE_SUMMARY:
  core_insight: [from .callout in executive summary section]
  narrative: [from p elements after callout]

ELBO:
  score: [overall ELBO score]
  accuracy: [accuracy component]
  complexity: [complexity component]
  iterations: [iteration count]
  top_dimensions: [list of high-scoring dimensions]
  bottom_dimensions: [list of low-scoring dimensions]

COMPONENTS: [ordered list]
  - name: [h4 text]
    type: [new | modify]
    description: [p text]

DATA_FLOW: [ordered list]
  - step: [number]
    action: [strong text]
    description: [p text]

CONNECTIONS: [ordered list]
  - name: [connection name]
    strength: [Strong | Moderate | Weak]
    load_bearing: [assessment text]

BUILD_SEQUENCE: [ordered list]
  - step: [number]
    name: [strong text]
    description: [p text]

QUALITY_PROFILE: [ordered list]
  - dimension: [name]
    score: [numeric score]

OPEN_QUESTIONS: [ordered list]
  - title: [strong text]
    detail: [full text]

EMERGENT_FINDINGS: [ordered list]
  - number: [data-num]
    title: [h4 text]
    description: [p text]

IMMUNE_MEMORY: [ordered list]
  - default: [failure/default text]
    evidence_based: [prevention/evidence text]

ASSUMPTIONS_OVERTURNED: [ordered list]
  - assumed: [what was assumed]
    found: [what evidence showed]

ANTI_PATTERNS: [ordered list]
  - name: [h4 text]
    description: [p text]

MIGRATION_RISKS: [ordered list]
  - name: [h4 text]
    description: [p text]
    severity: [extracted from description if present]

REJECTED_PATTERNS: [ordered list]
  - name: [strong text]
    description: [text content]
```
