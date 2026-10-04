// A message as its author wrote it: headings, lists, tables, links, inline code and highlighted code
// blocks. The one place markdown is rendered. Raw HTML in a message is never rendered.
import type { ReactNode } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { highlight } from './highlight';

const LANGUAGE = /language-([\w+-]+)/;

function Code({ className, children }: { className?: string | undefined; children?: ReactNode }) {
  const text = String(children ?? '');
  const language = LANGUAGE.exec(className ?? '')?.[1] ?? null;
  // A block is what carries a language class or spans lines; a span of inline code is neither.
  if (language === null && !text.includes('\n')) return <code>{children}</code>;
  const html = highlight(text.replace(/\n$/, ''), language);
  return html === null ? (
    <pre className="code"><code>{text.replace(/\n$/, '')}</code></pre>
  ) : (
    <pre className="code"><code className="hljs" dangerouslySetInnerHTML={{ __html: html }} /></pre>
  );
}

export function Markdown({ children }: { children: string }) {
  return (
    <div className="prose">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          code: Code,
          pre: ({ children: inner }) => <>{inner}</>,
          a: ({ href, children: label }) => (
            <a href={href} target="_blank" rel="noopener noreferrer">{label}</a>
          ),
          table: ({ children: rows }) => <div className="table-wrap"><table>{rows}</table></div>,
        }}
      >
        {children}
      </ReactMarkdown>
    </div>
  );
}
