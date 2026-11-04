import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { Button } from '../ui/Button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../ui/Dialog';
import {
  checkedNamesForDialog,
  choiceFromChecked,
  filterErTableNames,
  selectionIsSlow,
  type ErDiagramTableChoice,
} from './erDiagramTableSelection';

interface ErDiagramTableDialogProps {
  open: boolean;
  names: string[];
  namesLoading: boolean;
  namesError: string;
  drawnNames: string[];
  onOpenChange: (open: boolean) => void;
  onConfirm: (choice: ErDiagramTableChoice) => void;
}

export function ErDiagramTableDialog({
  open,
  names,
  namesLoading,
  namesError,
  drawnNames,
  onOpenChange,
  onConfirm,
}: ErDiagramTableDialogProps) {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const [checked, setChecked] = useState<Set<string>>(() => new Set());
  const allRef = useRef<HTMLInputElement>(null);
  const visible = filterErTableNames(names, query);
  const allSelected = names.length > 0 && checked.size === names.length;
  const slow = selectionIsSlow(checked.size, allSelected);

  useEffect(() => {
    if (!open) return;
    setQuery('');
    setChecked(new Set(checkedNamesForDialog(names, drawnNames)));
  }, [drawnNames, names, open]);

  useEffect(() => {
    if (allRef.current) {
      allRef.current.indeterminate = checked.size > 0 && !allSelected;
    }
  }, [allSelected, checked.size]);

  const toggleAll = () => {
    setChecked(allSelected ? new Set() : new Set(names));
  };

  const toggleName = (name: string) => {
    setChecked((current) => {
      const next = new Set(current);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  };

  const confirm = () => {
    if (checked.size === 0 || namesLoading || namesError || names.length === 0) return;
    onConfirm(choiceFromChecked(names, checked));
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle className="text-base">{t(I18N_KEYS.EXPLORER.ER_DIAGRAM_SELECT_TABLES_TITLE)}</DialogTitle>
          <DialogDescription className={checked.size === 0 ? 'text-destructive' : undefined}>
            {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_SELECT_TABLES_COUNT, { count: checked.size })}
          </DialogDescription>
        </DialogHeader>
        <input
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_SELECT_TABLES_SEARCH)}
          aria-label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_SELECT_TABLES_SEARCH)}
          disabled={namesLoading || !!namesError}
          className="h-8 w-full rounded border theme-border bg-transparent px-2 text-xs theme-text-primary outline-none"
        />
        <label className="inline-flex items-center gap-2 text-xs theme-text-primary">
          <input
            ref={allRef}
            type="checkbox"
            className="accent-violet-600"
            checked={allSelected}
            disabled={namesLoading || names.length === 0}
            onChange={toggleAll}
          />
          {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_SELECT_TABLES_ALL)}
        </label>
        <div className="h-72 overflow-y-auto rounded border theme-border px-2 py-1">
          {namesLoading ? (
            <div className="flex h-full items-center justify-center text-xs theme-text-secondary">
              {t(I18N_KEYS.EXPLORER.LOADING)}
            </div>
          ) : namesError ? (
            <div className="flex h-full items-center justify-center px-3 text-center text-xs text-destructive">
              {namesError}
            </div>
          ) : visible.length === 0 ? (
            <div className="flex h-full items-center justify-center text-xs theme-text-secondary">
              {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FILTER_EMPTY)}
            </div>
          ) : (
            visible.map((name) => (
              <label key={name} className="flex cursor-pointer items-center gap-2 rounded px-1 py-1 text-sm theme-text-primary hover:bg-accent/40">
                <input
                  type="checkbox"
                  className="accent-violet-600"
                  checked={checked.has(name)}
                  onChange={() => toggleName(name)}
                />
                <span className="truncate">{name}</span>
              </label>
            ))
          )}
        </div>
        {slow && (
          <p className="text-xs text-amber-600 dark:text-amber-400">
            {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_SELECT_TABLES_SLOW)}
          </p>
        )}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            {t(I18N_KEYS.COMMON.CANCEL)}
          </Button>
          <Button
            type="button"
            disabled={checked.size === 0 || namesLoading || !!namesError}
            onClick={confirm}
          >
            {t(I18N_KEYS.COMMON.CONFIRM)}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
