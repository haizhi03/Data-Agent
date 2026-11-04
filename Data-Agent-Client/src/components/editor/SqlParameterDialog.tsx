import { useEffect, useRef, useState } from 'react';
import * as DialogPrimitive from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '../ui/Button';
import { Dialog, DialogDescription, DialogFooter, DialogHeader, DialogOverlay, DialogPortal, DialogTitle } from '../ui/Dialog';
import { cn } from '../../lib/utils';
import { I18N_KEYS } from '../../constants/i18nKeys';
import type { SqlVariable } from '../../utils/sqlVariables';

interface SqlParameterDialogProps {
  open: boolean;
  variables: SqlVariable[];
  onSubmit: (values: Record<string, string>) => void;
  onCancel: () => void;
}

/** Collects values for `${name}` placeholders before the script is executed. */
export function SqlParameterDialog({ open, variables, onSubmit, onCancel }: SqlParameterDialogProps) {
  const { t } = useTranslation();
  const [values, setValues] = useState<Record<string, string>>({});
  const firstInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!open) return;
    setValues({});
    const timer = window.setTimeout(() => firstInputRef.current?.focus(), 0);
    return () => window.clearTimeout(timer);
  }, [open, variables]);

  const complete = variables.every(variable => (values[variable.name] ?? '').trim().length > 0);

  const submit = () => {
    if (!complete) return;
    onSubmit(Object.fromEntries(variables.map(variable => [variable.name, values[variable.name] ?? ''])));
  };

  return (
    <Dialog open={open} onOpenChange={next => { if (!next) onCancel(); }}>
      <DialogPortal>
        <DialogOverlay className="z-[220]" />
        <DialogPrimitive.Content
          className={cn(
            'fixed left-[50%] top-[50%] z-[221] grid w-full max-w-[460px] translate-x-[-50%] translate-y-[-50%] gap-4 border border-border bg-background p-6 shadow-lg duration-200',
            'data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
            'data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95',
            'data-[state=closed]:slide-out-to-left-1/2 data-[state=closed]:slide-out-to-top-[48%]',
            'data-[state=open]:slide-in-from-left-1/2 data-[state=open]:slide-in-from-top-[48%] sm:rounded-lg'
          )}
          onKeyDown={event => {
            if (event.key === 'Enter' && event.target instanceof HTMLInputElement) {
              event.preventDefault();
              submit();
            }
          }}
        >
          <DialogHeader>
            <DialogTitle>{t(I18N_KEYS.SQL_PARAMETER.TITLE)}</DialogTitle>
            <DialogDescription>
              {t(I18N_KEYS.SQL_PARAMETER.DESCRIPTION, { count: variables.length })}
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-3">
            {variables.map((variable, index) => (
              <label key={variable.name} className="block space-y-1">
                <span className="flex items-center justify-between gap-2 text-xs font-medium">
                  <span className="font-mono">{`\${${variable.name}}`}</span>
                  <span className="text-muted-foreground">
                    {t(I18N_KEYS.SQL_PARAMETER.OCCURRENCES, { count: variable.occurrences })}
                  </span>
                </span>
                <input
                  ref={index === 0 ? firstInputRef : undefined}
                  value={values[variable.name] ?? ''}
                  onChange={event => setValues(current => ({ ...current, [variable.name]: event.target.value }))}
                  placeholder={t(I18N_KEYS.SQL_PARAMETER.PLACEHOLDER, { name: variable.name })}
                  className="h-9 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus:ring-2 focus:ring-ring"
                  spellCheck={false}
                  autoComplete="off"
                />
              </label>
            ))}
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={onCancel}>
              {t(I18N_KEYS.SQL_PARAMETER.CANCEL)}
            </Button>
            <Button onClick={submit} disabled={!complete}>
              {t(I18N_KEYS.SQL_PARAMETER.CONFIRM)}
            </Button>
          </DialogFooter>

          <DialogPrimitive.Close className="absolute right-4 top-4 rounded-sm opacity-70 transition-opacity hover:opacity-100 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2">
            <X className="h-4 w-4" />
            <span className="sr-only">Close</span>
          </DialogPrimitive.Close>
        </DialogPrimitive.Content>
      </DialogPortal>
    </Dialog>
  );
}
