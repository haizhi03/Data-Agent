import { describe, expect, it } from 'vitest';
import type { ErRelation, ErTable } from '../../services/table.service';
import { columnKey, tableId } from './erDiagramRelations';
import {
  cardHeight,
  shiftCardsForResize,
  ER_MIN_ZOOM,
  ER_MINIMAP_HIDE_SLACK,
  ER_MINIMAP_SHOW_SLACK,
  formatZoomPercent,
  minimapNeeded,
  clearStoredLayout,
  hasStoredLayout,
  layoutBounds,
  layoutDiagram,
  mergeStoredPositions,
  normalizeLayout,
  viewportForDiagram,
  viewportShowsAllTables,
  readStoredLayout,
  toNodeId,
  visibleColumns,
  writeStoredLayout,
  type LayoutPoint,
  type LayoutStore,
} from './erDiagramLayout';

function memoryStore(initial: Record<string, string> = {}): LayoutStore {
  const data = { ...initial };
  return {
    getItem: (key) => (key in data ? data[key] : null),
    setItem: (key, value) => {
      data[key] = value;
    },
    removeItem: (key) => {
      delete data[key];
    },
  };
}

function table(name: string, columns = 2): ErTable {
  return {
    name,
    comment: '',
    columns: Array.from({ length: columns }, (_, index) => ({
      name: `c${index}`,
      typeName: 'int',
      comment: '',
      primaryKey: index === 0,
    })),
  };
}

function point(id: string, x: number, y: number): LayoutPoint {
  return { id, x, y, width: 260, height: 86 };
}

describe('ER diagram card height', () => {
  it('keeps a long table collapsed until that table is expanded', () => {
    expect(cardHeight(20)).toBe(34 + 12 * 22 + 22 + 2);
    expect(cardHeight(20, true)).toBeGreaterThan(cardHeight(20));
    expect(cardHeight(8)).toBe(34 + 8 * 22 + 2);
  });

  it('shifts only the column under a resized card and keeps the card itself', () => {
    const collapsed = cardHeight(20);
    const expanded = cardHeight(20, true);
    const cards = [
      { id: 'long', x: 24, y: 24, width: 260, height: expanded },
      { id: 'below', x: 24, y: 24 + collapsed + 48, width: 260, height: 80 },
      { id: 'beside', x: 24 + 260 + 100, y: 24 + collapsed + 48, width: 260, height: 80 },
    ];
    const grown = shiftCardsForResize(cards, 'long', collapsed);
    expect(grown.find((card) => card.id === 'long')).toMatchObject({ x: 24, y: 24, height: expanded });
    expect(grown.find((card) => card.id === 'below')?.y).toBe(24 + expanded + 48);
    expect(grown.find((card) => card.id === 'beside')?.y).toBe(24 + collapsed + 48);

    const shrunk = shiftCardsForResize(
      grown.map((card) => (card.id === 'long' ? { ...card, height: collapsed } : card)),
      'long',
      expanded,
    );
    expect(shrunk.find((card) => card.id === 'below')?.y).toBe(24 + collapsed + 48);
    expect(shrunk.find((card) => card.id === 'beside')?.y).toBe(24 + collapsed + 48);
  });

  it('keeps a linked column that would otherwise sit below the fold', () => {
    const columns = Array.from({ length: 14 }, (_, index) => ({ name: `c${index}` }));
    const tableKey = '\0app\0users';
    const linked = new Set([columnKey(tableKey, 'c13')]);
    const shown = visibleColumns(columns, linked, new Set(), tableKey, false);
    expect(shown).toHaveLength(12);
    expect(shown.map((column) => column.name)).toContain('c13');
    expect(shown.map((column) => column.name)).not.toContain('c12');
  });
});

describe('ER diagram layout storage', () => {
  it('uses XML-safe, distinct node IDs for scoped and quoted table names', () => {
    const names = ['\0app\0users', '\0audit\0users', '\0app\0用户"表'];
    const ids = names.map(toNodeId);
    expect(new Set(ids).size).toBe(names.length);
    ids.forEach((id, index) => {
      expect(decodeURIComponent(id)).toBe(names[index]);
      expect(id).not.toMatch(/[\u0000-\u001f"<>]/);
    });
  });

  it('reads layouts saved before node IDs were made XML-safe', () => {
    const oldId = '\u001fapp\u001fusers';
    const store = memoryStore({ er: JSON.stringify({ [oldId]: { x: 10, y: 20 } }) });
    expect(readStoredLayout('er', store)).toEqual({ [toNodeId('\0app\0users')]: { x: 10, y: 20 } });
  });

  it('keeps a saved position and leaves the others on the computed layout', () => {
    const points = [point('users', 10, 20), point('orders', 400, 20)];
    const merged = mergeStoredPositions(points, { orders: { x: 12, y: 80 } });
    expect(merged.find((item) => item.id === 'users')).toMatchObject({ x: 10, y: 20 });
    expect(merged.find((item) => item.id === 'orders')).toMatchObject({ x: 12, y: 80 });
  });

  it('merges a drag so a hidden table keeps its saved position', () => {
    const store = memoryStore();
    writeStoredLayout('er', { users: { x: 1, y: 2 }, orders: { x: 3, y: 4 } }, store);
    writeStoredLayout('er', { users: { x: 9, y: 8 } }, store);
    expect(readStoredLayout('er', store)).toEqual({
      users: { x: 9, y: 8 },
      orders: { x: 3, y: 4 },
    });
    expect(hasStoredLayout(readStoredLayout('er', store))).toBe(true);
    clearStoredLayout('er', store);
    expect(readStoredLayout('er', store)).toBeNull();
  });

  it('uses an image-export-safe id and still reads a previously saved position', () => {
    const id = toNodeId('app\0dbo\0orders');
    expect(id).toBe('app%00dbo%00orders');
    expect(id).not.toMatch(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/);
    const legacy = 'app\u001fdbo\u001forders';
    const store = memoryStore({ er: JSON.stringify({ [legacy]: { x: 12, y: 40 } }) });
    expect(readStoredLayout('er', store)).toEqual({ [id]: { x: 12, y: 40 } });
  });

  it('ignores a stored value that is not a layout', () => {
    const store = memoryStore({ er: '{not json', bad: '[]', partial: '{"a":{"x":1}}' });
    expect(readStoredLayout('er', store)).toBeNull();
    expect(readStoredLayout('bad', store)).toBeNull();
    expect(readStoredLayout('partial', store)).toEqual({});
  });
});

describe('ER diagram zoom label', () => {
  it('rounds the viewport zoom to a percent', () => {
    expect(formatZoomPercent(1)).toBe('100%');
    expect(formatZoomPercent(0.08)).toBe('8%');
    expect(formatZoomPercent(1.8)).toBe('180%');
    expect(formatZoomPercent(0.854)).toBe('85%');
  });
});

describe('ER diagram minimap visibility', () => {
  const pane = { width: 1000, height: 700 };
  const origin = { x: 0, y: 0, zoom: 1 };
  const card = (x: number) => ({ x, y: 24, width: 260, height: 80 });

  it('keeps the minimap hidden when the fit view already contains every table', () => {
    const bounds = { x: 24, y: 24, width: 900, height: 1400 };
    const viewport = viewportForDiagram(bounds, pane.width, pane.height);
    expect(viewport).toBeTruthy();
    expect(viewportShowsAllTables([bounds], viewport!, pane.width, pane.height, ER_MINIMAP_HIDE_SLACK)).toBe(true);
    expect(minimapNeeded(false, [bounds], viewport!, pane.width, pane.height)).toBe(false);
  });

  it('asks for the minimap when the minimum zoom still cannot contain the diagram', () => {
    const bounds = { x: 24, y: 24, width: 20000, height: 400 };
    const viewport = viewportForDiagram(bounds, pane.width, pane.height)!;
    expect(viewport.zoom).toBe(ER_MIN_ZOOM);
    expect(minimapNeeded(false, [bounds], viewport, pane.width, pane.height)).toBe(true);
  });

  it('waits until a table is clearly outside, then keeps the minimap until the view is comfortably full', () => {
    const barelyOutside = [card(-ER_MINIMAP_SHOW_SLACK)];
    const clearlyOutside = [card(-(ER_MINIMAP_SHOW_SLACK + 1))];
    const stillClipped = [card(-(ER_MINIMAP_HIDE_SLACK + 1))];
    expect(minimapNeeded(false, barelyOutside, origin, pane.width, pane.height)).toBe(false);
    expect(minimapNeeded(false, clearlyOutside, origin, pane.width, pane.height)).toBe(true);
    expect(minimapNeeded(true, stillClipped, origin, pane.width, pane.height)).toBe(true);
    expect(minimapNeeded(true, [card(-ER_MINIMAP_HIDE_SLACK)], origin, pane.width, pane.height)).toBe(false);
  });

  it('treats a zoomed-in table as outside the window without changing the decision rule', () => {
    const tables = [card(24), card(400)];
    const zoomed = { x: -24 * 2, y: 0, zoom: 2 };
    expect(minimapNeeded(false, tables, zoomed, pane.width, pane.height)).toBe(true);
    expect(viewportShowsAllTables([], origin, pane.width, pane.height)).toBe(true);
  });
});

describe('ER diagram opening frame', () => {
  it('anchors the laid-out cards at the top-left origin', () => {
    const shifted = normalizeLayout([
      point('users', 400, 660),
      point('orders', 80, 900),
    ]);
    expect(Math.min(...shifted.map((item) => item.x))).toBe(24);
    expect(Math.min(...shifted.map((item) => item.y))).toBe(24);
    expect(shifted[0].x - shifted[1].x).toBe(320);
    expect(shifted[0].y - shifted[1].y).toBe(-240);
  });

  it('fits a short diagram to the top of the window', () => {
    const bounds = layoutBounds([
      { x: 24, y: 24, width: 260, height: 80 },
      { x: 356, y: 24, width: 260, height: 120 },
    ]);
    const viewport = viewportForDiagram(bounds, 1200, 800);
    expect(viewport?.zoom).toBe(1);
    expect(viewport && viewport.y + bounds.y * viewport.zoom).toBe(32);
    expect(viewport && viewport.x + bounds.x * viewport.zoom).toBeGreaterThan(32);
  });

  it('shrinks a diagram that would otherwise run past the window', () => {
    const bounds = { x: 24, y: 24, width: 900, height: 1400 };
    const viewport = viewportForDiagram(bounds, 1000, 700);
    expect(viewport).toBeTruthy();
    const zoom = viewport!.zoom;
    expect(zoom).toBeLessThan(1);
    const top = viewport!.y + bounds.y * zoom;
    const bottom = viewport!.y + (bounds.y + bounds.height) * zoom;
    const left = viewport!.x + bounds.x * zoom;
    const right = viewport!.x + (bounds.x + bounds.width) * zoom;
    expect(top).toBeCloseTo(32);
    expect(bottom).toBeLessThanOrEqual(700 - 32 + 0.01);
    expect(left).toBeGreaterThanOrEqual(32 - 0.01);
    expect(right).toBeLessThanOrEqual(1000 - 32 + 0.01);
  });
});

describe('ELK diagram layout', () => {
  it('lays out expanded tables at their full height without overlapping another table', async () => {
    const tables = [table('long_table', 100), table('other'), table('third')];
    const points = await layoutDiagram(tables, [], new Set([toNodeId(tableId(tables[0]))]));
    expect(points[0].height).toBe(cardHeight(100, true));
    for (let i = 0; i < points.length; i += 1) {
      for (let j = i + 1; j < points.length; j += 1) {
        const a = points[i];
        const b = points[j];
        expect(a.x + a.width <= b.x || b.x + b.width <= a.x
          || a.y + a.height <= b.y || b.y + b.height <= a.y).toBe(true);
      }
    }
  });

  it('places a child table to the side of the table it references', async () => {
    const users = table('users');
    const orders = table('orders');
    const relation: ErRelation = {
      name: 'fk_user',
      fromTable: 'orders',
      fromColumn: 'user_id',
      fromColumns: ['user_id'],
      toTable: 'users',
      toColumn: 'id',
      toColumns: ['id'],
      cardinality: 'ONE_TO_MANY',
    };
    const points = await layoutDiagram([users, orders], [relation]);
    expect(points).toHaveLength(2);
    const left = points.find((item) => item.id.endsWith('users'));
    const right = points.find((item) => item.id.endsWith('orders'));
    expect(left && right).toBeTruthy();
    expect(Number.isFinite(left!.x) && Number.isFinite(right!.x)).toBe(true);
    expect(left!.x).not.toBe(right!.x);
  });
});
