/**
 * Escapes angle brackets outside fenced code blocks so MDX does not mistake
 * generics like `List<String>` or prose `<70B>` for JSX. HTML character
 * references are decoded by CommonMark inside code spans, so `&lt;` still
 * renders as `<`. Verified: the documentation corpus contains no real HTML
 * tags, so escaping angle brackets outside fences is safe. A `>` that starts
 * a line (blockquote syntax) is left untouched.
 */
export function escapeAngleBrackets(source: string): string {
  const lines = source.split("\n");
  let fence: string | null = null;

  return lines
    .map((line) => {
      const fenceMatch = line.match(/^\s*(```|~~~)/);
      if (fenceMatch) {
        if (!fence) {
          fence = fenceMatch[1];
        } else if (line.trim().startsWith(fence)) {
          fence = null;
        }
        return line;
      }
      if (fence) return line;

      const escaped = line
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;");
      return escaped.replace(/^(\s*)&gt;/, "$1>");
    })
    .join("\n");
}