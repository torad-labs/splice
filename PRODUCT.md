# Product

## Register

product

## Users

The operator (a single developer) running the splice proxy stack on their own loopback machine, driving Claude Code against non-Anthropic backends (ChatGPT Codex, xAI Grok) through decomposed proxy "heads" (claudex/codex, grok). Their context: mid-session, often with live coding sessions running, wanting to see and steer the fleet without leaving the terminal for long.

## Product Purpose

splice is a control plane for the proxy heads. The dashboard (spliced, loopback :3096) is the single place to see each head's live status, start / stop / restart it, watch 5-hour usage against a soft cap (which never blocks), manage per-head auth (ChatGPT sign-in, credential refresh), and edit the shared proxy config with layer provenance. Success is that the operator trusts the fleet at a glance and can act on any head without reading logs or restarting by hand.

## Brand Personality

Instrument, precise, quiet. Three words: engraved, exact, calm. The interface is an 18th-century instrument bench (the Torad plate system): iron-gall ink on rag, cinnabar as the one charged accent, measurements in prussian / verdigris / ormolu. It reads like a scientific instrument panel, not a SaaS console. Lowercase, terse, data-forward. It never shouts; the loudest thing on screen is a real warning.

## Anti-references

Generic observability dashboards (Grafana, Datadog): card-grid sprawl, neon on black, gratuitous gauges. No hero metrics, no rounded SaaS cards with icon + heading + text, no purple gradients, no decorative motion. If it looks like every proxy admin panel, it has failed.

## Design Principles

1. Every number on screen comes from the live API; nothing is decorative or faked.
2. Measurement is ink, chrome is structure: color belongs to data (status, warn), never to backgrounds or borders as flair.
3. Read-only by default; the few mutating controls (start / stop / restart) read deliberate and confirm before firing.
4. Design every state a control can enter (loading, empty, nominal, warn, error, stale), not just the happy path.
5. Restraint over surprise: N heads read as identical instruments on one bench; consistency is the feature.

## Accessibility & Inclusion

WCAG AA for text and controls in BOTH registers (paper / light and observatory / dark); the tokens are AA-tuned and must stay so. Focus is always visible (vermilion focus ring). Motion is functional only and respects prefers-reduced-motion. Color is never the sole signal: status carries a text label, not just an ink.

<!-- Register is certain (a loopback operator console). Users/purpose/brand/anti-refs/principles inferred from the repo (README, AGENTS.md), the Torad design tokens, and the frontend-design + torad-labs design passes; adjust any that miss your intent. -->
