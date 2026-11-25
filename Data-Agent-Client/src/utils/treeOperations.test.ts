import { describe, expect, it } from 'vitest';
import { ExplorerNodeType } from '../constants/explorer';
import type { ExplorerNode } from '../types/explorer';
import { collectDeletableChildren } from './treeOperations';

function node(id: string, type: ExplorerNodeType, children?: ExplorerNode[]): ExplorerNode {
  return { id, name: id, type, children };
}

describe('collectDeletableChildren', () => {
  it('unwraps group nodes so grouped tables are counted and deleted', () => {
    const children = [
      node('group-a', ExplorerNodeType.GROUP, [
        node('a1', ExplorerNodeType.TABLE),
        node('a2', ExplorerNodeType.TABLE),
      ]),
      node('b1', ExplorerNodeType.TABLE),
    ];

    expect(collectDeletableChildren(children).map((n) => n.id)).toEqual(['a1', 'a2', 'b1']);
  });

  it('drops empty placeholders', () => {
    const children = [node('empty', ExplorerNodeType.EMPTY), node('t1', ExplorerNodeType.TABLE)];

    expect(collectDeletableChildren(children).map((n) => n.id)).toEqual(['t1']);
  });

  it('returns an empty list when the folder only holds placeholders', () => {
    expect(collectDeletableChildren([node('empty', ExplorerNodeType.EMPTY)])).toEqual([]);
  });
});
