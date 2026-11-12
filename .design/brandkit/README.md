# Splice brand kit v1.0

Approved by Marcos on October 19, 2026 and kept in this repository at `.design/brandkit/`. The console under `app/src/main/resources/console/` uses its mark, favicon, tokens and fonts.

## What this copy leaves out
This folder holds the design files and stays near 14 MB. The full kit stays with the kit source in `brandkit-incoming/splice-brandkit/`, beside the repository checkout (`../brandkit-incoming/splice-brandkit/`).
- `src/`: the Python scripts that generate every file. The gate refuses a tracked `.py` file, so the generators stay with the kit source.
- `motion/`: the flow loop and mark sting clips, and the logo animation (intro, idle, outro) in MP4, GIF, WebM, Lottie and SVG, with their `frames/`, `keyframes/` and `posters/` folders and the `-alpha.webm` clips. Together they would take this folder past 15 MB.
- `brand-overview.html` keeps its links to `motion/` and `src/`. Those links resolve only in the full copy.

Splice is a free, open-source proxy that runs on the user's computer. It lets Claude Code use other models and accounts (GPT, Grok, Kimi and others) and exposes settings for tool calls, compaction, caching and usage.

Product lines from the 0.4.0 announcement video:
- "The plumbing for your coding agents."
- "Free and open source. Runs on your computer."

## Where to start
- `brand-overview.html`: index page with every logo file, colour, font and animation. Open it in a browser; links are relative and work offline.
- `splice-brand-guide.pdf`: the full guide, 14 pages, A4 landscape.
- `GUIDELINES.md`: the same rules as plain text.

## Contents
| Path | Contents |
| --- | --- |
| `splice-brand-overview.png` | Overview image, 1600×1000 |
| `splice-brand-typography.png` | Type specimen, 1600×1000 |
| `splice-brand-guide.pdf` | Guide: about, logo, logo usage, colour, provider colours and contrast, typography, type scale, shapes and components, motion, logo animation, writing, applications, contents |
| `brand-overview.html` | Index page |
| `GUIDELINES.md` | Rules in text |
| `SOURCE-EVIDENCE.md` | Source file or video frame for each value in the kit |
| `logos/` | Mark (night, day, flat night, flat day, one-colour cream, one-colour ink), wordmark (cream, ink), horizontal and stacked lockups (night, day). SVG with outlined paths, plus PNG |
| `icons/` | `favicon.svg` with 16, 32 and 48 px PNG; `favicon-day.svg`, the same tile in the day inks (added October 19, 2026); `splice-app.svg` with 256, 512 and 1024 px PNG |
| `tokens/` | `splice.css` (CSS custom properties), `splice.tokens.json` (DTCG format), `splice.contrast.json` (measured contrast ratios). Light mode additions of October 19, 2026: day provider colours, Paper Low and Paper High, the socket, the roles the console draws, chart hues and the contrast rows for them. The generator in the full kit does not write them yet |
| `fonts/` | Fraunces, Source Serif 4, IBM Plex Mono (TTF), `splice-fonts.css`, SIL OFL 1.1 licence files |
| `social/` | X avatar 400×400, X header 1500×500, GitHub social preview 1280×640 |
| `templates/` | Release card 1200×630 with sample copy from the 0.4.0 drafts |
| `motion/` | Full copy only. `splice-flow-loop` (4 s loop, 1280×720) and `splice-mark-sting` (2 s, 720×720) as MP4 and GIF; still frames in `motion/frames/` |
| `motion/logo/` | Full copy only. Logo animation: intro, idle loop and outro. See below |
| `src/` | Full copy only. Python scripts that generate every file above from `src/palette.py` |

## Logo animation (`motion/logo/`, full copy only)
Three clips, each at 1920×1080 (horizontal lockup) and 1080×1080 (stacked lockup; the square idle clip shows the mark only). All are 24 fps.

| Clip | Length | Plays | Use for |
| --- | --- | --- | --- |
| `splice-logo-intro-*` | 3.5 s | once, ends on the lockup | Start of videos and talks, first load of the site |
| `splice-logo-idle-*` | 3.0 s | loops | Animated avatar, site header, README banner, loading screen |
| `splice-logo-outro-*` | 3.5 s | once, ends on the end card | End of videos and demos. Shows the tagline, "Free and open source. Runs on your computer." and `github.com/torad-labs/splice` |

Formats for each clip:
- `.mp4`: H.264 High, yuv420p, Night background. Use in video editors and on social sites.
- `-alpha.webm`: VP9 with an alpha channel, transparent background. Use on web pages over any background from the palette.
- `.gif`: 12 fps, 640 px wide (540 px for square). Use where video does not play, for example a README. The idle GIF loops; the others play once.
- `frames/<clip>/0000.png ...`: transparent PNG frames for editors that do not read WebM alpha.
- `.json` (intro and idle only): Lottie, transparent background. Tested in lottie-web 5.12.2.
- `.svg` (intro and idle only): animated SVG (SMIL), transparent background. Tested in Chrome, embedded with `<img>` and inline. Firefox and Safari support SMIL but have not been tested.
- `posters/` and `keyframes/`: still images used by the guide and the index page.

The outro has no Lottie or SVG version because its tagline is set as live text in the renderer. Use the MP4, WebM or PNG frames.

## Rebuilding
Run this from the kit root of the full copy in `brandkit-incoming/splice-brandkit/`, not from this folder:

```
python3 src/tokens.py && python3 src/logos.py && python3 src/export_png.py \
  && python3 src/social.py && python3 src/motion.py \
  && python3 src/logo_anim.py && python3 src/logo_lottie.py && python3 src/logo_svg.py \
  && python3 src/pages.py png pdf
```

Requires Python 3 with fontTools, uharfbuzz and playwright (using a local Chrome), and ffmpeg with libx264 and libvpx-vp9. `src/logo_anim.py` takes about 5 minutes.

## Status and gaps
- Approved as v1.0. The copy lines stay proposals until a release uses them.
- No trademark search has been done for the name or the mark.
- Colours are sRGB only. There are no print (CMYK) values.
- Type has been checked for Latin script only.
- Provider and model names (Claude, GPT, Grok, Kimi, Muse, DeepSeek) appear as plain text with Splice's own colours. No provider logos are used. Splice is not affiliated with any model provider.
- Splice is a Torad Labs project. The Temple kit is a separate kit with its own colours and type; this kit shares only the ease curve and some layout conventions with it.
