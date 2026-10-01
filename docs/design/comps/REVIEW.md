# Review: each built page beside its comp

The built images were captured from the installed console on `127.0.0.1:3096`, in the day theme, on Oct 1, 2026, from 3:25 PM to 3:29 PM CT. Their source is `5dbe2987cea359fda421d291b3501af75c35b7a9`, whose complete [CI gate passed](https://github.com/torad-labs/splice/actions/runs/36917497021).

The daemon's open jar descriptor matched the installed artifact before capture and throughout the sequence. The served HTML matched the HTML inside that jar. The launcher matched the same source revision.

| Receipt | Value |
|---|---|
| Daemon | PID `1866283`, open jar `/proc/1866283/fd/4` |
| Jar SHA-256 | `09d5078839c63c2186fa5dec17f16b33afea1822a31f3c07987aa0d954c0c543` |
| Served HTML SHA-256 | `5804cb06d0bcd17a8d8811f6f37cd062620a836a65715a7f38cb54f68ba20f1e` |
| Launcher SHA-256 | `a9dea1737597dca3104157712b85872c7bd417a50316a0e4420954eed130b1d6` |
| Current images | Ten pages at 1440, 1920 and 3840 |
| Wide viewport | Exactly 3840 × 2060 for every wide image |

The local [capture receipt](../../../captures/console-5dbe2987c/console-capture-receipt.json) records each route, dimension, timestamp and image hash. Images and private capture records remain ignored by git. These are running-artifact captures, not operator visual approval.

The comp is on the left and the built page is on the right. Comp data is drawn; built data is live and changes during the sequence. At the smaller widths, Sessions, the session, Turns and the turn use bounded 2400-high viewports. The other pages use full-page images from a 900-high viewport. Every wide image is a bounded viewport capture.

## Needs you

| Comp | Built |
|---|---|
| ![](png/needs-day-1440.png) | ![](png/built/needs-day-1440.png) |
| ![](png/needs-day-1920.png) | ![](png/built/needs-day-1920.png) |

The built page shows actual quota and Doctor findings rather than the comp's sample items. Its actions are the actions available for those findings.

## Sessions

| Comp | Built |
|---|---|
| ![](png/sessions-day-1440.png) | ![](png/built/sessions-day-1440.png) |
| ![](png/sessions-day-1920.png) | ![](png/built/sessions-day-1920.png) |

The built page contains the daemon's current working and idle sessions. Whole cards are draggable; there are no grip buttons. The grouping control includes Team, and the sidebar includes the theme control.

## A session

| Comp | Built |
|---|---|
| ![](png/session-day-1440.png) | ![](png/built/session-day-1440.png) |
| ![](png/session-day-1920.png) | ![](png/built/session-day-1920.png) |

This is the console-builder session. The page opens at its newest retained message, so these captures show the ending viewport and composer, not the beginning or the entire transcript. The context rail changes arrangement with the available width.

## Fleet

| Comp | Built |
|---|---|
| ![](png/fleet-day-1440.png) | ![](png/built/fleet-day-1440.png) |
| ![](png/fleet-day-1920.png) | ![](png/built/fleet-day-1920.png) |

The built cards retain the daemon's actual quota, usage-window and runtime states. Whole cards are draggable without grips. Commands without reported usage windows remain short cards.

## A command's page

There is no comp for this page. These are the installed `claudex` command's window and account views.

| Built |
|---|
| ![](png/built/plan-day-1440.png) |
| ![](png/built/plan-day-1920.png) |

## Turns

| Comp | Built |
|---|---|
| ![](png/turns-day-1440.png) | ![](png/built/turns-day-1440.png) |
| ![](png/turns-day-1920.png) | ![](png/built/turns-day-1920.png) |

The selected window is 1 hour. Running turns appear above the command measurements and Finished list. The images show a bounded portion of that list.

## A turn

| Comp | Built |
|---|---|
| ![](png/turn-day-1440.png) | ![](png/built/turn-day-1440.png) |
| ![](png/turn-day-1920.png) | ![](png/built/turn-day-1920.png) |

This is a real retained turn from the console-builder session. Its Conversation read completed before every capture and contains retained messages, not a blank pending panel. The other tabs are Request and answer and Sent to the model.

### A step splice answered itself, historical example

This retained image was not refreshed from the installed build above. It is an archived local-step example, not current artifact evidence.

| Historical built example |
|---|
| ![](png/built/turn-local-day-1440.png) |

## Usage

| Comp | Built |
|---|---|
| ![](png/usage-day-1440.png) | ![](png/built/usage-day-1440.png) |
| ![](png/usage-day-1920.png) | ![](png/built/usage-day-1920.png) |

The selected window is 24 hours. The economics and command totals are live. These images do not establish numeric parity with the Project page or the hour-long Turns window.

## Settings

| Comp | Built |
|---|---|
| ![](png/settings-day-1440.png) | ![](png/built/settings-day-1440.png) |
| ![](png/settings-day-1920.png) | ![](png/built/settings-day-1920.png) |

The smaller built images are full-page captures of the real configuration and extend well beyond a single viewport. The wide image below shows the first viewport instead.

## A project

| Comp | Built |
|---|---|
| ![](png/project-day-1440.png) | ![](png/built/project-day-1440.png) |
| ![](png/project-day-1920.png) | ![](png/built/project-day-1920.png) |

The built project is `splice`, not the comp's `tally` or the earlier review's `eli`. It shows that repository's sessions, prompt and rule controls, and files read by the daemon. Its figures are not presented as a matched-window comparison with Usage.

## A team, historical fixture renders

The installed daemon returned no teams when the capture source was enumerated. These retained fixture renders were not refreshed and do not demonstrate the new seat-spacing fix. The current team behavior is covered by the CI browser journeys, not these images.

| Comp | Historical fixture render |
|---|---|
| ![](png/team-day-1440.png) | ![](png/built/team-day-1440.png) |
| ![](png/team-day-1920.png) | ![](png/built/team-day-1920.png) |

## The operator's frame: 3840 × 2060, day

Every image below comes from the same installed jar and uses exactly this viewport. The pages retain live state rather than the comp's drawn data. The session view is at its newest message, and long pages continue beyond the captured frame.

| Needs you | Sessions |
|---|---|
| ![](png/built/needs-day-3840.png) | ![](png/built/sessions-day-3840.png) |
| **A session** | **Fleet** |
| ![](png/built/session-day-3840.png) | ![](png/built/fleet-day-3840.png) |
| **A command** | **Turns** |
| ![](png/built/plan-day-3840.png) | ![](png/built/turns-day-3840.png) |
| **A turn** | **Usage** |
| ![](png/built/turn-day-3840.png) | ![](png/built/usage-day-3840.png) |
| **Settings** | **A project** |
| ![](png/built/settings-day-3840.png) | ![](png/built/project-day-3840.png) |
