import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../ui/Dialog';
import { Button } from '../ui/Button';
import { Input } from '../ui/Input';
import { I18N_KEYS } from '../../constants/i18nKeys';

interface GroupNameDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  initialName: string;
  onConfirm: (name: string) => void;
}

/** Reusable name prompt for creating/renaming a table group. */
export function GroupNameDialog({
  open,
  onOpenChange,
  title,
  description,
  initialName,
  onConfirm,
}: GroupNameDialogProps) {
  const { t } = useTranslation();
  const [name, setName] = useState(initialName);

  useEffect(() => {
    if (open) {
      setName(initialName);
    }
  }, [open, initialName]);

  const trimmedName = name.trim();
  const canConfirm = trimmedName !== '' && trimmedName !== initialName;

  const handleConfirm = () => {
    if (!canConfirm) return;
    onConfirm(trimmedName);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[400px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <Input
          autoFocus
          value={name}
          placeholder={t(I18N_KEYS.EXPLORER.GROUP_NAME_PLACEHOLDER)}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') handleConfirm();
          }}
        />
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t(I18N_KEYS.CONNECTIONS.CANCEL)}
          </Button>
          <Button disabled={!canConfirm} onClick={handleConfirm}>
            {title}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
