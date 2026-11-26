import { describe, expect, it } from 'vitest';
import { typeAllows } from './structureTypeRules';

describe('typeAllows', () => {
  const numeric = ['int', 'integer', 'bigint', 'decimal'];

  it('matches a type without treating a longer name as the same', () => {
    expect(typeAllows(numeric, 'INT')).toBe(true);
    expect(typeAllows(numeric, 'integer')).toBe(true);
    expect(typeAllows(numeric, 'interval')).toBe(false);
    expect(typeAllows(['varchar'], 'VARCHAR(11)')).toBe(true);
    expect(typeAllows(['char'], 'varchar')).toBe(false);
    expect(typeAllows(['varchar'], 'varchar2(20)')).toBe(true);
  });
});
