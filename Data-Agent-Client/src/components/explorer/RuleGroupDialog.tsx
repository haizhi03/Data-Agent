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
import type { TableGroupRule } from '../../store/tableGroupStore';

type RuleType = Exclude<TableGroupRule['type'], 'exact'>;

const RULE_TYPES: RuleType[] = ['prefix', 'suffix', 'contains', 'regex'];

const RULE_TYPE_I18N: Record<RuleType, string> = {
  prefix: I18N_KEYS.EXPLORER.GROUP_RULE_PREFIX,
  suffix: I18N_KEYS.EXPLORER.GROUP_RULE_SUFFIX,
  contains: I18N_KEYS.EXPLORER.GROUP_RULE_CONTAINS,
  regex: I18N_KEYS.EXPLORER.GROUP_RULE_REGEX,
};

/** A malformed regex would silently match nothing, so reject it up front. */
function isValidRegex(pattern: string): boolean {
  try {
    new RegExp(pattern);
    return true;
  } catch {
    return false;
  }
}

interface RuleGroupDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  initialName: string;
  initialRule: TableGroupRule;
  onConfirm: (name: string, rule: TableGroupRule) => void;
}

/** Dialog for creating/editing a rule-based table group. */
export function RuleGroupDialog({
  open,
  onOpenChange,
  title,
  initialName,
  initialRule,
  onConfirm,
}: RuleGroupDialogProps) {
  const { t } = useTranslation();
  const [name, setName] = useState(initialName);
  const [ruleType, setRuleType] = useState<RuleType>(
    initialRule.type === 'exact' ? 'prefix' : initialRule.type
  );
  const [ruleValue, setRuleValue] = useState(initialRule.type === 'exact' ? '' : initialRule.value);
  const [caseSensitive, setCaseSensitive] = useState(
    initialRule.type !== 'exact' && !!initialRule.caseSensitive
  );

  useEffect(() => {
    if (open) {
      setName(initialName);
      setRuleType(initialRule.type === 'exact' ? 'prefix' : initialRule.type);
      setRuleValue(initialRule.type === 'exact' ? '' : initialRule.value);
      setCaseSensitive(initialRule.type !== 'exact' && !!initialRule.caseSensitive);
    }
  }, [open, initialName, initialRule]);

  const trimmedName = name.trim();
  const regexInvalid =
    ruleType === 'regex' && ruleValue.trim() !== '' && !isValidRegex(ruleValue);
  const canConfirm = trimmedName !== '' && ruleValue.trim() !== '' && !regexInvalid;

  const handleConfirm = () => {
    if (!canConfirm) return;
    const rule: TableGroupRule = { type: ruleType, value: ruleValue, caseSensitive };
    onConfirm(trimmedName, rule);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[420px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>
            {t(I18N_KEYS.EXPLORER.GROUP_NAME)}
          </DialogDescription>
        </DialogHeader>

        <Input
          autoFocus
          value={name}
          placeholder={t(I18N_KEYS.EXPLORER.GROUP_NAME_PLACEHOLDER)}
          onChange={(e) => setName(e.target.value)}
        />

        <div className="grid grid-cols-2 gap-3">
          <label className="flex flex-col gap-1.5 text-sm text-muted-foreground">
            {t(I18N_KEYS.EXPLORER.GROUP_RULE_TYPE)}
            <select
              className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              value={ruleType}
              onChange={(e) => setRuleType(e.target.value as RuleType)}
            >
              {RULE_TYPES.map((type) => (
                <option key={type} value={type}>
                  {t(RULE_TYPE_I18N[type])}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1.5 text-sm text-muted-foreground">
            {t(I18N_KEYS.EXPLORER.GROUP_RULE_VALUE)}
            <Input
              value={ruleValue}
              placeholder={t(I18N_KEYS.EXPLORER.GROUP_RULE_VALUE_PLACEHOLDER)}
              onChange={(e) => setRuleValue(e.target.value)}
            />
          </label>
        </div>

        {regexInvalid && (
          <p className="text-xs text-destructive">
            {t(I18N_KEYS.EXPLORER.GROUP_RULE_INVALID_REGEX)}
          </p>
        )}

        <label className="flex items-center gap-2 text-sm text-muted-foreground">
          <input
            type="checkbox"
            checked={caseSensitive}
            onChange={(e) => setCaseSensitive(e.target.checked)}
          />
          {t(I18N_KEYS.EXPLORER.GROUP_RULE_CASE_SENSITIVE)}
        </label>

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
