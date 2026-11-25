## Module: comparison

- Open with the recommendation in one line, then the evidence.
- Options as columns and attributes as rows, five options at most at once. Keep each cell to a few words, and put
  caveats in a note under the table.
- Mark the better value in each row with weight and a word or a mark, not with colour alone.
- When each option needs a paragraph, use tabs: a row of buttons with `role="tab"` and `aria-selected`, and a panel
  for each, switched by a short script. Show the first panel without any script, so the view is not empty while it
  streams.
- Cards side by side work for two or three options; let them wrap on a narrow frame with `flex-wrap`.
