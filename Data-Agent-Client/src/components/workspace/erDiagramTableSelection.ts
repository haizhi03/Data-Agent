import type { ErTable } from '../../services/table.service';

/** Matches the server cap. The picker warns above this, and the button needs a larger schema. */
export const ER_DIAGRAM_TABLE_LIMIT = 80;

export type ErDiagramTableChoice =
  | { mode: 'default' }
  | { mode: 'all' }
  | { mode: 'names'; names: string[] };

export const DEFAULT_ER_TABLE_CHOICE: ErDiagramTableChoice = { mode: 'default' };

export function uniqueErTableNames(names: string[]): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const name of names) {
    const trimmed = name.trim();
    if (!trimmed) continue;
    const key = trimmed.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(trimmed);
  }
  return result;
}

export function localDrawnTableNames(tables: ErTable[]): string[] {
  return tables.filter((table) => table.external !== true && table.name).map((table) => table.name);
}

/** Names from the catalog list that are already drawn, using the catalog spelling. */
export function checkedNamesForDialog(allNames: string[], drawnNames: string[]): string[] {
  const drawn = new Set(drawnNames.map((name) => name.trim().toLowerCase()).filter(Boolean));
  return allNames.filter((name) => drawn.has(name.trim().toLowerCase()));
}

export function filterErTableNames(names: string[], query: string): string[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return names;
  return names.filter((name) => name.toLowerCase().includes(needle));
}

/** Every catalog name checked becomes "all", so the request does not list them. */
export function choiceFromChecked(allNames: string[], checked: ReadonlySet<string>): ErDiagramTableChoice {
  const names = allNames.filter((name) => checked.has(name));
  if (allNames.length > 0 && names.length === allNames.length) {
    return { mode: 'all' };
  }
  return { mode: 'names', names };
}

export function selectionIsSlow(
  selectedCount: number,
  allSelected: boolean,
  limit = ER_DIAGRAM_TABLE_LIMIT,
): boolean {
  return allSelected || selectedCount > limit;
}

export function showErDiagramTablePicker(
  tableCount: number | null | undefined,
  limit = ER_DIAGRAM_TABLE_LIMIT,
): boolean {
  return (tableCount ?? 0) > limit;
}

export function erDiagramLimitNotice(
  choice: ErDiagramTableChoice,
  truncated: boolean,
): 'truncated' | 'selected' | null {
  if (choice.mode === 'names') return 'selected';
  if (choice.mode === 'default' && truncated) return 'truncated';
  return null;
}
