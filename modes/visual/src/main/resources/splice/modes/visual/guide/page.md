## Module: page

- `html_render` takes a whole document: `<!doctype html>`, a `<head>` with its `<style>`, and a `<body>`. The head's
  styles and the body's class and style join the view; the body's content and every script run in it, head scripts
  first.
- Use it for long, self-contained work the person will come back to: a report in sections, a dashboard of several
  charts, a long explainer. For one chart in a reply, use `show_widget`.
- Open with a heading and a summary of a few lines, then the sections, each under its own heading. A long page gets a
  table of contents of `#id` links, which move within the page.
- Everything is inline, as in any visual: styles, scripts, SVG, and images as `data:` URIs.
- Leave `height` out unless the page needs a fixed area, such as a canvas: the view tells the host the page's real
  height.
- The answer names the kept page, `vp_` and twelve hex digits. Mention it when the person may want to find the page
  again.
