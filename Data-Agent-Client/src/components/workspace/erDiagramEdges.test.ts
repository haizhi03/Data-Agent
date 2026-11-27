import { describe, expect, it } from 'vitest';
import type { ErRelation } from '../../services/table.service';
import { buildDiagramEdges, columnHandleId, overflowHandleId } from './erDiagramEdges';
import { toNodeId } from './erDiagramLayout';
import { columnKey, relationFromId, relationToId, tableId } from './erDiagramRelations';

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

function node(name: string, x: number) {
  return {
    id: toNodeId(tableId({ name })),
    position: { x, y: 20 },
    width: 260,
  };
}

describe('ER diagram edges', () => {
  it('attaches the child on the right and the parent on the left when the child sits to the left', () => {
    const edges = buildDiagramEdges(
      [node('orders', 0), node('users', 400)],
      [relation],
      { tableId: relationFromId(relation), column: 'user_id' },
      false,
    );
    expect(edges).toHaveLength(1);
    expect(edges[0].sourceHandle).toBe(columnHandleId('source', 'right', 'user_id'));
    expect(edges[0].targetHandle).toBe(columnHandleId('target', 'left', 'id'));
    expect(edges[0].data).toMatchObject({ fromMark: 'many', toMark: 'one', dimmed: false });
  });

  it('flips the handles when the child sits to the right of the parent', () => {
    const edges = buildDiagramEdges(
      [node('orders', 400), node('users', 0)],
      [relation],
      { tableId: relationToId(relation), column: 'id' },
      false,
    );
    expect(edges[0].sourceHandle).toBe(columnHandleId('source', 'left', 'user_id'));
    expect(edges[0].targetHandle).toBe(columnHandleId('target', 'right', 'id'));
  });

  it('draws nothing for an empty pinned set even when every line is shown', () => {
    const nodes = [node('orders', 0), node('users', 400)];
    const shown = buildDiagramEdges(nodes, [relation], null, true);
    expect(shown).toHaveLength(1);
    expect(buildDiagramEdges(nodes, [relation], null, true, new Set(), new Set(), [])).toEqual([]);
  });

  it('draws nothing until a column is active, and dims the other lines when every line is shown', () => {
    const nodes = [node('orders', 0), node('users', 400)];
    expect(buildDiagramEdges(nodes, [relation], null, false)).toEqual([]);
    expect(buildDiagramEdges(nodes, [relation], { tableId: tableId({ name: 'films' }), column: 'id' }, false)).toEqual([]);
    const shown = buildDiagramEdges(nodes, [relation], { tableId: tableId({ name: 'films' }), column: 'id' }, true);
    expect(shown[0].data?.dimmed).toBe(true);
  });

  it('anchors a hidden column on the expand bar instead of a row below the card', () => {
    const usersKey = tableId({ name: 'users' });
    const names = Array.from({ length: 13 }, (_, index) => `c${index}`);
    const linked = new Set(names.map((name) => columnKey(usersKey, name)));
    const hidden: ErRelation = { ...relation, toColumn: 'c12', toColumns: ['c12'] };
    const edges = buildDiagramEdges(
      [
        node('orders', 0),
        {
          id: toNodeId(usersKey),
          position: { x: 400, y: 20 },
          width: 260,
          expanded: false,
          columns: names.map((name) => ({ name })),
          tableKey: usersKey,
        },
      ],
      [hidden],
      { tableId: usersKey, column: 'c12' },
      false,
      linked,
    );
    expect(edges[0].targetHandle).toBe(overflowHandleId('target', 'left'));
  });
});
