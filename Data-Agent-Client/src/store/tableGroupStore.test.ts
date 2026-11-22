import { beforeEach, describe, expect, it } from 'vitest';
import { tableGroupScopeKey, useTableGroupStore } from './tableGroupStore';

describe('removeTableFromGroups', () => {
  const scope = tableGroupScopeKey('1', 'db', 'public');

  beforeEach(() => {
    useTableGroupStore.setState({ groupsByScope: {} });
  });

  it('drops a deleted table from every manual group in the scope', () => {
    useTableGroupStore.setState({
      groupsByScope: {
        [scope]: [
          { id: 'g1', name: 'One', rule: { type: 'exact', tableNames: ['doomed', 'kept'] } },
          { id: 'g2', name: 'Two', rule: { type: 'exact', tableNames: ['doomed'] } },
        ],
      },
    });

    useTableGroupStore.getState().removeTableFromGroups(scope, 'doomed');

    expect(useTableGroupStore.getState().groupsByScope[scope].map((g) => g.rule)).toEqual([
      { type: 'exact', tableNames: ['kept'] },
      { type: 'exact', tableNames: [] },
    ]);
  });

  it('leaves rule groups and other scopes untouched', () => {
    const otherScope = tableGroupScopeKey('2', 'db', 'public');
    useTableGroupStore.setState({
      groupsByScope: {
        [scope]: [{ id: 'g1', name: 'Prefix', rule: { type: 'prefix', value: 'doomed' } }],
        [otherScope]: [{ id: 'g2', name: 'Manual', rule: { type: 'exact', tableNames: ['doomed'] } }],
      },
    });

    useTableGroupStore.getState().removeTableFromGroups(scope, 'doomed');

    expect(useTableGroupStore.getState().groupsByScope[scope][0].rule).toEqual({
      type: 'prefix',
      value: 'doomed',
    });
    expect(useTableGroupStore.getState().groupsByScope[otherScope][0].rule).toEqual({
      type: 'exact',
      tableNames: ['doomed'],
    });
  });

  it('keeps the same state when the table is in no group', () => {
    const groupsByScope = {
      [scope]: [{ id: 'g1', name: 'Manual', rule: { type: 'exact' as const, tableNames: ['other'] } }],
    };
    useTableGroupStore.setState({ groupsByScope });

    useTableGroupStore.getState().removeTableFromGroups(scope, 'doomed');

    expect(useTableGroupStore.getState().groupsByScope).toBe(groupsByScope);
  });
});

describe('removeGroupsForCatalog', () => {
  beforeEach(() => {
    useTableGroupStore.setState({ groupsByScope: {} });
  });

  it('drops every schema scope of the catalog and keeps siblings and other connections', () => {
    useTableGroupStore.setState({
      groupsByScope: {
        '1|app|public': [{ id: 'g1', name: 'One', rule: { type: 'exact', tableNames: ['a'] } }],
        '1|app|': [{ id: 'g2', name: 'Two', rule: { type: 'exact', tableNames: [] } }],
        '1|other|public': [{ id: 'g3', name: 'Three', rule: { type: 'exact', tableNames: [] } }],
        '10|app|public': [{ id: 'g4', name: 'Four', rule: { type: 'exact', tableNames: [] } }],
      },
    });

    useTableGroupStore.getState().removeGroupsForCatalog('1', 'app');

    expect(Object.keys(useTableGroupStore.getState().groupsByScope)).toEqual([
      '1|other|public',
      '10|app|public',
    ]);
  });

  it('keeps the same state when the catalog has no groups', () => {
    const groupsByScope = {
      '1|other|public': [{ id: 'g1', name: 'One', rule: { type: 'exact' as const, tableNames: [] } }],
    };
    useTableGroupStore.setState({ groupsByScope });

    useTableGroupStore.getState().removeGroupsForCatalog('1', 'app');

    expect(useTableGroupStore.getState().groupsByScope).toBe(groupsByScope);
  });
});

describe('removeGroupsForConnection', () => {
  beforeEach(() => {
    useTableGroupStore.setState({ groupsByScope: {} });
  });

  it('drops every scope of the connection without touching another connection', () => {
    useTableGroupStore.setState({
      groupsByScope: {
        '1|app|public': [{ id: 'g1', name: 'One', rule: { type: 'exact', tableNames: [] } }],
        '1|other|': [{ id: 'g2', name: 'Two', rule: { type: 'exact', tableNames: [] } }],
        '10|app|public': [{ id: 'g3', name: 'Three', rule: { type: 'exact', tableNames: [] } }],
      },
    });

    useTableGroupStore.getState().removeGroupsForConnection('1');

    expect(Object.keys(useTableGroupStore.getState().groupsByScope)).toEqual(['10|app|public']);
  });
});
