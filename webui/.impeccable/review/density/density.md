# ARE TWELVE PAGES THIN? (M1-109)

The row inherited a sentence: *twelve of thirteen pages carry a quarter to a half the ink of the one
page with a comp.* **That sentence is false, and showing why is the first half of the diagnosis.**
The pages are not short of ink. Most of them cover more of their frame than the comp does. What they
are short of is something else entirely, and it has a name in this world already.

Measured at 1536, dark, with fixtures. Instruments: `density.mjs` (pixels), `capacity.mjs` (DOM).

---

## 1. The ratio everyone was reading is not an ink measure

`tonal()` counts pixels whose mean luminance lands in **[30,150]**. `coverage.mjs`'s `layers()` uses
that same band and splits it into **RULED** (mid-tone runs ≤3px — the rack's rules) and **PRINTED**
(runs >3px — plates and printed members). The cream a strip is printed on sits **above** that band.
Text ink sits **below** it. So the tonal ratio measures neither the paper nor the ink: it measures
the **mid-tone material** — the rack's ruled structure and its printed members.

What that does to the ranking:

| page | paper % | tonal rank said | actually |
|---|---:|---|---|
| settings | **46.0** | last, 29% of comp | carries **more paper than the comp** (27.9%) |
| logs | **60.4** | 67% | the **fullest page in the set**, 31.8% bare ground |
| teams (comp's page) | 28.9 | 104% | **mid-table** on bare ground; four pages are fuller |

A page is not thin because that ratio is low. Two of the three "thinnest" pages are the two most
paper-covered surfaces in the console.

## 2. What the comp actually has that the others do not

| page | paper | **printed** | ruled | floor |
|---|---:|---:|---:|---:|
| **COMP (team-board-a)** | 27.9 | **6.2** | 5.9 | 60.1 |
| teams (build) | 28.9 | **5.7** | 6.4 | 59.0 |
| usage | 20.0 | 3.6 | 3.6 | 72.8 |
| logs | 60.4 | 2.6 | 5.3 | 31.8 |
| fleet | 36.0 | 1.7 | 4.4 | 57.8 |
| mcp | 44.5 | 1.7 | 3.6 | 50.2 |
| accounts | 26.7 | 1.6 | 5.0 | 66.8 |
| turns | 33.4 | 1.6 | 5.8 | 59.2 |
| doctor | 30.5 | 1.5 | 5.2 | 62.9 |
| models | 23.4 | 1.4 | 4.1 | 71.1 |
| sessions | 28.7 | 1.2 | 5.8 | 64.3 |
| compaction | 29.3 | 1.1 | 3.5 | 66.1 |
| projects | 22.3 | 1.0 | 5.2 | 71.5 |
| settings | 46.0 | **0.9** | 2.6 | 50.5 |

**RULED is comparable everywhere (2.6–6.4). PAPER varies wildly (20–60) and does not track the
feeling of density at all. PRINTED is the discriminator**: the comp has 6.2% and the build's own
teams page 5.7%, while every other address sits between 0.9% and 3.6%.

Printed material in this world is the mid-tone block: the grey header strip a table wears, the
plate a bay label sits on. The comp composes **many small printed members** — three columns of
three-to-five short tables, each with its own header strip. The other pages compose **few large
ones**. That is the density difference, and it is a compositional fact, not a colour or a type size.

## 3. Where the space actually goes

Bare-ground share cannot locate waste, because a chart's black interior and a dead quadrant are the
same number. Two geometric measures do:

- **bare %** — largest rectangle where every sample is unlit room.
- **dead %** — largest rectangle carrying **no paper and only a thin scatter of mid-tone**: space
  crossed by the rack's own rails but printed with nothing.

The second is the one that matters, and it is the one that finds the defect. On `models` the largest
*bare* rectangle is 4.4% and sits in the header band, while the dead column beside its rack is
**21.5%** — the rails cross the full width, so every cell there holds lit pixels and reads as "not
empty" while being visibly dead. M1-102 found this same shape on fleet by eye and called it a
quarter of the width.

| page | bare % | **dead %** | the region | bay slots used |
|---|---:|---:|---|---|
| projects | 3.4 | **43.7** | 1432×480 across the bottom | 4/8 |
| models | 4.4 | **21.5** | 432×784 right column | 13/13 |
| accounts | 3.3 | **20.3** | 408×784 right column | 6/11 |
| turns | 2.8 | **17.7** | 536×520 right column | 13/15 |
| compaction | 6.9 | **17.2** | 368×736 right column | 13/13 |
| settings | 8.5 | **14.1** | 864×256 header band | — |
| fleet | 3.7 | **13.5** | 1104×192 bottom band | 7/7 |
| usage | 6.1 | 11.3 | 1008×176 top band | 3/3 |
| **teams (the comp's page)** | 2.9 | **11.2** | 960×184 bottom band | 14/29 |
| doctor | 3.8 | 10.6 | 424×392 right column | 10/14 |
| sessions | 2.4 | 10.2 | 1536×104 bottom band | 5/9 |
| mcp | 4.0 | 7.8 | 392×312 right column | 10/10 |
| logs | 2.6 | 6.8 | 664×160 top band | 15/12 |

### The bar comes from the comp, not from me

**The comp's own page wastes 11.2%.** Empty slot rails are part of this world's vocabulary — the
comp shows them, at that proportion. So the honest threshold is the comp's own figure, and the
pages that exceed it are the ordered list this row was asked for:

1. **projects — 43.7%** (3.9× the comp)
2. **models — 21.5%**
3. **accounts — 20.3%**
4. **turns — 17.7%**
5. **compaction — 17.2%**
6. **settings — 14.1%**
7. **fleet — 13.5%**

Below the comp's own figure: usage 11.3 (at it), doctor 10.6, sessions 10.2, mcp 7.8, logs 6.8.

## 4. One question per page

The row asks one thing of each page: **layout holding space it does not use, or genuinely less to
say?** Three shapes, with different answers.

**(a) The dead right column — models, accounts, turns, compaction, doctor, mcp.** This is the detail
panel, empty until a row is selected. **LAYOUT.** It is not that these pages have less to say; it is
that a fifth of the frame is reserved for a response to a click nobody has made. The comp settles
what belongs there: in `team-board-a` the right third is a *populated bay* — team chat and activity
— not a placeholder. A column that carries a bay until a row is selected is buildable from what the
comp already establishes.

**(b) Empty slot rails below content — projects (43.7%), sessions, accounts, teams itself.**
**LAYOUT, but only above the comp's own proportion.** The rails are the world's idiom and teams
shows them at 11.2%. projects at 43.7% is the same idiom four times over: four repo strips, then
roughly fifteen empty rails to the bottom of the frame. The bay is sized to the viewport rather than
to its contents, and only projects is far enough past the comp to call wrong.

**(c) The header band — settings, usage, logs.** A title, some view tabs, and 800–1400px of nothing
to their right. **LAYOUT**, and the cheapest of the three to settle, but also the smallest.

**Pages I believe are correctly sparse, and why:**

- **logs** — 60.4% paper, 31.8% bare ground, 15 rows. A log tail is a column of lines; it is the
  densest surface in the console and its 6.8% dead region is a top band. Nothing to answer here.
- **usage** — 72.8% floor reads alarming and is an artifact: its charts are dark by design, and the
  floor measure counts a chart interior as bare. Its dead region is 11.3%, at the comp's own figure,
  and it has the second-highest printed share (3.6%) of any non-teams page. **Correctly sparse.**
- **mcp** and **doctor** — below the comp's waste figure, both list-shaped. Sparse because a server
  list and a check list genuinely say less than a team board.
- **fleet** — borderline at 13.5%, and M1-102 already owns its specific column.

**The one page where the answer is unambiguous is `projects`**: 43.7% of the frame is rack drawn
with nothing in it, 4 of 8 slots filled, the lowest printed share but one, and a bottom half that is
rails and nothing else.

## 5. What I measured wrongly first, and threw away

Recorded because a diagnosis is only as good as its instrument, and three of these were caught by
looking at the page rather than at the number.

1. **The paper classifier inverted on two pages.** My first cut used the *modal* luminance as the
   ground. settings and teams are more than half cream by pixel count, so the mode **is** the paper,
   the cut landed above it, and two visibly cream-covered pages reported 0.1% and 1.0% paper.
   `coverage.mjs` uses the **median** and is right; only my replication was wrong.
2. **The rack-capacity probe measured the wrong box.** Grouping strips by `parentElement` reported
   every rack full — 7/7, 4/4, 10/10 — which was true and useless. The slot rails are painted by the
   enclosing `.myx-bay`, which is taller. Measuring the wrong container turned this row's central
   question into a null result.
3. **The header-band measure was removed, not fixed.** It reported 1513 of 1528px used on all
   thirteen addresses — the same number everywhere, which is the signature of a measurement that is
   not measuring. The pixel pass answers that question properly; two instruments guessing at one
   number is how 15/8/25 happened.

## 6. Out of scope, in one sentence each

No type sizes, no spacing, no colours were chosen here, and nothing above needs a new visual
element. The two observations that would need a decision rather than a measurement: the comp gets
its density from **many small printed members** where the pages use few large ones, and the **detail
column** wants a resting state the comp's right third already demonstrates. Both are for the
orchestrator, not for this row.
