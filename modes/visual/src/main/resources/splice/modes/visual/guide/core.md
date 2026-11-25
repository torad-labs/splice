# Drawing visuals

You have two drawing tools. `show_widget` draws an HTML or SVG fragment inline in the conversation. `html_render`
draws a whole HTML document and keeps it, so it is still there when the conversation is opened again. Both are drawn in
one sandboxed view, in clients that draw MCP Apps.

## When a visual beats text

- Numbers with a shape: a trend, a distribution, shares of a whole, many values side by side. Draw a chart.
- Several options weighed on several attributes. Draw a comparison.
- Structure or flow: components and how they connect, a sequence of calls, states and their transitions. Draw a
  diagram.
- A code change where it matters which line changed and why. Draw an annotated diff.
- An idea with moving parts that a reader understands best by stepping through it or by changing an input. Draw an
  explainer.
- Something the person will use and not only read: a calculator, a converter, a list to filter. Draw a small tool.
- A long, self-contained piece of work to come back to: a report, a dashboard. Render a page.

## When it does not

- The answer is a sentence, a number, a yes or no, a command, or a snippet the person will copy.
- A short list, or a small table that Markdown already shows well.
- The person asked for text, for a file, or for code to paste somewhere.
- The picture would only decorate. A visual earns its place by showing what the words cannot show as well.

Draw one visual per point, and never draw the same thing twice in one reply.

## What every visual does

- **The reply stands without it.** Some clients show only the tool's answer and never the picture. Say in words what
  the visual shows: the finding, the recommendation, the numbers that matter.
- **It wears the host's theme.** The host gives its colours and fonts as CSS variables, and the view follows its light
  or dark. Use the variables, each with a fallback, and never hard-code a light or a dark palette:
  - text: `var(--color-text-primary, CanvasText)`, `var(--color-text-secondary, GrayText)`, `var(--color-text-tertiary, GrayText)`
  - surfaces: leave the background transparent, or `var(--color-background-secondary, Canvas)` for a panel
  - borders: `var(--color-border-primary, GrayText)`, `var(--color-border-tertiary, GrayText)`
  - meaning: `--color-text-info`, `--color-text-success`, `--color-text-warning`, `--color-text-danger`, and the
    `--color-background-*` of the same names
  - type: `var(--font-sans, system-ui, sans-serif)`, `var(--font-mono, ui-monospace, monospace)`
  - shape: `--border-radius-sm`, `--border-radius-md`, `--border-radius-lg`
  For a colour the host does not name, such as a second chart series, write `light-dark(<on light>, <on dark>)` so it
  reads in both. In SVG, `currentColor` follows the text colour.
- **It reaches nothing.** The view's policy allows no origin: no `<script src>`, no stylesheet link, no web font, no
  remote image, no `fetch`. Draw with inline SVG, canvas, CSS and plain JavaScript, put the data in the code, and give
  an image as a `data:` URI.
- **It fits the frame.** The width is the conversation's and changes: use `width: 100%`, `max-width`, and a `viewBox`
  on SVG, never a fixed width. The height follows the content, so never use `100vh`. Keep it compact.
- **It streams well.** While the call streams, the fragment is shown without running its scripts; once it is complete,
  its scripts run in order. Write styles first, then the markup, then the scripts, and make the first state visible
  without any script.
- **It talks back only through the host.** `sendPrompt(text)` offers the person a message to send you, which they may
  send or not; `openLink(url)` and any `<a href>` open outside the frame. Forms do not submit, dialogs such as `alert`
  do not show, and storage may not be there: keep state in memory.
- **It can be read by everyone.** Label axes and units, give SVG a `<title>` or an `aria-label`, keep text contrast
  high, and never carry meaning in colour alone.
- **It is small.** `widget_code` and `html` hold at most 256 KiB each.

## Which tool

- `show_widget` for a visual in the flow of the reply. Its `title` is a short name, `like_this`. Put `title` and
  `loading_messages` before `widget_code`, so the host can show them while the code streams. The fragment has no
  `<html>`, `<head>` or `<body>`.
- `html_render` for a whole document, with `<!doctype html>`, `<head>` and `<body>`, which the mode keeps. Its `title`
  is for a person to read.
