# Markdown Extraction Reference

Heading and section mapping for crystallized product markdown. Used by the
Ingest phase to extract structured data from markdown product files.

## Contents

- [File Map](#file-map)
- [Extraction Schema](#extraction-schema)
  - [prd.md](#prdmd)
  - [rejection.md](#rejectionmd)
  - [Combined file](#combined-file)
  - [meta.json](#metajson)
- [Heading Aliases](#heading-aliases)
- [Ingest Output Schema](#ingest-output-schema)

## File Map

| File | Purpose | Key Sections |
|------|---------|-------------|
| `prd.md` | Implementation plan | Components, data flow, connections, build sequence, quality profile, ELBO, open questions, emergent findings, immune memory |
| `rejection.md` | Boundaries + rejected approaches | Assumptions overturned, anti-patterns, migration risks, rejected patterns |
| Combined `.md` | Single file containing both PRD and rejection content | All of the above |
| `meta.json` | Structured metadata (optional for MD input) | title, slug, type, subtitle, date, status |

## Extraction Schema

### prd.md

| Section | Heading Pattern | Data Points |
|---------|----------------|-------------|
| Title | `# ...` (H1) | Document title — use as PRODUCT if no meta.json |
| Executive Summary | `## Executive Summary` or `## Summary` | Blockquote `>` (core insight), paragraph text (narrative) |
| Components | `## Components` | List items or `###` sub-headings; `[new]`/`[modify]` tags or prefix labels for type |
| Data Flow | `## Data Flow` | Numbered list or `###` sub-sections with step/action/description |
| Connections | `## Connections` | Markdown table rows or list items with name/strength/load-bearing |
| Build Sequence | `## Build Sequence` | Numbered list or `###` sub-sections |
| Quality Profile | `## Quality` or `## Quality Profile` | Table rows or list items with dimension/score |
| ELBO | `## ELBO` | Score, accuracy, complexity values — may appear as list, table, or inline |
| Open Questions | `## Open Questions` | List items with title (bold) and detail text |
| Emergent Findings | `## Emergent Findings` or `## Surprises` | Numbered items with title and description |
| Immune Memory | `## Immune Memory` or `## Antibodies` | Paired entries: default behavior vs evidence-based choice |

### rejection.md

| Section | Heading Pattern | Data Points |
|---------|----------------|-------------|
| Assumptions Overturned | `## Assumptions Overturned` or `## Assumptions` | Paired entries: assumed vs found |
| Anti-Patterns | `## Anti-Patterns` or `## Anti Patterns` | List items with name (bold) and description |
| Migration Risks | `## Migration Risks` or `## Risks` | List items with name, description, optional severity |
| Rejected Patterns | `## Rejected Patterns` | List items with name (bold) and description |

### Combined file

When PRD and rejection content appear in a single file, look for these
boundary markers (in order of precedence):

1. `# PRD` and `# Rejection` H1 headings
2. `## Rejection Framework` or `## Boundaries` heading
3. Section headings from rejection (Assumptions Overturned, Anti-Patterns, etc.)
   appearing after PRD sections

Everything before the rejection boundary is PRD content. Everything after is
rejection content.

### meta.json

Same as HTML format:

```json
{
  "title": "string",
  "slug": "string",
  "type": "string",
  "subtitle": "string",
  "date": "string (YYYY-MM-DD)",
  "status": "string"
}
```

When meta.json is absent, extract metadata from:
1. YAML frontmatter (`---` delimited block at file start)
2. H1 heading → title
3. Report remaining fields as MISSING

## Heading Aliases

The crystallize agent may use slightly different heading names depending on
the run. These aliases map to the same output field:

| Output Field | Accepted Headings |
|-------------|-------------------|
| EXECUTIVE_SUMMARY | Executive Summary, Summary, Overview |
| COMPONENTS | Components, Component List, Architecture |
| DATA_FLOW | Data Flow, Flow, Pipeline |
| CONNECTIONS | Connections, Dependencies, Integration Points |
| BUILD_SEQUENCE | Build Sequence, Build Order, Implementation Order |
| QUALITY_PROFILE | Quality, Quality Profile, Quality Dimensions |
| OPEN_QUESTIONS | Open Questions, Questions, Unresolved |
| EMERGENT_FINDINGS | Emergent Findings, Surprises, Unexpected Findings |
| IMMUNE_MEMORY | Immune Memory, Antibodies, Lessons |
| ASSUMPTIONS_OVERTURNED | Assumptions Overturned, Assumptions, Wrong Models |
| ANTI_PATTERNS | Anti-Patterns, Anti Patterns, Pitfalls |
| MIGRATION_RISKS | Migration Risks, Risks, Risk Assessment |
| REJECTED_PATTERNS | Rejected Patterns, Rejected Approaches, What Not To Do |

## Ingest Output Schema

Identical to the HTML extraction output schema. The output format is the same
regardless of input format — downstream blocks never see the source format.

See [`html-extraction.md`](html-extraction.md) → Ingest Output Schema for the
full template.
