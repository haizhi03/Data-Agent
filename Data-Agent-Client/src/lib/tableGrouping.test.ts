import { beforeEach, describe, expect, it } from 'vitest';
import { ExplorerNodeType } from '../constants/explorer';
import { useTableGroupStore } from '../store/tableGroupStore';
import { buildTablesFolderChildren, matchRule } from './tableGrouping';

describe('matchRule', () => {
  it('matches explicit table names exactly', () => {
    expect(matchRule({ type: 'exact', tableNames: ['a', 'b'] }, 'a')).toBe(true);
    expect(matchRule({ type: 'exact', tableNames: ['a', 'b'] }, 'B')).toBe(false);
    expect(matchRule({ type: 'exact', tableNames: ['a'] }, 'c')).toBe(false);
  });

  it('matches prefix, suffix and contains case-insensitively by default', () => {
    expect(matchRule({ type: 'prefix', value: 'order_' }, 'order_1')).toBe(true);
    expect(matchRule({ type: 'prefix', value: 'order_' }, 'ORDER_1')).toBe(true);
    expect(matchRule({ type: 'prefix', value: 'order_' }, 'x_order')).toBe(false);

    expect(matchRule({ type: 'suffix', value: '_log' }, 'app_log')).toBe(true);
    expect(matchRule({ type: 'suffix', value: '_log' }, 'APP_LOG')).toBe(true);
    expect(matchRule({ type: 'suffix', value: '_log' }, 'log_app')).toBe(false);

    expect(matchRule({ type: 'contains', value: 'user' }, 'a_user_b')).toBe(true);
    expect(matchRule({ type: 'contains', value: 'user' }, 'A_USER_B')).toBe(true);
    expect(matchRule({ type: 'contains', value: 'user' }, 'usr')).toBe(false);
  });

  it('honors caseSensitive for prefix', () => {
    expect(matchRule({ type: 'prefix', value: 'Order_', caseSensitive: true }, 'Order_1')).toBe(true);
    expect(matchRule({ type: 'prefix', value: 'Order_', caseSensitive: true }, 'order_1')).toBe(false);
  });

  it('matches regex (case-insensitive by default) and swallows invalid patterns', () => {
    expect(matchRule({ type: 'regex', value: '^tbl_' }, 'tbl_a')).toBe(true);
    expect(matchRule({ type: 'regex', value: '^TBL_' }, 'tbl_a')).toBe(true);
    expect(matchRule({ type: 'regex', value: '^tbl_' }, 'atbl_a')).toBe(false);
    expect(matchRule({ type: 'regex', value: '(' }, 'anything')).toBe(false);
  });

  it('treats empty values as no match', () => {
    expect(matchRule({ type: 'prefix', value: '  ' }, 'abc')).toBe(false);
    expect(matchRule({ type: 'regex', value: '' }, 'abc')).toBe(false);
  });
});

describe('buildTablesFolderChildren', () => {
  beforeEach(() => {
    useTableGroupStore.setState({ groupsByScope: {} });
  });

  const folderOpts = (tableNames: string[]) => ({
    connectionId: '1',
    catalog: 'db',
    schema: 'public',
    folderId: 'f',
    tableNames,
  });

  it('returns flat table nodes sorted by name when there are no groups', () => {
    const nodes = buildTablesFolderChildren(folderOpts(['b', 'a', 'C']));
    expect(nodes.map((n) => n.name)).toEqual(['a', 'b', 'C']);
    expect(nodes.every((n) => n.type === ExplorerNodeType.TABLE)).toBe(true);
  });

  it('groups matching tables under group nodes and interleaves alphabetically', () => {
    useTableGroupStore.setState({
      groupsByScope: {
        '1|db|public': [
          { id: 'g1', name: 'Orders', rule: { type: 'prefix', value: 'order_' } },
          { id: 'g2', name: 'Logs', rule: { type: 'exact', tableNames: ['error_log'] } },
        ],
      },
    });

    const nodes = buildTablesFolderChildren(folderOpts(['order_1', 'order_2', 'error_log', 'other']));

    const byName = Object.fromEntries(nodes.map((n) => [n.name, n]));
    expect(byName.Orders.type).toBe(ExplorerNodeType.GROUP);
    expect(byName.Orders.groupId).toBe('g1');
    expect(byName.Orders.groupKind).toBe('rule');
    expect(byName.Orders.children?.map((c) => c.name)).toEqual(['order_1', 'order_2']);

    expect(byName.Logs.type).toBe(ExplorerNodeType.GROUP);
    expect(byName.Logs.groupKind).toBe('manual');
    expect(byName.Logs.children?.map((c) => c.name)).toEqual(['error_log']);

    expect(byName.other.type).toBe(ExplorerNodeType.TABLE);
    // Manual "Logs" and rule "Orders" and table "other" sort case-insensitively.
    expect(nodes.map((n) => n.name)).toEqual(['Logs', 'Orders', 'other']);
  });

  it('gives manual groups priority over rule groups (single parent)', () => {
    useTableGroupStore.setState({
      groupsByScope: {
        '1|db|public': [
          { id: 'first', name: 'First', rule: { type: 'prefix', value: 'order_' } },
          { id: 'second', name: 'Second', rule: { type: 'exact', tableNames: ['order_1'] } },
        ],
      },
    });

    const nodes = buildTablesFolderChildren(folderOpts(['order_1', 'order_2']));
    const first = nodes.find((n) => n.id === 'f-group-first')!;
    const second = nodes.find((n) => n.id === 'f-group-second')!;

    // Even though the rule group comes first, the explicit assignment wins;
    // order_2 has no manual assignment and stays in the prefix group.
    expect(second.children?.map((c) => c.name)).toEqual(['order_1']);
    expect(first.children?.map((c) => c.name)).toEqual(['order_2']);
  });

  it('gives the first matching rule group priority (single parent)', () => {
    useTableGroupStore.setState({
      groupsByScope: {
        '1|db|public': [
          { id: 'first', name: 'First', rule: { type: 'prefix', value: 'order_' } },
          { id: 'second', name: 'Second', rule: { type: 'contains', value: 'order' } },
        ],
      },
    });

    const nodes = buildTablesFolderChildren(folderOpts(['order_1']));
    const first = nodes.find((n) => n.id === 'f-group-first')!;
    const second = nodes.find((n) => n.id === 'f-group-second')!;

    expect(first.children?.map((c) => c.name)).toEqual(['order_1']);
    expect(second.children).toEqual([]);
  });

  it('builds unique node ids for tables nested inside a group', () => {
    useTableGroupStore.setState({
      groupsByScope: {
        '1|db|public': [{ id: 'g', name: 'G', rule: { type: 'prefix', value: 't_' } }],
      },
    });

    const nodes = buildTablesFolderChildren(folderOpts(['t_a', 'plain']));
    const group = nodes.find((n) => n.type === ExplorerNodeType.GROUP)!;
    const nested = group.children![0];
    expect(nested.id).toBe('f-group-g-table-t_a');
    expect(nested.connectionId).toBe('1');
    expect(nested.catalog).toBe('db');
    expect(nested.schema).toBe('public');
    // Group membership is stamped on the table node so drag-and-drop can
    // distinguish manual vs rule membership and find the source group.
    expect(nested.groupId).toBe('g');
    expect(nested.groupKind).toBe('rule');
    const ungrouped = nodes.find((n) => n.name === 'plain')!;
    expect(ungrouped.groupId).toBeUndefined();
    expect(ungrouped.groupKind).toBeUndefined();
  });
});
