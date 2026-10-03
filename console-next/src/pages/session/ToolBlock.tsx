import { Fragment, useState } from 'react';
import type { ReactNode } from 'react';
import { callTarget, outputSize, toolLabel, toolTarget } from '../../lib/conversation';
import type { Item } from '../../lib/conversation';
import { noun } from '../../lib/format';
import { inputLines, inputView, outputLines, outputView } from '../../lib/tool-view';
import type { DiffLine, Field, InputView, OutputView } from '../../lib/tool-view';
import { Check, Chevron } from '../../ui';
import { highlight } from '../../ui/highlight';
import { P } from './copy';

type Tool = Extract<Item, { kind: 'tool' }>;

/** The most of a result one block prints: a tool can return a whole file, and the page is not a file viewer. */
export const OUTPUT_CAP = 20_000;
/** A part longer than this opens at its start, with the rest one click away. */
export const FOLD_LINES = 12;
const FOLD_CHARS = 2_000;

/** Code in its language, highlighted; plain text when the language is not one the highlighter knows. */
function Code({ text, language }: { text: string; language: string | null }) {
  const html = highlight(text, language);
  return html === null || language === null ? <code className="plain">{text}</code> : <code className={`hljs language-${language}`} dangerouslySetInnerHTML={{ __html: html }} />;
}

/** One line of a diff, highlighted on its own; the marker is drawn by the stylesheet, so copied text is the code alone. */
function DiffRow({ line, language }: { line: DiffLine; language: string | null }) {
  const html = highlight(line.text, language);
  return html === null ? <span className={`ln ${line.op}`}>{line.text}</span> : <span className={`ln ${line.op}`} dangerouslySetInnerHTML={{ __html: html }} />;
}

/** Labelled values, a nested object or list as its own labelled values. */
function Fields({ fields }: { fields: readonly Field[] }) {
  return (
    <dl className="kv">
      {fields.map((field, i) => (
        <Fragment key={`${i}:${field.key}`}>
          <dt>{field.key}</dt>
          <dd>{field.value.kind === 'text' ? field.value.text : <Fields fields={field.value.fields} />}</dd>
        </Fragment>
      ))}
    </dl>
  );
}

/** A long part opens at its start; a short one is shown whole with no control. */
function Fold({ lines, chars, children }: { lines: number; chars: number; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  if (lines <= FOLD_LINES && chars <= FOLD_CHARS) return children;
  return (
    <div className={open ? 'fold' : 'fold shut'}>
      <div className="fold-body">{children}</div>
      <button type="button" className="more" onClick={() => setOpen((was) => !was)}>
        {open ? P.showFewer : P.showAll(lines)}
      </button>
    </div>
  );
}

function InputBody({ view }: { view: InputView }) {
  switch (view.kind) {
    case 'command':
      return <pre className="code cmd"><Code text={view.command} language="bash" /></pre>;
    case 'edit':
      return (
        <pre className="code diff">
          {view.lines.map((line, i) => (
            <DiffRow key={i} line={line} language={view.language} />
          ))}
        </pre>
      );
    case 'write':
      return <pre className="code"><Code text={view.content} language={view.language} /></pre>;
    case 'read':
      return null;
    case 'fields':
      return <Fields fields={view.fields} />;
    case 'text':
      return <pre className="out">{view.text}</pre>;
  }
}

function OutputBody({ view }: { view: OutputView }) {
  switch (view.kind) {
    case 'text':
      return <pre className="out">{view.text}</pre>;
    case 'numbered':
      return (
        <>
          <div className="numbered">
            <pre className="gutter">{view.numbers.join('\n')}</pre>
            <pre className="code"><Code text={view.code} language={view.language} /></pre>
          </div>
          {view.rest === '' ? null : <pre className="out">{view.rest}</pre>}
        </>
      );
    case 'fields':
      return <Fields fields={view.fields} />;
  }
}

const resultText = (item: Tool, input: InputView | null): string => {
  if (input?.kind === 'edit' && item.output !== null) {
    const count = (op: DiffLine['op']): number => input.lines.filter((line) => line.op === op).length;
    return `+${count('add')} ${P.removed}${count('del')}`;
  }
  const size = outputSize(item.output);
  if (size === null) return P.running;
  return size.lines === 0 ? P.noOutput : `${size.lines} ${noun(size.lines, 'line', 'lines')}`;
};

/** One tool call and what came back, as a dark-glass block that opens on demand: the call's input the way Claude Code shows it,
 *  then the result, each folded when long. */
export function ToolBlock({ item }: { item: Tool }) {
  // A call the daemon cut short has no parsed input; its text still names what it did.
  const target = toolTarget(item.tool, item.input) ?? (item.inputText === '' ? null : callTarget(item.tool, item.inputText));
  const read = inputView(item.tool, item.input, item.inputText);
  const view = read?.view ?? null;
  const running = item.output === null;
  const body = item.output === null ? null : item.output.length > OUTPUT_CAP ? item.output.slice(0, OUTPUT_CAP) : item.output;
  const result = body === null || body === '' ? null : outputView(item.tool, body, item.input);
  const extra = view !== null && 'extra' in view ? view.extra : [];
  return (
    <details className="tool">
      <summary>
        <Chevron className="chev" />
        <span className="verb">{toolLabel(item.tool)}</span>
        {target === null ? null : <span className="arg">{target}</span>}
        <span className={`res${running ? ' run' : ''}`}>
          {running ? <span className="sp" /> : <Check />}
          {resultText(item, view)}
        </span>
      </summary>
      {view !== null && 'path' in view ? <p className="where">{view.path}</p> : null}
      {view?.kind === 'command' && view.description !== null ? <p className="say">{view.description}</p> : null}
      {view?.kind === 'edit' && view.everywhere ? <p className="say">{P.everywhere}</p> : null}
      {result?.kind === 'numbered' ? <p className="range">{P.readLines(result.numbers[0] ?? 0, result.numbers.at(-1) ?? 0)}</p> : null}
      {view === null || view.kind === 'read' ? null : (
        <Fold lines={inputLines(view)} chars={item.inputText.length}>
          <InputBody view={view} />
        </Fold>
      )}
      {extra.length === 0 ? null : <Fields fields={extra} />}
      {read?.cut === true ? <p className="cut">{P.inputCut}</p> : null}
      {result === null || body === null ? null : (
        <Fold lines={outputLines(result)} chars={body.length}>
          <OutputBody view={result} />
        </Fold>
      )}
      {item.output !== null && item.output.length > OUTPUT_CAP ? <p className="cut">{P.outputCut}</p> : null}
    </details>
  );
}
