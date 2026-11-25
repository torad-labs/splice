## Module: diagram

- Draw boxes and arrows as inline SVG: `<rect rx>` with `<text>` for each node, `<path>` or `<line>` for each edge, and
  one `<marker>` for the arrowheads. Stroke with `currentColor` so the diagram follows the theme.
- Lay the nodes out on a grid you work out first: columns by layer or by step, rows within them. Left to right for a
  flow, top to bottom for a hierarchy. Twelve nodes at most; split a bigger one into two diagrams.
- Group related nodes on a faint background rectangle with the group's name in a corner.
- A sequence: one vertical line per participant, and one horizontal arrow per message, top to bottom in time order,
  each labelled above its arrow.
- States: rounded boxes for the states and arrows labelled with the event that moves between them; mark the start.
- SVG text does not wrap. Keep labels to a few words, or put a wrapping `<div>` inside a `<foreignObject>`.
