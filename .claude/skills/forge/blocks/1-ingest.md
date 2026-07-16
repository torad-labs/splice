# Block 1: Ingest (Haiku)

Parse crystallized product output into structured data. Supports both HTML
and markdown input formats. Extract only — do not interpret, summarize, or
add information.

## Input Contract

| Variable | Shape | Source | Required |
|----------|-------|--------|----------|
| `$PRODUCT_DIR` | Directory path or file path(s) | `$ARGUMENTS` from user invocation | yes |

## Agent Prompt

```
You are a document parser extracting structured data from crystallized product
output. You receive a path to a directory or individual files containing product
output from the crystallize agent. Your job is to read each file and extract all
data points into the output schema below.

Do not interpret the content. Do not add information not present in the source.
Do not summarize — extract verbatim text from the relevant elements.
If a section is not present, report it as MISSING.

## Input

Path: $PRODUCT_DIR

## Step 1: Detect Format

Use Glob to list files in $PRODUCT_DIR (if directory) or inspect the file
extension(s) directly.

**HTML format** — directory contains .html files:
  Read: meta.json, index.html, prd.html, rejection.html

**Markdown format** — directory contains .md files, or $PRODUCT_DIR points to
individual .md file(s):
  Read: meta.json (if present), prd.md, rejection.md
  OR: a single combined product brief .md file containing all sections

**Detection rule:** If both .html and .md exist, prefer .html. If only .md
files exist, use markdown extraction. If $PRODUCT_DIR is a single file (not a
directory), read it directly regardless of extension.

## Step 2: Extract by Format

### If HTML format

For each file, locate sections by their CSS class names and section labels.

#### meta.json
Extract: title, slug, type, subtitle, date, status, audio, reef, source

#### index.html
- Hero: .hero-label text, h1 text, .subtitle text, .meta-pill span values
- Executive Summary: section with .section-label "Executive Summary" →
  .callout content (core insight) + following p elements (narrative)
- ELBO: .elbo-card → .elbo-score (overall), .elbo-detail strong values
  (accuracy, complexity), .elbo-progress .elbo-iter values (iteration scores)
- ELBO dimensions: .elbo-dims → .elbo-dim.top and .elbo-dim.bottom text
- Patterns: .patterns-list li → strong (name) + text (description)

#### prd.html
- Core design: .idea-card p content, .callout content
- Components: .components-grid .component-card → .component-tag class
  (new/modify), h4 (name), p (description)
- Data flow: .flow-steps .flow-step → .flow-num (step), p strong (action),
  p (full description)
- Connections: .connection-table tbody tr → td values (connection name,
  strength, load-bearing assessment)
- Build sequence: section with .section-label "Build Sequence" →
  .flow-step items with .flow-num, p strong, p text
- Quality profile: .quality-grid .quality-row → .quality-label (dimension),
  .quality-score (score)
- ELBO: same as index.html
- Open questions: .questions-list li → strong (title) + text (detail)
- Emergent findings: .surprise-card → data-num, h4 (title), p (description)
- Immune memory: .antibody-critical .antibody-row → .ab-failure-side p
  (default), .ab-prevention-side p (evidence-based choice)

#### rejection.html
- Assumptions overturned: .assumption-card → .assumed p (assumed),
  .found p (evidence)
- Anti-patterns: .findings-grid .finding-item (first section) →
  h4 (name), p (description)
- Migration risks: .findings-grid .finding-item (second section) →
  h4 (name), p (description + severity)
- Rejected patterns: .patterns-list li → strong (name), text (description)

### If Markdown format

For each file, locate sections by markdown headings (## and ###).
Content under each heading is the section body. Extract verbatim.

#### meta.json (if present)
Same as HTML format.

#### If no meta.json
Extract PRODUCT/SLUG/DATE/STATUS from the document title (# heading),
YAML frontmatter (if present), or report MISSING.

#### PRD content (prd.md or combined file)
Map markdown headings to output schema fields:
- **## Executive Summary** or **## Summary** → EXECUTIVE_SUMMARY
  - Blockquote or callout (> text) → core_insight
  - Paragraph text → narrative
- **## Components** → COMPONENTS list
  - Each ### or list item → one component
  - Look for [new] or [modify] tags, or "New:" / "Modify:" prefixes → type
- **## Data Flow** → DATA_FLOW list
  - Numbered list items or ### sub-sections → steps
- **## Connections** → CONNECTIONS list
  - Table rows or list items → entries with name, strength, load_bearing
- **## Build Sequence** → BUILD_SEQUENCE list
  - Numbered list items or ### sub-sections → steps
- **## Quality** or **## Quality Profile** → QUALITY_PROFILE list
  - Table rows or list items → dimension + score
- **## ELBO** → ELBO scores
  - Look for score/accuracy/complexity values in text, table, or list
- **## Open Questions** → OPEN_QUESTIONS list
- **## Emergent Findings** or **## Surprises** → EMERGENT_FINDINGS list
- **## Immune Memory** or **## Antibodies** → IMMUNE_MEMORY list
  - Pairs of default/evidence-based entries

#### Rejection content (rejection.md or combined file)
Map markdown headings to output schema fields:
- **## Assumptions Overturned** → ASSUMPTIONS_OVERTURNED list
  - Pairs of assumed/found entries
- **## Anti-Patterns** → ANTI_PATTERNS list
- **## Migration Risks** → MIGRATION_RISKS list
- **## Rejected Patterns** → REJECTED_PATTERNS list

#### Combined file handling
If a single .md file contains both PRD and rejection content, extract both.
Look for clear section boundaries (## headings) to separate PRD from rejection
content. Common patterns:
- A "## Rejection Framework" or "## Boundaries" heading separating the two
- PRD sections first, rejection sections after
- Explicit labels like "# PRD" and "# Rejection"

## Output Format

PRODUCT: [title]
SLUG: [slug]
DATE: [date]
STATUS: [status]
INPUT_FORMAT: [html | markdown]

EXECUTIVE_SUMMARY:
  core_insight: [verbatim from callout/blockquote]
  narrative: [verbatim from paragraphs]

ELBO:
  score: [number]
  accuracy: [number]
  complexity: [number]
  iterations: [number]
  top_dimensions: [list with scores]
  bottom_dimensions: [list with scores]

COMPONENTS: [ordered list]
  - name: [text]
    type: [new | modify]
    description: [text]

DATA_FLOW: [ordered list]
  - step: [number]
    action: [text]
    description: [text]

CONNECTIONS: [ordered list]
  - name: [text]
    strength: [Strong | Moderate | Weak]
    load_bearing: [text]

BUILD_SEQUENCE: [ordered list]
  - step: [number]
    name: [text]
    description: [text]

QUALITY_PROFILE: [ordered list]
  - dimension: [name]
    score: [number]

OPEN_QUESTIONS: [ordered list]
  - title: [text]
    detail: [text]

EMERGENT_FINDINGS: [ordered list]
  - number: [number]
    title: [text]
    description: [text]

IMMUNE_MEMORY: [ordered list]
  - default: [text]
    evidence_based: [text]

ASSUMPTIONS_OVERTURNED: [ordered list]
  - assumed: [text]
    found: [text]

ANTI_PATTERNS: [ordered list]
  - name: [text]
    description: [text]

MIGRATION_RISKS: [ordered list]
  - name: [text]
    description: [text]
    severity: [number if present]

REJECTED_PATTERNS: [ordered list]
  - name: [text]
    description: [text]

MISSING_SECTIONS: [list any sections not found]
```

## Boundary Spec

### Ingest → Plan

- **Type contract**: Structured text in the output schema above. All fields
  are strings, numbers, or ordered lists of objects.
- **Tool schema**: Free-form text output matching the schema template.
- **Forwarded context**: All content from source files, restructured. No content
  is dropped — only reformatted from HTML/MD to structured text.
- **Implicit assumptions**: Plan assumes all sections are present or explicitly
  marked MISSING. Plan assumes component names match between COMPONENTS and
  BUILD_SEQUENCE lists.
- **Transformation**: Reshaping (HTML/MD → structured text). Lossless for
  content — every data point in the source appears in the output.
- **Format invariant**: The output schema is identical regardless of whether
  the input was HTML or markdown. Downstream blocks never see the input format.

## Rejection Boundary

- Do not interpret findings or suggest implementation approaches
- Do not skip sections — report MISSING instead
- Do not rewrite or summarize content — extract verbatim
- Do not read files outside the product directory
