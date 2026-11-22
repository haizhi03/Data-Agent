import { useEffect, useState } from 'react';
import type { IHeaderParams } from 'ag-grid-community';
import { ArrowDown, ArrowUp, KeyRound } from 'lucide-react';

export interface ResultColumnHeaderMeta {
  label: string;
  typeLabel: string;
  typeFamily: string;
  primaryKey: boolean;
  tableLabel: string | null;
  comment: string | null;
}

type SortState = 'asc' | 'desc' | null;

function getSortState(column: IHeaderParams['column']): SortState {
  if (column.isSortAscending()) return 'asc';
  if (column.isSortDescending()) return 'desc';
  return null;
}

/** Two-line result header: column label (gold key when primary key) + type and comment. */
export function ResultColumnHeader(props: IHeaderParams) {
  const { api, column } = props;
  const meta = column.getColDef().headerComponentParams as ResultColumnHeaderMeta | undefined;
  const label = meta?.label ?? props.displayName;
  const tooltip = column.getColDef().headerTooltip;
  // The custom header replaces AG Grid's default label, so render the sort
  // direction here; clicking the header still cycles asc -> desc -> none.
  const [sort, setSort] = useState<SortState>(() => getSortState(column));

  useEffect(() => {
    const sync = () => setSort(getSortState(column));
    sync();
    api.addEventListener('sortChanged', sync);
    return () => api.removeEventListener('sortChanged', sync);
  }, [api, column]);

  return (
    <div className="result-grid__header" title={typeof tooltip === 'string' ? tooltip : undefined}>
      <span className="result-grid__header-name">
        {meta?.primaryKey ? (
          <KeyRound className="result-grid__pk-icon" aria-label="Primary key" />
        ) : null}
        <span className="result-grid__header-name-text">{label}</span>
        {sort === 'asc' ? (
          <ArrowUp className="result-grid__sort-icon" aria-hidden="true" />
        ) : sort === 'desc' ? (
          <ArrowDown className="result-grid__sort-icon" aria-hidden="true" />
        ) : null}
      </span>
      <span className="result-grid__header-meta">
        {meta?.typeLabel ? (
          <span className={`result-grid__header-type result-grid__type--${meta.typeFamily}`}>
            {meta.typeLabel}
          </span>
        ) : null}
        {meta?.comment ? (
          <span className="result-grid__header-comment">{meta.comment}</span>
        ) : null}
      </span>
    </div>
  );
}
