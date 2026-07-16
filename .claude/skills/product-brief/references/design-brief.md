# Design Brief — Product Brief Visual Identity

## Principle
The brief's design serves the reader's decision-making process while being
a pleasure to look at. Every visual element either accelerates comprehension
or creates energy that keeps the reader moving. The design should feel like
receiving a Morning Brew or Milk Road edition — you WANT to read it.

## Design DNA

**Morning Brew / Milk Road energy:**
- Bold section headers that pop
- Generous whitespace between sections (breathing room, not density)
- Playful accent colors — not one muted corporate palette
- Visual personality: the brief has a voice in its design, not just its words
- Scannable blocks with strong visual hierarchy
- At-a-glance metric strips that feel like a dashboard, not a spreadsheet
- Section emojis or icons as visual anchors (optional, domain-appropriate)

**What makes newsletter design work:**
- Contrast: dark headers against light backgrounds, colored callouts against white
- Rhythm: sections alternate between dense data and breathing room
- Personality: the design has opinions (bold colors, chunky type, rounded elements)
- Speed: readers scan first, read second — the scan path must tell the story

## Typography

- **Body font**: clean, modern sans-serif. Inter, Plus Jakarta Sans, or DM Sans.
  Size 15-16px, line-height 1.7. Comfortable, not cramped.
- **Headings**: bold, larger — 24-32px for section titles. Can use the same
  family at heavier weight or a display variant. Headings should POP.
- **Label/metadata font**: monospace for confidence tags, section labels,
  metrics. JetBrains Mono, DM Mono, or Fira Code. Small (11-13px), uppercase,
  letter-spaced — signals precision.
- **Pull quotes / callouts**: larger body font (18-20px), italic or medium weight.

## Color System

### Base palette
- Background: warm white (#FAFAF8) or very light warm gray — NOT pure white
- Text: rich near-black (#1A1A2E or #2D2D3F) — NOT corporate gray
- Muted text: (#6B7280)
- Card backgrounds: white (#FFFFFF) with subtle shadow for lift

### Accent palette (pick 2-3 per brief, domain-adapted)
The brief should have COLOR. Not one muted accent — a small palette that
creates visual energy:

**Default palette (no domain specified):**
- Primary: electric blue (#3B82F6) or indigo (#6366F1)
- Secondary: warm coral (#F97316) or amber (#F59E0B)
- Tertiary: emerald (#10B981)

**Domain-adapted palettes:**
- legal-tech → navy (#1E3A5F) + gold (#D4A84B) + slate (#64748B)
- fintech → teal (#0D9488) + electric blue (#3B82F6) + amber (#F59E0B)
- devtools → violet (#7C3AED) + cyan (#06B6D4) + lime (#84CC16)
- healthtech → emerald (#059669) + sky (#0EA5E9) + rose (#F43F5E)
- edtech → indigo (#4F46E5) + orange (#F97316) + teal (#14B8A6)
- proptech → warm brown (#92400E) + amber (#D97706) + sage (#65A30D)
- consumer → coral (#F97316) + violet (#8B5CF6) + teal (#14B8A6)
- creator-economy → pink (#EC4899) + indigo (#6366F1) + amber (#F59E0B)
- climate → emerald (#059669) + sky (#0284C7) + amber (#D97706)

### Confidence colors (mandatory — see confidence-system.md)
These are the primary visual system. Bold, not pastel:
- Verified: emerald (#059669) on light green bg (#ECFDF5)
- Strong: teal (#0D9488) on light teal bg (#F0FDFA)
- Moderate: amber (#D97706) on light amber bg (#FFFBEB)
- Emerging: orange (#EA580C) on light orange bg (#FFF7ED)
- Speculative: red (#DC2626) on light red bg (#FEF2F2)
- Unknown: slate (#64748B) on light gray bg (#F8FAFC)
- Contested: purple (#7C3AED) on light purple bg (#F5F3FF)

Confidence tags should be pill-shaped (rounded, padded), not just colored text.

## Layout

### Page structure
```
Hero (full-width, bold background color or gradient)
Decision Dashboard (full-width, metric strip with personality)
Content (centered, max-width 780px)
  Section cards (elevated, rounded, spaced)
  Tables (clean but styled)
  Callout blocks (colored left-border or full-bg)
Footer (full-width, muted)
```

### Hero
- Bold background: primary accent color, or a gradient from primary to secondary
- White or light text on dark background
- Title: large (36-48px), bold
- Subtitle: lighter weight, slightly transparent
- Optional: domain icon or minimal illustration
- NOT a plain white header with dark text

### Decision Dashboard
Morning Brew "market snapshot" energy:
- Background: slightly lifted (white card on warm-white page, or light accent tint)
- Metric cells: each in a mini-card with rounded corners (8-12px)
- Each cell: label (monospace, small, muted) + value (bold, large) + confidence pill
- Grid layout: 3 columns on desktop, 2 on tablet, 1 on mobile
- Subtle separators between cells (not heavy borders)

### Section cards
Each section is visually distinct and elevated:
- White background with subtle box-shadow (0 1px 3px rgba(0,0,0,0.1))
- Rounded corners (8-12px)
- Section label: uppercase, small, monospace, in accent color
- Section heading: bold, dark, generous size
- Content: body font, comfortable reading width
- Margin between cards: 24-32px (breathing room)
- Optional: colored left border (4px) in section's accent color

### Callout blocks
For key insights, pull quotes, or important data:
- Full-width within the card
- Light accent background (10% opacity of accent color)
- Left border (4px) in accent color
- Slightly larger text
- These break up walls of text and highlight what matters

### Tables
- Rounded corners on the table container
- Header row: accent background (light), bold monospace labels
- Alternating row backgrounds (very subtle warm gray)
- Cell padding generous (12-16px)
- No heavy borders — hairlines or none, with row backgrounds doing the work

### Sticky navigation
- Sidebar on desktop: pill-shaped nav items, highlight active section
- Top bar on mobile: horizontal scroll, pill buttons
- Accent color for active state

## Interactive Elements

### Evidence tooltips
- Trigger: hover (desktop), tap (mobile)
- Style: floating card with rounded corners, subtle shadow, accent-tinted header
- Content: source name, linked URL, date, confidence pill
- NOT a browser-native tooltip — a styled floating card

### Expert Panel
- Use `<details><summary>` for expandable sections
- Summary: expert role pill (colored) + verdict pill (confidence color)
- Expanded: full response with comfortable reading style
- Convergent additions: always visible, highlighted in a callout block

### Scenario cards
- Each scenario in a rounded card with light colored background
- Trigger → Impact → Response as a visual flow (arrows or step indicators)
- Probability tag as a confidence pill

## Responsive Design

- Max content width: 780px, centered
- Mobile breakpoint: 640px
  - Hero: full-width, slightly less padding
  - Dashboard: stack metrics to single column
  - Cards: full width, reduced padding, same rounded style
  - Tables: horizontal scroll wrapper with scroll hint
  - Nav: horizontal pill bar at top
- Font sizes: use clamp() for headings
- Touch targets: minimum 44px

## Visual Personality Checklist

Before finalizing the HTML, verify:
- [ ] Hero has a bold background (color or gradient), not plain white
- [ ] At least 2 accent colors create visual variety
- [ ] Confidence tags are pill-shaped, not just colored text
- [ ] Section cards have rounded corners and shadow (lifted, not flat)
- [ ] Generous spacing between sections (not cramped)
- [ ] At least one callout block breaks up text density
- [ ] Tables have styled headers, not plain HTML defaults
- [ ] The overall feel is "I want to read this" not "I have to read this"

## What NOT to Do

- No plain white background everywhere (use warm white #FAFAF8 as base)
- No flat, borderless sections (cards need lift — shadow or border)
- No single-color palette (2-3 accent colors minimum)
- No tiny, timid typography (headings should be bold and large)
- No walls of text without visual breaks (use callouts, metrics strips, cards)
- No generic corporate consulting deck aesthetic
- No "Generated by..." watermark — the brief serves the product, not the tool
