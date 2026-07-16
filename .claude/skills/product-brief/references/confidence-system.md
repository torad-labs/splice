# Confidence System

## Purpose
Every factual claim in the brief gets a confidence tag. Tags communicate
to the reader how trustworthy each piece of information is. This is the
primary trust-building mechanism — the brief admits its own uncertainty
instead of hiding it.

## Confidence Levels

| Level | Color (hex) | Text Color | Tag | Criteria |
|-------|-------------|------------|-----|----------|
| Verified | `#D4EDDA` bg | `#1A6B2E` | `VERIFIED` | 3+ independent sources agree, data is current (within 6 months) |
| Strong | `#D1ECF1` bg | `#0C6B7D` | `STRONG` | 2 independent sources agree, data is recent (within 12 months) |
| Moderate | `#FFF3CD` bg | `#7A5C00` | `MODERATE` | Single source, or extrapolated from adjacent/related data |
| Emerging | `#FFE0B2` bg | `#E65100` | `EMERGING` | Subject to active change (litigation, regulation, market shift) |
| Speculative | `#FFCCBC` bg | `#BF360C` | `SPECULATIVE` | No direct data. Logical inference from adjacent evidence |
| Unknown | `#E0E0E0` bg | `#616161` | `UNKNOWN` | No data found. Flagged for user to provide |
| Contested | `#E8D5F5` bg | `#6A1B9A` | `CONTESTED` | Sources disagree. Both positions presented |

## Modifier Tags

Added alongside primary confidence level when relevant:

| Modifier | Meaning | Example |
|----------|---------|---------|
| `EVOLVING` | Active legal/regulatory change, check frequently | "UPL enforcement landscape [EMERGING] [EVOLVING]" |
| `SEASONAL` | Data valid for specific period only | "Holiday booking patterns [STRONG] [SEASONAL]" |
| `REGIONAL` | Applies to specific geography, not universal | "TRAIGA sandbox [VERIFIED] [REGIONAL: Texas]" |
| `SELF-REPORTED` | Source is the company itself, not independent | "DoNotPay claims 2M disputes [MODERATE] [SELF-REPORTED]" |

## Tagging Rules

### Per-claim, not per-section
A single section can have VERIFIED market size alongside UNKNOWN
customer acquisition cost alongside EMERGING regulatory status.
Each claim gets its own tag.

### How to assign levels
1. Count independent sources for the claim
2. Check recency (within 6mo → current, 6-12mo → recent, >12mo → dated)
3. Check source quality (primary research > secondary reporting > opinion)
4. If sources disagree → CONTESTED
5. If no sources → UNKNOWN (never fabricate)

### Claim examples
```
The U.S. legal AI market is projected to reach $14B by 2035. [VERIFIED]
[Source: Grand View Research, 2025; Allied Market Research, 2024; Precedence Research, 2025]

80 million Americans face at least one civil legal issue annually. [STRONG]
[Source: Legal Services Corporation, Justice Gap Report, 2022]

Customer willingness to pay for privacy-guaranteed legal research is untested. [UNKNOWN]

Heppner may be appealed and potentially overturned. [EMERGING] [EVOLVING]
[Source: Legal analysis from multiple law firm blogs, Feb-Mar 2026]

DoNotPay reports over 2 million disputes handled. [MODERATE] [SELF-REPORTED]
[Source: DoNotPay marketing materials]
```

## Implementation by Format

### HTML
```html
<span class="claim">
  The market reaches $14B by 2035
  <span class="confidence verified">VERIFIED</span>
  <span class="source-tooltip" data-source="Grand View Research"
    data-url="https://..." data-date="2025" data-confidence="VERIFIED">
  </span>
</span>
```

CSS:
```css
.confidence {
  display: inline-block;
  font-family: monospace;
  font-size: 10px;
  letter-spacing: 0.1em;
  padding: 1px 6px;
  border-radius: 2px;
  margin-left: 4px;
  vertical-align: middle;
}
.confidence.verified { background: #D4EDDA; color: #1A6B2E; }
.confidence.strong { background: #D1ECF1; color: #0C6B7D; }
.confidence.moderate { background: #FFF3CD; color: #7A5C00; }
.confidence.emerging { background: #FFE0B2; color: #E65100; }
.confidence.speculative { background: #FFCCBC; color: #BF360C; }
.confidence.unknown { background: #E0E0E0; color: #616161; }
.confidence.contested { background: #E8D5F5; color: #6A1B9A; }
```

### DOCX
Confidence tags rendered as colored inline text using DOCX text coloring.
Source citations as footnotes.

### Markdown
```markdown
The market reaches $14B by 2035. [VERIFIED]
[Source: Grand View Research, https://..., 2025]
```

## Decision Dashboard Confidence

The Decision Dashboard at the top aggregates section-level confidence:
- For each metric, use the LOWEST confidence tag from its source claims
- Overall confidence is the mode (most common) of section-level tags
- Color-code each metric cell in the dashboard

Example:
```
Market: $14B [VERIFIED] | Timing: Post-Heppner [STRONG] |
Competition: Moderate [STRONG] | Tech Risk: Medium [MODERATE] |
Reg Risk: High [EMERGING] | Overall: STRONG
```
