// MODELS, THE CATALOG RACK (row M2-33). What is proved here is the rack's SHAPE, because that is
// what the page's own comments already claimed and the code did not do.
//
// components.tsx has said since m1 that "the bay head prints these once and the strips below carry
// values only (B9)", and it exported MODEL_COLUMNS for exactly that. No bay was ever given a names
// row, every strip was given `label={column.label}`, and the struck row for a vacant tier rendered
// the first TWO columns of six. So the rack printed its six column names thirteen times and then
// stopped being a grid at the third column. Measured at 1536 dark before the change: a strip stood
// 63.8px of which 42px was the values; a vacant row ended at x=553 where its neighbours ran to
// x=1137; every strip left 345.8px of bare bay; and with a names row added but the strips still at
// their own width, the last name sat 301.9px right of the column it names.
//
// CONTRACTS.md section 4: a .ts test holds no JSX (TS1161), so elements are built with
// createElement and asserted on the markup react-dom/server returns; and a BOARD takes its payload
// as a prop, because a static render only ever sees a zustand store's initial state.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';

import { MODEL_COLUMNS, vacantText } from '../src/pages/models/components';
import { ModelsBoard } from '../src/pages/models';
import { fixtureCatalog } from '../src/pages/models/fixtures/models';
import { S } from '../src/pages/models/strings';

const h = createElement;

interface Rack { label: string; names: { w: number; text: string }[]; rows: number[][]; labels: number }

/** Every bay in a rendered board, as the three things B9 is about: the names row, the cell widths
 *  under it, and how many per-cell labels survive. Bounded at the bay's own `</section>`, because a
 *  region that runs to the end of the markup sweeps in whatever the page draws below the rack --
 *  the measurement-reads-past-its-object defect this campaign has now made three times. */
function racks(markup: string): Rack[] {
  // a bay's class list may carry a modifier (a compact rack is `myx-bay myx-bay-compact`)
  return markup.split(/<section class="myx-bay[ "]/).slice(1).map((part) => {
    const head = /<span class="myx-bay-label">([^<]*)</.exec(part);
    const fields = /<div class="myx-bay-fields">([\s\S]*?)<\/div><div class="myx-bay-rows">/.exec(part);
    const rowsAt = part.indexOf('<div class="myx-bay-rows">');
    const end = part.indexOf('</section>');
    const region = rowsAt < 0 ? '' : part.slice(rowsAt, end < 0 ? undefined : end);
    return {
      label: head === null ? '?' : head[1],
      names: fields === null ? [] : [...fields[1].matchAll(/width:(\d+)ch[^>]*>([^<]*)</g)]
        .map((m) => ({ w: Number(m[1]), text: m[2] })),
      // SPLIT ON THE CLASS, NOT ON THE CLASS PLUS ITS CLOSING QUOTE: a modified row is
      // `class="myx-strip myx-strip-selected"`, so the quoted form misses exactly the rows this
      // file exists to check and silently folds their cells into the previous row -- the first
      // run reported a twelve-cell strip in a six-column rack. The character class is the other
      // half: a bare prefix also matches `myx-strip-fields`, the strip's OWN inner box, and the
      // second run reported an empty row. Found both times by the assertion, which is the point of
      // asserting the list rather than its length.
      rows: region.split(/<div class="myx-strip[ "]/).slice(1)
        .map((strip) => [...strip.matchAll(/class="myx-sfield" style="width:(\d+)ch/g)].map((m) => Number(m[1]))),
      labels: (region.match(/myx-sfield-label/g) ?? []).length,
    };
  });
}

describe('the catalog rack is a grid, and its names are printed once', () => {
  const markup = renderToStaticMarkup(h(ModelsBoard, { catalog: fixtureCatalog }));
  const bays = racks(markup);
  const widths = MODEL_COLUMNS.map((column) => column.w);

  test('the board renders every head as a bay, so a green below is not an empty denominator', () => {
    // Law 34: if the board drew nothing, every assertion here would pass vacuously and the suite
    // would report a rack with no repeated labels and no ragged rows. The denominator is named.
    expect(bays.map((bay) => bay.label)).toEqual(fixtureCatalog.heads.map((head) => head.head));
    expect(bays.every((bay) => bay.rows.length > 0)).toBe(true);
    expect(bays.length).toBeGreaterThan(0);
  });

  test('every bay prints the six column names once, from the same table the cells use', () => {
    for (const bay of bays) {
      expect(`${bay.label}: ${bay.names.map((name) => name.text).join()}`)
        .toBe(`${bay.label}: ${MODEL_COLUMNS.map((column) => column.label).join()}`);
    }
  });

  test('and therefore prints no label on any cell', () => {
    for (const bay of bays) expect(`${bay.label}: ${bay.labels} labels`).toBe(`${bay.label}: 0 labels`);
  });

  test('EVERY row has six cells, the vacant tier included (M1-107)', () => {
    // A view decides what a cell SHOWS, never how many cells a row has. The struck row used to
    // carry two of six, which ended it 584px short of its neighbours in the same rack.
    for (const bay of bays) {
      for (const row of bay.rows) {
        expect(`${bay.label} ${row.join()}`).toBe(`${bay.label} ${widths.join()}`);
      }
    }
  });

  test('a name is declared at the ch of the column it names', () => {
    for (const bay of bays) expect(bay.names.map((name) => name.w)).toEqual(widths);
  });

  test('the tiers no model fills are named on one line, and no strip is dashes alone', () => {
    // FEATURES 4.8: the page says which tiers Claude Code will not get on a head. It said so with a
    // strip of dashes per tier; it says it once, by name, and every strip left carries a model.
    expect(markup).toContain('no model fills the fable tier here');
    const strips = markup.split(/<div class="myx-strip[ "]/).slice(1);
    for (const strip of strips) {
      const values = [...strip.slice(0, strip.indexOf('</div></div>') + 12)
        .matchAll(/<span class="myx-sfield-text">([^<]*)</g)].map((m) => m[1]);
      expect(values.filter((value) => value !== S.absent).length).toBeGreaterThan(1);
    }
    expect(vacantText([])).toBeNull();
    expect(vacantText(['opus', 'haiku'])).toContain('no model fills the opus, haiku tiers');
  });

  test('the wall can fail: a short row and a labelled cell are both reported by name', () => {
    // The planted violations are the two states this page was in an hour ago. A wall nobody has
    // seen go red on the defect it was written for is not yet a wall.
    const planted = '<section class="myx-bay"><header class="myx-bay-head"><span class="myx-bay-label">planted</span>'
      + '</header><div class="myx-bay-fields"><span style="width:24ch;flex-grow:24">model</span></div>'
      + '<div class="myx-bay-rows"><div class="myx-strip"><div class="myx-sfield" style="width:24ch">'
      + '<span class="myx-sfield-label">model</span></div></div>'
      + '<div class="myx-strip"><div class="myx-sfield" style="width:24ch"></div>'
      + '<div class="myx-sfield" style="width:8ch"></div></div></div></section>';
    const bay = racks(planted)[0];
    expect(bay?.label).toBe('planted');
    expect(bay?.labels).toBe(1);
    expect(bay?.rows).toEqual([[24], [24, 8]]);
  });
});
