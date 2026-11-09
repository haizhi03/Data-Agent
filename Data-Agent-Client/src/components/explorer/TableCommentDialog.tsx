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
import { I18N_KEYS } from '../../constants/i18nKeys';

interface TableCommentDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  tableName: string;
  initialComment: string;
  onConfirm: (comment: string) => void;
  isPending: boolean;
}

export function TableCommentDialog({
  open,
  onOpenChange,
  tableName,
  initialComment,
  onConfirm,
  isPending,
}: TableCommentDialogProps) {
  const { t } = useTranslation();
  const [comment, setComment] = useState(initialComment);

  // Reset the textarea whenever the dialog is opened for another table.
  useEffect(() => {
    if (open) {
      setComment(initialComment);
    }
  }, [open, initialComment]);

  const canConfirm = !isPending && comment.trim() !== initialComment.trim();

  const handleConfirm = () => {
    if (!canConfirm) return;
    onConfirm(comment.trim());
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[440px]">
        <DialogHeader>
          <DialogTitle>{t(I18N_KEYS.EXPLORER.EDIT_TABLE_COMMENT)}</DialogTitle>
          <DialogDescription>
            {t(I18N_KEYS.EXPLORER.TABLE_COMMENT_PROMPT, { name: tableName })}
          </DialogDescription>
        </DialogHeader>
        <textarea
          autoFocus
          rows={4}
          value={comment}
          disabled={isPending}
          placeholder={t(I18N_KEYS.EXPLORER.TABLE_COMMENT_PLACEHOLDER)}
          onChange={(e) => setComment(e.target.value)}
          className="w-full resize-none rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
        />
        <p className="text-xs theme-text-secondary opacity-70">
          {t(I18N_KEYS.EXPLORER.TABLE_COMMENT_HINT)}
        </p>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isPending}>
            {t(I18N_KEYS.CONNECTIONS.CANCEL)}
          </Button>
          <Button disabled={!canConfirm} onClick={handleConfirm}>
            {isPending ? t(I18N_KEYS.COMMON.LOADING) + '...' : t(I18N_KEYS.EXPLORER.TABLE_COMMENT_SAVE)}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
