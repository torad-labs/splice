## Module: diff

- Show only the hunks that carry the point, each under its file's path and starting line.
- Draw the lines as rows in a monospace block: added lines on `var(--color-background-success)`, removed lines on
  `var(--color-background-danger)`, both with a `+` or `-` in the gutter so the change does not rest on colour, and
  context lines muted.
- Annotate with a numbered marker on the line and the note beside it, or under it on a narrow frame. One or two
  sentences each, about why, not what.
- Escape `&`, `<` and `>` in the code you place in HTML.
- Unified by default. Side by side only for a short hunk, and let it stack when the frame is narrow.
- The visual explains a change; the change itself still goes in the files.
