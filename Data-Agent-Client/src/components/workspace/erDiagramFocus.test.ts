import { describe, expect, it } from 'vitest';
import type { ErRelation } from '../../services/table.service';
import { relationFromId, relationToId } from './erDiagramRelations';
import { focusHops, relationFocusKey, splitFocusRelations, visibleFocusHops } from './erDiagramFocus';

function relation(overrides: Partial<ErRelation> = {}): ErRelation {
  return {
    name: 'fk',
    fromTable: 'orders',
    fromColumn: 'address_id',
    fromColumns: ['address_id'],
    toTable: 'address',
    toColumn: 'id',
    toColumns: ['id'],
    cardinality: 'ONE_TO_MANY',
    ...overrides,
  };
}

describe('ER focus selection', () => {
  const orders = relationFromId(relation());
  const address = relationToId(relation());
  const users = relationToId(relation({ toTable: 'users', toColumn: 'id', toColumns: ['id'], fromColumn: 'user_id', fromColumns: ['user_id'] }));

  it('keeps a foreign key inside when both ends are selected, and one hop when only one end is', () => {
    const insideLink = relation();
    const outward = relation({ name: 'fk_user', fromColumn: 'user_id', fromColumns: ['user_id'], toTable: 'users', toColumn: 'id', toColumns: ['id'] });
    const unrelated = relation({ name: 'fk_other', fromTable: 'payment', fromColumn: 'order_id', fromColumns: ['order_id'], toTable: 'invoice', toColumn: 'id', toColumns: ['id'] });
    const split = splitFocusRelations([insideLink, outward, unrelated], new Set([orders, address]));

    expect(split.inside).toEqual([insideLink]);
    expect(split.outside).toEqual([outward]);
    expect(focusHops(split.outside, new Set([orders, address]))).toEqual([
      { outside: 'users', link: 'orders.user_id → users.id' },
    ]);
    expect(users).toContain('users');
  });

  it('shows only the first few outward hops in the status line', () => {
    const hops = ['a', 'b', 'c', 'd'].map((name) => ({ outside: name, link: `orders.id → ${name}.id` }));
    expect(visibleFocusHops(hops)).toEqual({
      shown: hops.slice(0, 3),
      extra: 1,
    });
  });

  it('gives each foreign key a stable key so one line can be removed', () => {
    const first = relation();
    const second = relation({ name: 'fk_user', fromColumn: 'user_id', fromColumns: ['user_id'], toTable: 'users' });
    expect(relationFocusKey(first)).not.toBe(relationFocusKey(second));
    expect(relationFocusKey(first)).toBe(relationFocusKey({ ...first }));
  });
});
