// An honest empty. The world's rule is that every empty names its source, so
// source is a required prop and not a default: a blank bay can always answer
// "which thing did I look at and find nothing in".
export function Empty({ text, source }: { text: string; source: string }) {
  return (
    <p className="myx-empt" role="status">
      <span className="myx-empt-text">{text}</span>
      <span className="myx-empt-source">{source}</span>
    </p>
  );
}
