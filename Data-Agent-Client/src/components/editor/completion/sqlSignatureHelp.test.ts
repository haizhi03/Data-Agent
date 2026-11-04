import { describe, expect, it } from 'vitest';
import { parameterSpans } from './sqlSignatureHelp';

describe('signature parameter spans', () => {
  it('locates parameters inside the signature label', () => {
    const signature = { label: 'NVL(value:ANY, replacement:ANY) → ANY',
      parameters: [{ label: 'value:ANY' }, { label: 'replacement:ANY' }] };
    const spans = parameterSpans(signature)!;
    expect(spans).toHaveLength(2);
    for (const [i, [start, end]] of spans.entries())
      expect(signature.label.slice(start, end)).toBe(signature.parameters[i].label);
  });
  it('keeps repeated parameter names on distinct spans', () => {
    const signature = { label: 'CONCAT(text:VARCHAR/CLOB, text2:VARCHAR/CLOB) → VARCHAR/CLOB',
      parameters: [{ label: 'text:VARCHAR/CLOB' }, { label: 'text2:VARCHAR/CLOB' }] };
    const spans = parameterSpans(signature)!;
    expect(spans[0][0]).toBeLessThan(spans[1][0]);
    expect(signature.label.slice(...spans[0])).toBe('text:VARCHAR/CLOB');
    expect(signature.label.slice(...spans[1])).toBe('text2:VARCHAR/CLOB');
  });
  it('returns null when a parameter is missing from the label', () => {
    expect(parameterSpans({ label: 'F(a NUMBER)', parameters: [{ label: 'b NUMBER' }] })).toBeNull();
  });
  it('handles zero-parameter signatures', () => {
    expect(parameterSpans({ label: 'PI() → DOUBLE', parameters: [] })).toEqual([]);
  });
});
