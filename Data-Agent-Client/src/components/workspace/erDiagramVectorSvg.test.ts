import { describe, expect, it } from 'vitest';
import type { ErTable } from '../../services/table.service';
import {
  buildErDiagramSvg,
  cardinalityMark,
  fitText,
  parseSvgPath,
  vectorEdgesFromPaths,
  vectorTablesFromNodes,
  type VectorTheme,
} from './erDiagramVectorSvg';

const theme: VectorTheme = {
  background: '#f4f7fb',
  card: '#ffffff',
  border: '#d8e1eb',
  title: '#172133',
  text: '#172133',
  muted: '#5f6f86',
  type: '#0ea5e9',
  key: '#f59e0b',
  linked: '#e11d48',
  highlight: '#e11d48',
  externalBorder: '#fb7185',
};

function table(name: string): ErTable {
  return {
    name,
    comment: '',
    columns: [{ name: 'id', typeName: 'int', comment: 'key', primaryKey: true }],
  };
}

const labels = {
  linked: new Set<string>(),
  highlighted: new Set<string>(),
  junctions: new Set<string>(),
  external: '外部',
  junction: '关联表',
  expand: (hidden: number) => `展开其余 ${hidden} 个字段`,
  collapse: '收起字段',
};

describe('vector ER svg', () => {
  it('draws tables and cardinality marks as shapes, without embedding html', () => {
    const tables = vectorTablesFromNodes([{
      position: { x: 0, y: 0 },
      data: { table: table('orders'), expanded: false },
    }], labels);
    const edges = vectorEdgesFromPaths(
      [{ id: 'orders->users', d: 'M 0 20 L 80 20' }],
      [{ id: 'orders->users', data: { cross: false, manyToMany: false, fromMark: 'many', toMark: 'one', lane: 0, dimmed: false } }],
    );
    const svg = buildErDiagramSvg({ tables, edges, theme });
    expect(svg).toContain('data-er-table="1"');
    expect(svg).toContain('>orders</text>');
    expect(svg).toContain('>id</text>');
    expect(svg).not.toContain('foreignObject');
    expect(svg).not.toContain('<marker');
    expect(svg).toContain('M 12 20 L 0 14');
    expect(svg).toContain('M 78 26 L 78 14');
  });

  it('keeps routes outside the cards inside the view box', () => {
    const tables = vectorTablesFromNodes([{
      position: { x: 0, y: 0 },
      data: { table: table('orders'), expanded: false },
    }], labels);
    const svg = buildErDiagramSvg({
      tables,
      edges: [{
        points: [{ x: -16, y: -70 }, { x: 10, y: -70 }],
        color: '#e11d48',
        dashed: true,
        opacity: 1,
        fromMark: 'many',
        toMark: 'one',
      }],
      theme,
    });
    expect(svg).toContain('viewBox="-64 -118');
    expect(svg).toContain('stroke-dasharray="5 4"');
  });

  it('escapes table names and shortens text that would overflow the card', () => {
    const tables = vectorTablesFromNodes([{
      position: { x: 0, y: 0 },
      data: { table: table('a<b>&c'), expanded: false },
    }], labels);
    expect(buildErDiagramSvg({ tables, edges: [], theme })).toContain('>a&lt;b&gt;&amp;c</text>');
    expect(fitText('orders_table_with_a_very_long_name_that_cannot_fit', 12, 80).endsWith('…')).toBe(true);
  });

  it('parses the routed path and places a one-bar perpendicular to the line', () => {
    expect(parseSvgPath('M -16.5 -70 L 10 -70')).toEqual([
      { x: -16.5, y: -70 },
      { x: 10, y: -70 },
    ]);
    expect(cardinalityMark('one', { x: 40, y: 20 }, { x: 0, y: 20 })).toEqual(['M 38 26 L 38 14']);
  });
});
