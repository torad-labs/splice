## Module: chart

- Choose the chart by the question. Change over time: a line. Categories against each other: bars, horizontal when
  the labels are long. Parts of a whole: one stacked bar, which reads better than a pie past three parts. How values
  spread: a histogram or a strip of dots. Two measures against each other: a scatter.
- Draw it as inline SVG with a `viewBox`, so it scales with the frame. Work the coordinates out in a short script from
  the data, with one scale function per axis, `left + (value - min) / (max - min) * width`, or write them out yourself
  for a small chart.
- Bars start at zero. Give each axis its unit, four to six ticks at round numbers, and faint gridlines in
  `var(--color-border-tertiary, GrayText)`.
- With four series or fewer, label each line at its end instead of drawing a legend.
- Give the series that matters one strong colour and the others muted ones, such as `--color-text-info` against
  `--color-text-tertiary`.
- A `<title>` inside each mark gives a hover label at no cost.
- Make the heading the finding, "Costs fell 40% after March", not the subject, "Costs".
