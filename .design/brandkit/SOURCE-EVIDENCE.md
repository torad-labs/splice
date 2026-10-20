# Source evidence

Where each value in the kit came from. Sources are the existing Splice files on torad-server and the 0.4.0 announcement video (`splice-0.4.0-announce.mp4`). Where a source existed, the kit uses its value.

| Value | Source |
| --- | --- |
| Night `#1D2140`, Night Low `#141731`, Shell `#1C1610`, Ink `#07050A`, Cream `#EFE2C6`, Cream Body and Cream Mute, Key Light `#FFD79A`, day text colours, Live and Limit | `Splice-Animator/design/console/console.css` (`--wall`, `--wall-low`, `--obj`, `--ink`, `--text*`, `--key-light`, `--live`, `--limit`, day theme) |
| Provider colours (Claude, GPT, Grok, Kimi, Muse, Router, Local, DeepSeek) | Model colours in `console.css`, which come from `torad-tokens.css --cel-*`. Frames from the video match within 1 to 3 RGB steps (Claude #ED8E57, Grok #6DACFD, Muse #B797F4) |
| Plate `#382E25` | The plate behind "splice" in the video title at 0:06, sampled |
| Paper `#FBF1DF` | The video's end card at 0:50, sampled |
| Dusk `#724C5E` | The end-card background at 0:50, sampled |
| Fraunces, Source Serif 4, IBM Plex Mono | `console.css` (`--font-lead`, `--font-name`, `--font-data`) and `social/splice-grok46-card.html`. The "splice" title in the video matches Fraunces at about wght 450 to 500, opsz 144 |
| Ink outline, offset shadow, 9 px corners, no pills, noise texture, diagonal light band | The header comment and `body::before` / `body::after` rules in `console.css` |
| 240 ms and 420 ms durations, ease curves | `console.css` (`--t-snap`, `--t-move`, `--e-out`, `--e-io`). `--e-out` is the same curve Temple uses |
| Updating drawn objects every 2nd frame, holds on arrival, one move at a time, no fake progress, depth layers | Splice-Animator's motion notes (`Splice-Animator/me/CRAFT.md`) |
| Terminal cards, the rail with sockets, labels on objects, row of five sessions | Frames from the video, 0:13 to 0:53 |
| Copy lines | `/workspace/marketing/splice-v0.4.0-drafts.md` and the video's end card |
| Logo animation timing | Kit motion rules above, applied to the logo. Drop, cable draw and plug light follow the order in `motion/splice-mark-sting.mp4` |
| Night-side console roles: Card High `#241A12`, Socket `#120D09`, track and track-line alphas, the JSON string green `#A9CF8E`, the six chart hues | `console.css` and `settings.css` and `reqview.css` as they stood on October 19, 2026, before the console took the kit tokens (`--obj-high`, `--socket`, `--track`, `--track-line`, `--store-1` to `--store-6`, `.json .str`) |
| Light-mode additions: the day provider colours, Paper Low, Paper High, the day chart hues, the day track-line alpha, the day on-fill text | Derived on October 19, 2026 for Marcos’s light-mode requirement. Provider and chart day values keep the night hue and lose lightness until they hold 4.5:1 on Paper Low (Router moves to a quiet brown because its hue merges with Claude’s at that lightness). Paper Low and High are tints of Paper chosen so Day Mute, Key on Paper and Day Limit hold 4.5:1 on them. Every pair is measured in `tokens/splice.contrast.json` |

Fonts were downloaded from the google/fonts repository (Source Serif 4) and the system's Google Fonts copies (Fraunces, IBM Plex Mono). All three use the SIL OFL 1.1; licence files are in `fonts/`.

Splice had no logo before this kit. The console and the video use the word "splice" typed in Fraunces.
