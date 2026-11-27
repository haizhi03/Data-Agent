import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { useToast } from '../../hooks/useToast';
import { tableDataService, type BatchRowOperation } from '../../services/tableData.service';

interface UseBatchRowOpsSubmitArgs {
  connectionId: number | null | undefined;
  tableName: string;
  catalog?: string;
  schema?: string;
  /** Runs after a successful commit so callers can clear staged state / reload. */
  onSuccess: () => void | Promise<void>;
}

/**
 * Shared "submit staged operations" flow used by the table data editor and the
 * SQL result editor: batchRows + success toast + force confirmation + failures.
 */
export function useBatchRowOpsSubmit({
  connectionId,
  tableName,
  catalog,
  schema,
  onSuccess,
}: UseBatchRowOpsSubmitArgs) {
  const { t } = useTranslation();
  const toast = useToast();
  const [submitting, setSubmitting] = useState(false);
  const [forceConfirmOpen, setForceConfirmOpen] = useState(false);
  const [forceMessage, setForceMessage] = useState('');

  const submit = useCallback(
    async (operations: BatchRowOperation[], force = false, planHash?: string | null): Promise<boolean> => {
      if (operations.length === 0) {
        return true;
      }
      if (connectionId == null) {
        return false;
      }

      setSubmitting(true);
      try {
        const result = await tableDataService.batchRows({
          connectionId,
          tableName,
          catalog: catalog || undefined,
          schema: schema || undefined,
          operations,
          force,
          planHash: planHash ?? undefined,
        });

        if (result.success) {
          toast.success(t(I18N_KEYS.EXPLORER.BATCH_SUBMIT_SUCCESS, { count: result.total }));
          await onSuccess();
          return true;
        }

        if (result.errorCode === 'PLAN_STALE') {
          toast.error(t(I18N_KEYS.EXPLORER.BATCH_SUBMIT_PLAN_STALE));
          return false;
        }

        if (result.requiresForce) {
          setForceMessage(result.errorMessage ?? '');
          setForceConfirmOpen(true);
          return false;
        }

        const failedIndex = result.failedAtIndex != null ? result.failedAtIndex + 1 : '?';
        toast.error(
          t(I18N_KEYS.EXPLORER.BATCH_SUBMIT_FAILED_ITEM, {
            index: failedIndex,
            message: result.errorMessage ?? '',
          }),
        );
        return false;
      } catch (err) {
        toast.error((err as Error).message);
        return false;
      } finally {
        setSubmitting(false);
      }
    },
    [catalog, connectionId, onSuccess, schema, t, tableName, toast],
  );

  const closeForceConfirm = useCallback(() => {
    setForceConfirmOpen(false);
  }, []);

  return {
    submitting,
    forceConfirmOpen,
    forceMessage,
    submit,
    closeForceConfirm,
  };
}
