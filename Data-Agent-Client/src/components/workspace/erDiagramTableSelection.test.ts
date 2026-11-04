import { describe, expect, it } from 'vitest';
import {
  checkedNamesForDialog,
  choiceFromChecked,
  erDiagramLimitNotice,
  filterErTableNames,
  selectionIsSlow,
  showErDiagramTablePicker,
  uniqueErTableNames,
} from './erDiagramTableSelection';

describe('ER diagram table selection', () => {
  it('drops blanks and duplicate spellings while keeping the first name', () => {
    expect(uniqueErTableNames([' Orders ', '', 'orders', 'Payments', ' payments '])).toEqual([
      'Orders',
      'Payments',
    ]);
  });

  it('checks the drawn local tables against the catalog spelling', () => {
    expect(checkedNamesForDialog(['Orders', 'Customers', 'Payments'], ['payments', 'Orders'])).toEqual([
      'Orders',
      'Payments',
    ]);
  });

  it('filters the picker list without changing the selection', () => {
    expect(filterErTableNames(['Orders', 'OrderItems', 'Customers'], ' order ')).toEqual([
      'Orders',
      'OrderItems',
    ]);
    expect(filterErTableNames(['Orders'], '   ')).toEqual(['Orders']);
  });

  it('sends every checked table as all, and a subset as names in catalog order', () => {
    const catalog = ['Orders', 'Customers', 'Payments'];

    expect(choiceFromChecked(catalog, new Set(catalog))).toEqual({ mode: 'all' });
    expect(choiceFromChecked(catalog, new Set(['Payments', 'Orders']))).toEqual({
      mode: 'names',
      names: ['Orders', 'Payments'],
    });
  });

  it('warns above 80 tables and when every table is selected', () => {
    expect(selectionIsSlow(80, false)).toBe(false);
    expect(selectionIsSlow(81, false)).toBe(true);
    expect(selectionIsSlow(80, true)).toBe(true);
  });

  it('shows the picker only when the schema itself is past the cap', () => {
    expect(showErDiagramTablePicker(80)).toBe(false);
    expect(showErDiagramTablePicker(81)).toBe(true);
    expect(showErDiagramTablePicker(undefined)).toBe(false);
  });

  it('keeps the first-batch hint, the chosen-batch hint, and a quiet full load apart', () => {
    expect(erDiagramLimitNotice({ mode: 'default' }, true)).toBe('truncated');
    expect(erDiagramLimitNotice({ mode: 'default' }, false)).toBeNull();
    expect(erDiagramLimitNotice({ mode: 'names', names: ['Orders'] }, true)).toBe('selected');
    expect(erDiagramLimitNotice({ mode: 'all' }, false)).toBeNull();
    expect(erDiagramLimitNotice({ mode: 'all' }, true)).toBeNull();
  });
});
