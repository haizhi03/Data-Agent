import type { SqlSignatureItem } from '../../../types/sql';

/**
 * Locates each parameter label inside its signature label. Sequential search
 * keeps repeated names (CONCAT's two `text:VARCHAR/CLOB`) on distinct spans.
 * Returns null when any parameter cannot be found, so callers can fall back
 * to plain string labels.
 */
export function parameterSpans(signature: SqlSignatureItem): [number, number][] | null {
  const spans: [number, number][] = [];
  let from = signature.label.indexOf('(');
  if (from < 0) from = 0;
  for (const parameter of signature.parameters) {
    const start = signature.label.indexOf(parameter.label, from);
    if (start < 0) return null;
    spans.push([start, start + parameter.label.length]);
    from = start + parameter.label.length;
  }
  return spans;
}
