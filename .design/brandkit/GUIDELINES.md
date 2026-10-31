# Splice brand guidelines, brand kit v1.0

Rules for using the Splice logo, colours, type, components, motion and copy. Values come from `tokens/splice.css`; sources are listed in `SOURCE-EVIDENCE.md`.

## 1. About Splice
Splice is a free, open-source proxy that runs on the user's computer. It lets Claude Code use models and accounts the user already has, and exposes settings for tool calls, compaction, caching and usage. The audience is developers who already use Claude Code.

Splice and Temple are both Torad Labs projects. They share:
- dark backgrounds by default
- monospace type for numbers, commands and code
- the ease-out curve `cubic-bezier(.22,1,.36,1)`

Do not use Temple's teal, glass effects or Temple Rounded in Splice assets. Do not put the Splice mark on Temple assets.

## 2. Name and wordmark
- Write **Splice** in sentences. Write `splice` for the command and in the wordmark.
- The wordmark is lowercase `splice` set in Fraunces (wght 480, opsz 144, SOFT 0, WONK 0), supplied as outlined paths in `logos/splice-wordmark-*.svg`.
- Do not retype the wordmark, capitalise it, change its letter spacing or set it in another font.

## 3. Mark
The mark is a cable shaped like a lowercase s, with a plug at each end, on a square plate. Use the SVG files; do not redraw it.

| File | Use on |
| --- | --- |
| `splice-mark-night.svg` | Night, Dusk and dark photos. Plate fill, Cream cable, Key Light plugs, Ink shadow |
| `splice-mark-day.svg` | Paper and other light backgrounds |
| `splice-mark-flat-night.svg`, `splice-mark-flat-day.svg` | Small sizes and UI. No shadow |
| `splice-mark-mono-cream.svg`, `splice-mark-mono-ink.svg` | One-colour printing, embossing, single-colour UI. Cable and plugs are cut out of the plate |
| `icons/favicon.svg` | 16 to 32 px. No shadow, thicker cable |
| `splice-horizontal-*.svg` | Headers, nav bars, cards |
| `splice-stacked-*.svg` | Square spaces and end cards |

Keep the spacing between mark and wordmark as supplied in the lockup files.

**Clear space:** one quarter of the plate width on every side, measured from the plate edge (not from the shadow).

**Minimum sizes:**
| Asset | Minimum |
| --- | --- |
| Mark with shadow | 48 px |
| Flat mark | 24 px |
| Favicon | 16 to 32 px |
| Wordmark | 72 px wide |
| Horizontal lockup | 28 px tall |

**Do not:**
- stretch, skew or rotate the mark
- change the cable or plug colours, including to a provider colour
- blur the shadow or move it to any side other than down and right
- make the plate round or pill-shaped
- add gloss, gradients or glass effects
- place the mark on busy images or on provider colour stripes
- animate the mark next to code the reader needs to read

## 4. Colour
| Name | Hex | Use |
| --- | --- | --- |
| Night | `#1D2140` | Default background for every dark surface. About 70% of a frame |
| Night Low | `#141731` | Second background: side navigation, footers, table headers |
| Plate | `#382E25` | Logo plate and the rail in diagrams. Large dark objects |
| Shell | `#1C1610` | Terminal screens, stat boxes, dark cards |
| Ink | `#07050A` | 3 px outlines and the 6 px offset shadow on every object |
| Paper | `#FBF1DF` | Light cards, tooltips and end cards. Light-mode background |
| Cream | `#EFE2C6` | Body text and the wordmark on Night |
| Key Light | `#FFD79A` | Accent. Primary button, focus ring, the active session. One use per view |
| Paper Low | `#EBE1CA` | Second background in light mode: table headers, sunk fields, code blocks. Added October 19, 2026 |
| Paper High | `#F6EDDB` | Raised surfaces in light mode: menus. Added October 19, 2026 |
| Dusk | `#724C5E` | Alternate background for end cards and the GitHub preview only |

Text colours:
- On Night: Cream `#EFE2C6` for body, Cream Body `#CBBD9D` for secondary text, Cream Mute `#9C8F70` for captions.
- On Paper: Day Ink `#241D14` for body, Day Body `#4E4434` for secondary text, Day Mute `#6B604B` for captions, Key on Paper `#8A5A12` for accent text. Key Light itself is too pale to read on Paper.

**Provider colours.** Each provider has one fixed colour, used for its cable, its session card shadow and its model dot.

| Provider | Hex | Token |
| --- | --- | --- |
| Claude | `#EE8F58` | `--splice-cable-claude` |
| GPT | `#00C5BE` | `--splice-cable-gpt` |
| Grok | `#6EADFE` | `--splice-cable-grok` |
| Kimi | `#E3B341` | `--splice-cable-kimi` |
| Muse | `#B896F3` | `--splice-cable-muse` |
| Router | `#F3B27A` | `--splice-cable-router` |
| Local | `#7BC47F` | `--splice-cable-local` |
| DeepSeek | `#EC93C3` | `--splice-cable-deepseek` |

Do not use provider colours as backgrounds, as status colours or as Splice brand colours.

**Provider colours on Paper.** The values above are 1.6 to 2.2:1 on Paper. On Paper, Paper Low and Paper High use the day values, which keep the hue and hold 4.5:1 or more on Paper Low and darker (5.3:1 or more on Paper). Router has no hue of its own left at that lightness, so its day value is a quiet brown. Text on a solid provider fill is Day Ink on the night values and Paper on the day values (`--splice-on-fill`).

| Provider | Day hex | Token |
| --- | --- | --- |
| Claude | `#A54711` | `--splice-cable-claude-day` |
| GPT | `#006F6B` | `--splice-cable-gpt-day` |
| Grok | `#015DD3` | `--splice-cable-grok-day` |
| Kimi | `#7E5E13` | `--splice-cable-kimi-day` |
| Muse | `#7837E8` | `--splice-cable-muse-day` |
| Router | `#7E5B44` | `--splice-cable-router-day` |
| Local | `#327035` | `--splice-cable-local-day` |
| DeepSeek | `#B82072` | `--splice-cable-deepseek-day` |

**Status colours.** Always show a text label with them.
| Status | On Night | On Paper | Meaning |
| --- | --- | --- | --- |
| Live | `#5BB59C` | `#2C6555` | Connected, request succeeded, tests passed |
| Limit | `#F06A4C` | `#BB301A` | Rate limited, failed, stopped |
| Needs you | Key Light `#FFD79A` | Key on Paper `#8A5A12` | Waiting for the user to decide something |

**Contrast** (WCAG 2.2; full table in `tokens/splice.contrast.json`):
| Pair | Ratio |
| --- | --- |
| Cream on Night | 12.2:1 |
| Cream Mute on Night | 4.9:1 |
| Key Light on Night | 11.5:1 |
| Day Ink on Paper | 14.9:1 |
| Key on Paper | 5.3:1 |
| Lowest provider colour on Night | 6.5:1 |

Body text needs at least 4.5:1. Text 24 px and larger needs at least 3:1. Borders and other marks that identify a control need at least 3:1.

In light mode every day text colour, every day status colour, every day provider colour and every chart hue holds 4.5:1 on Paper, Paper Low and Paper High, and the field rim (`--splice-track-line`) holds 3:1 on all three. `tokens/splice.contrast.json` lists each pair.

## 5. Typography
All three families use the SIL Open Font License 1.1 and are in `fonts/`. Load them with `fonts/splice-fonts.css`.

| Family | Use | Settings |
| --- | --- | --- |
| Fraunces | Headlines and the wordmark | opsz auto, SOFT 0, WONK 0, weight 500 to 600 |
| Source Serif 4 | Body text, UI labels, captions | 400 for body, 500 for UI, 600 for titles |
| IBM Plex Mono | Numbers, times, token counts, commands, session names, code | 400 to 600, tabular figures. Do not use for sentences |

**Type scale:**
| Style | Font and weight | Size / line height | Notes |
| --- | --- | --- | --- |
| Display | Fraunces 560 | 56–120 px / 0.95 | letter spacing −0.015em |
| Heading | Fraunces 580 | 32–44 px / 1.05 | |
| Title | Source Serif 4 600 | 20 px / 1.3 | |
| Body | Source Serif 4 400 | 17 px / 1.5 | 60 to 72 characters per line |
| UI | Source Serif 4 500 | 14 px / 20 px | |
| Data | IBM Plex Mono 500 | 13–15 px / 1.45 | |
| Label | IBM Plex Mono 500 | 11–12 px | uppercase, letter spacing +0.14em |

Use sentence case for headings and buttons. In diagrams, put labels on or next to the object they describe, not in a separate caption bar.

## 6. Shapes and components
- Every object has a 3 px Ink outline and a solid Ink shadow offset 6 px down and 6 px right.
- Terminal cards use their provider colour for the shadow instead of Ink.
- Corner radius: 9 px for objects, 7 px for elements inside an object. Do not use pill shapes.
- No gradients, blur or transparency effects on objects.
- Backgrounds are Night with one diagonal light band from the upper left and a fine noise texture (`feTurbulence`, opacity 0.09).
- Spacing uses multiples of 4 px: 4, 8, 12, 16, 24, 32, 48, 64, 96.
- Buttons: Ink outline and a 3 px shadow. On hover the shadow grows; on press it goes to 0 (90 ms). The primary button is Key Light with Day Ink text. Secondary buttons are Shell with Cream text.

## 7. Motion
Use motion to show where a request goes. Reference clips:
- `motion/splice-flow-loop.mp4`: 4 s loop. A request leaves a Claude Code session, stops at the rail and goes to one model.
- `motion/splice-mark-sting.mp4`: 2 s logo reveal for small UI and short clips.

| Rule | Value |
| --- | --- |
| Drawn objects | Update every 2nd frame (12 updates per second in 24 fps video). Layout moves and camera moves update every frame |
| Durations | 90 ms button press, 240 ms small UI change, 420 ms large or full-screen move |
| Easing | `cubic-bezier(.22,1,.36,1)` for UI, `cubic-bezier(.65,0,.35,1)` for full-screen moves |
| Holds | Hold the frame where something arrives for 2 to 4 drawings (83–167 ms). Animate one thing at a time |
| Accuracy | Do not show fake progress bars or spinners. Cutting straight from a command to its result is fine |
| Reduced motion | Honour `prefers-reduced-motion`. The duration tokens drop to 0 ms |

Text the reader needs to read does not animate on its own. It only moves with the object it sits on.

## 8. Logo animation
Files are in `motion/logo/`. Formats and sizes are listed in `README.md`.

| Clip | Length | Use for | Do not |
| --- | --- | --- | --- |
| Intro | 3.5 s, plays once | Start of videos and talks, first load of the site | Do not loop it. Do not play it more than once per page view |
| Idle | 3.0 s, loops | Animated avatar, site header, README banner, loading screen | Do not use it next to code or text the reader is working on. Use the static mark when `prefers-reduced-motion` is set |
| Outro | 3.5 s, plays once | End of videos and demos | Do not edit the tagline or URL in the video. Ask for a new render instead |

What happens in each clip:
- Intro: the plate drops in and squashes on landing (0 to 0.4 s), the cable draws from the top plug to the bottom plug (0.46 to 1.25 s), both plugs light (1.25 s), a pulse runs along the cable (1.4 to 2.0 s), then the mark moves aside and the wordmark appears in three steps (2.0 to 2.55 s).
- Idle: the plugs pulse slowly in brightness over 3 s, and one pulse runs along the cable each cycle. The first and last frames match.
- Outro: the lockup moves up, then the tagline, "Free and open source. Runs on your computer." and `github.com/torad-labs/splice` appear one line at a time. It ends on a still frame.

Use the alpha WebM or PNG frames to place the animation on a background other than Night. Use only backgrounds from the palette.

## 9. Writing
Write plainly and specifically. Say what Splice does, what the user gets and what to do next. Only describe features that are in a published release.

Use:
- "The plumbing for your coding agents."
- "Claude Code on the plans and keys you already have."
- "Splice puts control of your agents in your hands."
- "Free and open source. Runs on your computer."

Do not use:
- "Unlimited Claude for free." Splice uses plans and keys the user already pays for. It does not make usage free.
- "Break out of Anthropic's jail." Do not attack providers.
- "10x faster" or any speed or quality claim without a published benchmark.

Rules:
- Label staged or animated scenes as demos.
- Do not invent numbers, quotes or testimonials.
- Write provider and model names as plain text. Do not use provider logos.
- Where needed, add: "Splice is an independent open-source project and is not affiliated with or endorsed by any model provider."

## 10. Applications
| Asset | Size | Notes |
| --- | --- | --- |
| X avatar | 400×400 | Night mark. Fits inside the circle crop |
| X header | 1500×500 | Keep the bottom-left 300×170 px empty; the avatar covers it |
| GitHub social preview | 1280×640 | Dusk background with a Paper card. 40 px safe margin |
| Release card | 1200×630 | Label, Fraunces headline, Source Serif 4 subhead, Key Light mono link, request diagram on the right |
| Logo animation | 1920×1080, 1080×1080 | See section 8 |
