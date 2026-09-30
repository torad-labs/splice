import { callTarget, editDiff, outputSize, toolCommand, toolLabel, toolPath, toolTarget, writtenContent } from '../../lib/conversation';
import type { Item } from '../../lib/conversation';
import { noun } from '../../lib/format';
import { Check, Chevron } from '../../ui';
import { P } from './copy';

type Tool = Extract<Item, { kind: 'tool' }>;

/** The most of a result one block prints: a tool can return a whole file, and the page is not a file viewer. */
export const OUTPUT_CAP = 20_000;

const resultText = (item: Tool): string => {
  const diff = editDiff(item.tool, item.input);
  if (diff !== null && item.output !== null) return `+${diff.added.length} ${P.removed}${diff.removed.length}`;
  const size = outputSize(item.output);
  if (size === null) return P.running;
  return size.lines === 0 ? P.noOutput : `${size.lines} ${noun(size.lines, 'line', 'lines')}`;
};

/** One tool call and what came back, as a dark-glass block that opens on demand. */
export function ToolBlock({ item }: { item: Tool }) {
  // A call the daemon cut short has no parsed input; its text still names what it did.
  const target = toolTarget(item.tool, item.input) ?? (item.inputText === '' ? null : callTarget(item.tool, item.inputText));
  const where = toolPath(item.tool, item.input);
  const diff = editDiff(item.tool, item.input);
  const written = writtenContent(item.tool, item.input);
  const command = toolCommand(item.tool, item.input);
  const running = item.output === null;
  const body = item.output === null ? null : item.output.length > OUTPUT_CAP ? item.output.slice(0, OUTPUT_CAP) : item.output;
  return (
    <details className="tool">
      <summary>
        <Chevron className="chev" />
        <span className="verb">{toolLabel(item.tool)}</span>
        {target === null ? null : <span className="arg">{target}</span>}
        <span className={`res${running ? ' run' : ''}`}>
          {running ? <span className="sp" /> : <Check />}
          {resultText(item)}
        </span>
      </summary>
      {where === null ? null : <p className="where">{where}</p>}
      {diff === null ? (
        written === null ? null : <pre className="code">{written}</pre>
      ) : (
        <pre className="code">
          {diff.removed.map((line, i) => (
            <span key={`r${i}`} className="ln del">{`- ${line}`}</span>
          ))}
          {diff.added.map((line, i) => (
            <span key={`a${i}`} className="ln add">{`+ ${line}`}</span>
          ))}
        </pre>
      )}
      {command === null ? null : <pre className="code">{command}</pre>}
      {diff === null && written === null && command === null && item.inputText !== '' && body === null ? <pre>{item.inputText}</pre> : null}
      {body === null || body === '' ? null : <pre className="shell-out">{body}</pre>}
      {item.output !== null && item.output.length > OUTPUT_CAP ? <p className="cut">{P.outputCut}</p> : null}
    </details>
  );
}
