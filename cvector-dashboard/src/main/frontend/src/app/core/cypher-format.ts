/**
 * Best-effort Cypher pretty-printer for the Query view's "Format query" action.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Each top-level clause (MATCH, WHERE, RETURN, ORDER BY, …) goes on its own line.</li>
 *   <li>Inside a WHERE/WITH chain, AND / OR / XOR break to an indented continuation line so
 *       multi-predicate filters stack vertically instead of running off the right edge.</li>
 *   <li>Keywords are uppercased; identifiers, string literals, and back-tick names are
 *       preserved exactly.</li>
 *   <li>Comments (<code>//</code> line, <code>/* … *&#47;</code> block) and quoted strings
 *       are masked before any transform runs, so the formatter never reflows or upper-cases
 *       text inside them.</li>
 * </ul>
 *
 * <p>Not a full Cypher parser — pattern bodies (e.g. <code>(a)-[:R]->(b)</code>) stay on the
 * same line as their clause; users who want fine-grained pattern wrapping can hand-edit in
 * the Full screen editor. The goal is to turn a one-liner into the standard clause-per-line
 * shape, not to be a complete style enforcer.
 */
export function formatCypher(src: string): string {
  if (!src.trim()) return src;

  // Mask string/comment literals so the regex passes below can't see them. Replaced with
  // a sentinel pair (\u0001N\u0002) that survives whitespace collapse and is restored at
  // the end. Sentinels chosen from the C0 control range so they can't legally appear in
  // user-typed Cypher.
  const placeholders: string[] = [];
  const masked = src.replace(
    /'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*"|`(?:[^`\\]|\\.)*`|\/\/[^\n]*|\/\*[\s\S]*?\*\//g,
    (m) => {
      const idx = placeholders.length;
      placeholders.push(m);
      return `\u0001${idx}\u0002`;
    },
  );

  // Collapse all whitespace to single spaces. We reintroduce newlines deliberately below;
  // any layout the user typed is intentionally discarded so the formatter is idempotent.
  let out = masked.replace(/\s+/g, ' ').trim();

  // Top-level clauses get their own line. Multi-word clauses are listed first so the
  // alternation matches them before their single-word prefix (e.g. OPTIONAL MATCH beats
  // MATCH at the start of "OPTIONAL MATCH (n)").
  const topClauses = [
    'OPTIONAL\\s+MATCH', 'DETACH\\s+DELETE', 'UNION\\s+ALL',
    'MATCH', 'WHERE', 'WITH', 'RETURN', 'ORDER\\s+BY', 'LIMIT', 'SKIP',
    'CREATE', 'MERGE', 'DELETE', 'SET', 'REMOVE', 'UNWIND',
    'CALL', 'YIELD', 'FOREACH', 'UNION',
  ];
  const topRe = new RegExp(`\\b(?:${topClauses.join('|')})\\b`, 'gi');
  out = out.replace(topRe, (m) => '\n' + collapseAndUpper(m));

  // AND/OR/XOR inside a clause: indent two spaces so the connector visually nests under
  // the parent WHERE/WITH. Matches a fairly common Cypher house-style.
  out = out.replace(/\b(AND|OR|XOR)\b/gi, (m) => '\n  ' + m.toUpperCase());

  // Inline keywords — uppercase only, no line break. Multi-word phrases ("STARTS WITH",
  // "ENDS WITH") are normalised to single-space form while being upper-cased.
  const inline = [
    'AS', 'NOT', 'IN', 'IS', 'NULL', 'TRUE', 'FALSE', 'DISTINCT',
    'CASE', 'WHEN', 'THEN', 'ELSE', 'END',
    'CONTAINS', 'STARTS\\s+WITH', 'ENDS\\s+WITH', 'EXISTS',
    'ASC', 'DESC', 'ASCENDING', 'DESCENDING',
  ];
  const inlineRe = new RegExp(`\\b(?:${inline.join('|')})\\b`, 'gi');
  out = out.replace(inlineRe, (m) => collapseAndUpper(m));

  // Strip the leading newline introduced when the very first token was a top-level clause.
  out = out.replace(/^\n+/, '');

  // Restore masked literals/comments verbatim.
  out = out.replace(/\u0001(\d+)\u0002/g, (_, idx) => placeholders[Number(idx)]);

  return out;
}

function collapseAndUpper(m: string): string {
  return m.replace(/\s+/g, ' ').toUpperCase();
}
