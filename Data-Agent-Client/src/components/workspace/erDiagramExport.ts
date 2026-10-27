import { getViewportForBounds, type Rect } from '@xyflow/react';
import { toCanvas } from 'html-to-image';
import type { ErEdgeData } from './erDiagramEdges';
import {
  buildErDiagramSvg,
  ER_EXPORT_PADDING,
  exportImageSize,
  includeBounds,
  vectorEdgesFromPaths,
  type VectorDiagram,
  type VectorTheme,
} from './erDiagramVectorSvg';

export { ER_EXPORT_PADDING, exportImageSize, includeBounds };

export type ErDiagramExportFormat = 'png' | 'svg' | 'pdf' | 'json';

function erDiagramBaseName(name: string) {
  return name.trim().replace(/[\\/:*?"<>|]+/g, '-').replace(/\s+/g, ' ').trim() || 'er-diagram';
}

export function erDiagramFilename(name: string, extension: ErDiagramExportFormat = 'png') {
  return `${erDiagramBaseName(name)}.${extension}`;
}

export function erDiagramCsvFilename(name: string, part: 'tables' | 'relations') {
  return `${erDiagramBaseName(name)}-${part}.csv`;
}

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Give the browser time to start the download before releasing its URL.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
export function hasImageContent(pixels: Uint8ClampedArray) {
  // The first pixel is in the padded background. A uniform bitmap is a failed capture.
  for (let index = 4; index < pixels.length; index += 4) {
    if (pixels[index] !== pixels[0] || pixels[index + 1] !== pixels[1]
      || pixels[index + 2] !== pixels[2] || pixels[index + 3] !== pixels[3]) return true;
  }
  return false;
}

export function svgHasDiagram(markup: string) {
  return markup.includes('data-er-table="1"') && !markup.includes('<foreignObject') && !markup.includes('<marker');
}

export function vectorTheme(background: string): VectorTheme {
  const style = getComputedStyle(document.documentElement);
  const pick = (name: string, fallback: string) => style.getPropertyValue(name).trim() || fallback;
  return {
    background,
    card: pick('--bg-popup', '#ffffff'),
    border: pick('--border-color', '#d8e1eb'),
    title: pick('--text-primary', '#172133'),
    text: pick('--text-primary', '#172133'),
    muted: pick('--text-secondary', '#5f6f86'),
    type: '#0ea5e9',
    key: '#f59e0b',
    linked: '#e11d48',
    highlight: '#e11d48',
    externalBorder: '#fb7185',
  };
}

export function collectVectorEdges(viewport: ParentNode, edges: Array<{ id: string; data?: ErEdgeData }>) {
  const paths = [...viewport.querySelectorAll('path.react-flow__edge-path')].flatMap((path) => {
    const id = path.getAttribute('id');
    const d = path.getAttribute('d');
    return id && d ? [{ id, d }] : [];
  });
  return vectorEdgesFromPaths(paths, edges);
}

/** Fits every table and visible relation into the image, independent of pan and zoom. */
async function framedCapture(viewport: HTMLElement, nodeBounds: Rect, background: string) {
  await document.fonts.ready;
  await new Promise(requestAnimationFrame);
  await new Promise(requestAnimationFrame);
  let bounds = nodeBounds;
  viewport.querySelectorAll<SVGPathElement>('.react-flow__edge-path').forEach((path) => {
    // Routes may travel outside the node bounds; keep the entire line in the image.
    bounds = includeBounds(bounds, path.getBBox());
  });
  const { width, height } = exportImageSize(bounds);
  const fitted = getViewportForBounds(bounds, width, height, 0, 1, `${ER_EXPORT_PADDING}px`);
  return {
    backgroundColor: background,
    width,
    height,
    pixelRatio: 1,
    filter: (node: HTMLElement) => !(node instanceof Element && node.hasAttribute('data-er-export-hidden')),
    style: {
      width: `${width}px`,
      height: `${height}px`,
      transform: `translate(${fitted.x}px, ${fitted.y}px) scale(${fitted.zoom})`,
    },
  };
}

/** Exports the currently displayed fields and relations, independent of pan/zoom. */
export async function renderErDiagramPng(viewport: HTMLElement, nodeBounds: Rect, background: string): Promise<Blob> {
  const canvas = await toCanvas(viewport, await framedCapture(viewport, nodeBounds, background));
  const context = canvas.getContext('2d');
  if (!context || !hasImageContent(context.getImageData(0, 0, canvas.width, canvas.height).data)) {
    throw new Error('ER diagram capture is blank');
  }
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error('Unable to encode ER diagram')), 'image/png');
  });
}

/** Real SVG shapes so PowerPoint can insert the diagram and scale it without blurring. */
export function renderErDiagramSvg(diagram: VectorDiagram): Blob {
  const markup = buildErDiagramSvg(diagram);
  if (!svgHasDiagram(markup)) throw new Error('ER diagram capture is blank');
  return new Blob([markup], { type: 'image/svg+xml;charset=utf-8' });
}
