import { describe, expect, it } from 'vitest';
import { pdfPageSize, svgViewBox } from './erDiagramPdf';

describe('ER diagram PDF page', () => {
  it('reads the svg view box', () => {
    expect(svgViewBox('<svg viewBox="-64 -118 372 224"></svg>')).toEqual({
      x: -64,
      y: -118,
      width: 372,
      height: 224,
    });
  });

  it('keeps a normal diagram on one page at its own size', () => {
    expect(pdfPageSize(1200, 800)).toEqual({ width: 1200, height: 800, scale: 1 });
  });

  it('scales an oversized diagram down to the PDF page limit', () => {
    expect(pdfPageSize(28800, 14400)).toEqual({ width: 14400, height: 7200, scale: 0.5 });
  });
});
