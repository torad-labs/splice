/** A path, a code or an address that the page does not print until it is asked for: a quiet toggle, then the text in mono. */
export function OnRequest({ label, children }: { label: string; children: string }) {
  return (
    <details className="on-request">
      <summary>{label}</summary>
      <code>{children}</code>
    </details>
  );
}
