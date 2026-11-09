import { describe, expect, it } from 'vitest';
import { ExplorerNodeType } from '../constants/explorer';
import type { ExplorerNode } from '../types/explorer';
import { collectDeletableChildren, updateNodeCommentById } from './treeOperations';

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

describe('updateNodeCommentById', () => {
  const tree = (): ExplorerNode[] => [
    node('root', ExplorerNodeType.ROOT, [
      node('folder', ExplorerNodeType.FOLDER, [
        { ...node('t1', ExplorerNodeType.TABLE), comment: 'Old' },
        node('t2', ExplorerNodeType.TABLE),
      ]),
    ]),
  ];

  it('sets the comment on a nested node without touching siblings', () => {
    const updated = updateNodeCommentById(tree(), 't1', 'New comment');
    const folder = updated[0].children![0];
    expect(folder.children![0].comment).toBe('New comment');
    expect(folder.children![1].comment).toBeUndefined();
  });

  it('removes the field when the comment is blank', () => {
    const updated = updateNodeCommentById(tree(), 't1', '   ');
    expect(updated[0].children![0].children![0].comment).toBeUndefined();
  });

  it('adds a comment to a node without one', () => {
    const updated = updateNodeCommentById(tree(), 't2', 'Fresh');
    expect(updated[0].children![0].children![1].comment).toBe('Fresh');
  });
});
