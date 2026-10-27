import fontUrl from '../../assets/fonts/NotoSansSC-Regular.ttf?url';
import { buildErDiagramSvg, ER_SVG_FONT_FAMILY, type VectorDiagram } from './erDiagramVectorSvg';

/** PDF page size limit in points. Larger diagrams are scaled to fit one page. */
export const PDF_MAX_SIDE = 14400;
const PDF_FONT = 'NotoSansSC';

export function svgViewBox(markup: string) {
  const match = markup.match(/viewBox="([^"]+)"/);
  if (!match) throw new Error('ER diagram capture is blank');
  const [x, y, width, height] = match[1].trim().split(/[\s,]+/).map(Number);
  if (![x, y, width, height].every(Number.isFinite) || width <= 0 || height <= 0) {
    throw new Error('ER diagram capture is blank');
  }
  return { x, y, width, height };
}

/** One page the size of the diagram, scaled down only when it exceeds the PDF limit. */
export function pdfPageSize(viewWidth: number, viewHeight: number) {
  const scale = Math.min(1, PDF_MAX_SIDE / viewWidth, PDF_MAX_SIDE / viewHeight);
  return {
    width: Math.max(1, Math.round(viewWidth * scale * 100) / 100),
    height: Math.max(1, Math.round(viewHeight * scale * 100) / 100),
    scale,
  };
}

function bytesToBase64(bytes: Uint8Array) {
  let binary = '';
  const chunk = 0x8000;
  for (let index = 0; index < bytes.length; index += chunk) {
    binary += String.fromCharCode(...bytes.subarray(index, Math.min(index + chunk, bytes.length)));
  }
  return btoa(binary);
}

let fontBase64: Promise<string> | null = null;

function loadFont() {
  if (!fontBase64) {
    fontBase64 = fetch(fontUrl).then(async (response) => {
      if (!response.ok) throw new Error('Unable to load ER diagram font');
      return bytesToBase64(new Uint8Array(await response.arrayBuffer()));
    }).catch((error: unknown) => {
      fontBase64 = null;
      throw error;
    });
  }
  return fontBase64;
}

/** Vector PDF of the whole diagram. Tables are not split across pages. */
export async function renderErDiagramPdf(diagram: VectorDiagram): Promise<Blob> {
  const markup = buildErDiagramSvg(diagram).split(`font-family="${ER_SVG_FONT_FAMILY}"`).join(`font-family="${PDF_FONT}"`);
  const view = svgViewBox(markup);
  const page = pdfPageSize(view.width, view.height);
  const [{ jsPDF }, { svg2pdf }] = await Promise.all([
    import('jspdf'),
    import('svg2pdf.js'),
  ]);
  const doc = new jsPDF({
    unit: 'pt',
    format: [page.width, page.height],
    orientation: page.width >= page.height ? 'landscape' : 'portrait',
    compress: true,
  });
  const file = 'NotoSansSC-Regular.ttf';
  const encoded = await loadFont();
  doc.addFileToVFS(file, encoded);
  doc.addFont(file, PDF_FONT, 'normal');
  doc.addFont(file, PDF_FONT, 'bold');
  doc.setFont(PDF_FONT);
  const svg = new DOMParser().parseFromString(markup, 'image/svg+xml').documentElement;
  if (svg.tagName.toLowerCase() === 'parsererror') throw new Error('ER diagram capture is blank');
  await svg2pdf(svg, doc, { x: 0, y: 0, width: page.width, height: page.height });
  return doc.output('blob');
}
