import { createContext, useContext, type MutableRefObject } from 'react';
import type { ErTable } from '../../services/table.service';
import type { DiagramRect } from './erDiagramRoute';
import type { ColumnFocus } from './erDiagramRelations';

export interface ErDiagramInteraction {
  linked: ReadonlySet<string>;
  highlighted: ReadonlySet<string>;
  junctionTables: ReadonlySet<string>;
  dimmedTables: ReadonlySet<string>;
  /** Tables matched by the name box. Null when the box is empty. */
  soughtTables: ReadonlySet<string> | null;
  active: ColumnFocus | null;
  draggingRef: MutableRefObject<boolean>;
  togglePin: (focus: ColumnFocus) => void;
  setHover: (focus: ColumnFocus) => void;
  clearHover: (focus: ColumnFocus) => void;
  busy: boolean;
  toggleExpanded: (nodeId: string) => void;
  /** Ctrl or Command click toggles this table in the current selection. */
  modifySelection: (nodeId: string) => void;
  openData: (table: ErTable) => void;
  openDdl: (table: ErTable) => void;
  openExternal: (table: ErTable) => void;
}

export const ErDiagramInteractionContext = createContext<ErDiagramInteraction | null>(null);
export const ErDiagramRectsContext = createContext<DiagramRect[]>([]);

export function useErDiagramInteraction() {
  const value = useContext(ErDiagramInteractionContext);
  if (!value) throw new Error('ER diagram interaction is unavailable');
  return value;
}
