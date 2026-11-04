import { useEffect, useMemo, useState } from 'react';
import { Check, Copy, FileCode2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark, oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { useTheme } from '../../hooks/useTheme';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '../ui/Dialog';
import { Button } from '../ui/Button';
import { I18N_KEYS } from '../../constants/i18nKeys';
import type { TableTabMetadata } from '../../types/tab';
import { primaryKeyService } from '../../services/primaryKey.service';
import { useDbTypeStore } from '../../store/dbTypeStore';
import { useWorkspaceStore } from '../../store/workspaceStore';
import { cn } from '../../lib/utils';
import {
  DEFAULT_SQL_GENERATE_OPTIONS,
  generateSql,
  resolveProfile,
  type SqlGenerateOptions,
  type SqlStatementType,
} from '../../utils/sqlGenerator';

const STATEMENT_TYPES: SqlStatementType[] = ['INSERT', 'UPDATE', 'DELETE', 'SELECT'];

interface SqlGeneratorDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  metadata: TableTabMetadata;
  /** Table columns in display order. */
  headers: string[];
  /** Selected row values (typed). */
  rows: unknown[][];
}

export function SqlGeneratorDialog({
  open,
  onOpenChange,
  metadata,
  headers,
  rows,
}: SqlGeneratorDialogProps) {
  const { t } = useTranslation();
  const { theme } = useTheme();
  const supportedDbTypes = useDbTypeStore((s) => s.supportedDbTypes);
  const openTab = useWorkspaceStore((s) => s.openTab);

  const [statementType, setStatementType] = useState<SqlStatementType>('INSERT');
  const [options, setOptions] = useState<SqlGenerateOptions>({ ...DEFAULT_SQL_GENERATE_OPTIONS });
  const [pkColumns, setPkColumns] = useState<string[]>([]);
  const [copied, setCopied] = useState(false);

  const syntaxTheme = theme === 'dark' ? oneDark : oneLight;
  const syntaxBg = theme === 'dark' ? '#282c34' : '#fafafa';

  const dbTypeOption = supportedDbTypes.find((item) => item.code === metadata.dbType);
  const profile = resolveProfile(metadata.dbType, dbTypeOption?.identifierQuoteStyle);

  useEffect(() => {
    if (!open) {
      setCopied(false);
      return;
    }
    setStatementType('INSERT');
    setOptions({ ...DEFAULT_SQL_GENERATE_OPTIONS });
    setCopied(false);

    let cancelled = false;
    primaryKeyService
      .listPrimaryKeys(
        String(metadata.connectionId),
        metadata.objectName,
        metadata.catalog || undefined,
        metadata.schema || undefined,
      )
      .then((keys) => {
        if (cancelled) return;
        setPkColumns(keys?.[0]?.columnNames ?? []);
      })
      .catch(() => {
        if (!cancelled) setPkColumns([]);
      });
    return () => {
      cancelled = true;
    };
  }, [open, metadata]);

  const generated = useMemo(() => {
    if (!open || rows.length === 0) return null;
    return generateSql(
      {
        statementType,
        table: { catalog: metadata.catalog, schema: metadata.schema, name: metadata.objectName },
        columns: headers,
        rows,
        pkColumns,
        options,
      },
      profile,
    );
  }, [open, rows, statementType, headers, pkColumns, options, profile, metadata]);

  const handleCopy = async () => {
    if (!generated) return;
    try {
      await navigator.clipboard.writeText(generated.sql);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('Failed to copy generated SQL:', err);
    }
  };

  const handleOpenInConsole = () => {
    if (!generated) return;
    const tabName =
      [metadata.connectionName, metadata.databaseName, metadata.schemaName].filter(Boolean).join('_') ||
      'console';
    openTab({
      id: `console-${Date.now()}`,
      name: tabName,
      type: 'file',
      content: generated.consoleSql,
      metadata: {
        connectionId: metadata.connectionId,
        connectionName: metadata.connectionName,
        databaseName: metadata.databaseName,
        schemaName: metadata.schemaName,
        dbType: metadata.dbType,
        sqlBatchMode: options.wrapInTransaction ? 'ATOMIC' : undefined,
      },
    });
    onOpenChange(false);
  };

  const setOption = (key: keyof SqlGenerateOptions, value: boolean) => {
    setOptions((prev) => ({ ...prev, [key]: value }));
  };

  const isInsert = statementType === 'INSERT';
  const showWhere = statementType === 'UPDATE' || statementType === 'DELETE';
  const fallbackWhere = showWhere && pkColumns.length === 0;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-3xl max-h-[85vh] flex flex-col">
        <DialogHeader>
          <DialogTitle>{t(I18N_KEYS.EXPLORER.SQL_GENERATOR_TITLE)}</DialogTitle>
          <DialogDescription className="font-mono text-xs">
            {[metadata.catalog, metadata.schema, metadata.objectName].filter(Boolean).join('.')}
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-3 min-h-0">
          {/* Statement type */}
          <div className="flex items-center gap-2">
            <span className="text-[11px] theme-text-secondary whitespace-nowrap">
              {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_STATEMENT)}
            </span>
            <div className="flex items-center gap-1">
              {STATEMENT_TYPES.map((type) => (
                <button
                  key={type}
                  type="button"
                  onClick={() => setStatementType(type)}
                  className={cn(
                    'h-6 px-2 rounded text-[11px] font-mono transition-colors',
                    statementType === type
                      ? 'bg-primary/20 text-primary border border-primary/40'
                      : 'border theme-border theme-text-secondary hover:bg-accent/30',
                  )}
                >
                  {type}
                </button>
              ))}
            </div>
          </div>

          {/* Options */}
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <span className="text-[11px] theme-text-secondary">
              {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_OPTIONS)}
            </span>
            <label className="flex items-center gap-1.5 text-[11px] theme-text-primary">
              <input
                type="checkbox"
                checked={options.qualifyWithSchema}
                onChange={(e) => setOption('qualifyWithSchema', e.target.checked)}
                className="h-3 w-3"
              />
              {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_QUALIFY_SCHEMA)}
            </label>
            {isInsert && (
              <>
                <label className="flex items-center gap-1.5 text-[11px] theme-text-primary">
                  <input
                    type="checkbox"
                    checked={options.omitColumnList}
                    onChange={(e) => setOption('omitColumnList', e.target.checked)}
                    className="h-3 w-3"
                  />
                  {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_OMIT_COLUMNS)}
                </label>
                <label className="flex items-center gap-1.5 text-[11px] theme-text-primary">
                  <input
                    type="checkbox"
                    checked={options.batchMultiValues}
                    onChange={(e) => setOption('batchMultiValues', e.target.checked)}
                    className="h-3 w-3"
                  />
                  {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_BATCH_VALUES)}
                </label>
              </>
            )}
            <label className="flex items-center gap-1.5 text-[11px] theme-text-primary">
              <input
                type="checkbox"
                checked={options.wrapInTransaction}
                onChange={(e) => setOption('wrapInTransaction', e.target.checked)}
                className="h-3 w-3"
              />
              {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_TRANSACTION)}
            </label>
          </div>

          {showWhere && generated && (
            <div className="text-[11px] theme-text-secondary">
              {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_WHERE_COLUMNS)}: {generated.whereColumns.join(', ')}
              {fallbackWhere && ` ${t(I18N_KEYS.EXPLORER.SQL_GENERATOR_FALLBACK_WHERE)}`}
            </div>
          )}

          {/* Preview */}
          <div className="flex-1 min-h-[240px] flex flex-col gap-2">
            {rows.length === 0 ? (
              <div className="flex items-center justify-center py-12 text-[12px] theme-text-secondary">
                {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_NO_SELECTION)}
              </div>
            ) : (
              generated && (
                <>
                  <div className="flex items-center justify-between">
                    <span className="text-xs theme-text-secondary">
                      {generated.statementCount} {generated.statementCount === 1 ? 'statement' : 'statements'}
                    </span>
                    <div className="flex items-center gap-2">
                      <Button variant="outline" size="sm" onClick={handleOpenInConsole} className="h-7">
                        <FileCode2 className="w-3 h-3 mr-1" />
                        {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_OPEN_CONSOLE)}
                      </Button>
                      <Button variant="outline" size="sm" onClick={handleCopy} className="h-7">
                        {copied ? (
                          <>
                            <Check className="w-3 h-3 mr-1" />
                            {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_COPIED)}
                          </>
                        ) : (
                          <>
                            <Copy className="w-3 h-3 mr-1" />
                            {t(I18N_KEYS.EXPLORER.SQL_GENERATOR_COPY)}
                          </>
                        )}
                      </Button>
                    </div>
                  </div>

                  <div className="flex-1 overflow-auto border theme-border rounded-md">
                    <SyntaxHighlighter
                      language="sql"
                      style={syntaxTheme}
                      showLineNumbers={false}
                      customStyle={{
                        margin: 0,
                        padding: '1rem',
                        fontSize: '11px',
                        lineHeight: 1.5,
                        background: syntaxBg,
                        minHeight: '100%',
                      }}
                      codeTagProps={{ style: { fontFamily: 'inherit' } }}
                      PreTag="div"
                    >
                      {generated.sql}
                    </SyntaxHighlighter>
                  </div>
                </>
              )
            )}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
