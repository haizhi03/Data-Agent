import { useState, useRef, useEffect, useCallback, useMemo } from 'react';
import type { WheelEvent as ReactWheelEvent, MouseEvent as ReactMouseEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { ExplorerNodeType } from '../../constants/explorer';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { tableService } from '../../services/table.service';
import { useToast } from '../../hooks/useToast';
import type { ExplorerNode } from '../../types/explorer';
import type { DbConnection } from '../../types/connection';
import { resolveErrorMessage } from '../../lib/errorMessage';

const MIN_WIDTH = 520;
const MIN_HEIGHT = 400;
const DEFAULT_WIDTH = 800;
const DEFAULT_HEIGHT = 980;
/** Keep the dialog clear of the viewport edges when fitting / clamping. */
const VIEWPORT_MARGIN = 16;

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), Math.max(min, max));
}

/** Dialog size that still fits the current browser window. */
function fitToViewport(width: number, height: number) {
  return {
    width: Math.max(MIN_WIDTH, Math.min(width, window.innerWidth - VIEWPORT_MARGIN * 2)),
    height: Math.max(MIN_HEIGHT, Math.min(height, window.innerHeight - VIEWPORT_MARGIN * 2)),
  };
}

const COMMON_TYPES = [
  'INT',
  'BIGINT',
  'VARCHAR(255)',
  'TEXT',
  'DATE',
  'DATETIME',
  'TIMESTAMP',
  'DECIMAL(10,2)',
  'BOOLEAN',
  'FLOAT',
  'DOUBLE',
] as const;

export type TreeSectionId = 'columns' | 'keys' | 'foreign_keys' | 'indexes' | 'checks' | 'virtual_columns' | 'virtual_fk';

export interface CreateTableColumn {
  name: string;
  type: string;
  nullable: boolean;
}

export interface CreateTableForeignKey {
  column: string;
  refTable: string;
  refColumn: string;
}

export interface CreateTableIndex {
  name: string;
  columns: string;
}

interface UseCreateTableDialogStateArgs {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  node: ExplorerNode | null;
  connections?: DbConnection[];
  onSuccess?: (node: ExplorerNode) => void;
}

function quoteIdentifier(name: string, dbType?: string): string {
  if (!name.trim()) return name;
  const isMysql = dbType?.toLowerCase().includes('mysql');
  return isMysql ? `\`${name}\`` : `"${name.replace(/"/g, '""')}"`;
}

function validateTableName(name: string): boolean {
  return /^[a-zA-Z_][a-zA-Z0-9_]*$/.test(name.trim());
}

function validateColumnName(name: string): boolean {
  return name.trim().length > 0 && /^[a-zA-Z_][a-zA-Z0-9_]*$/.test(name.trim());
}

export function useCreateTableDialogState({
  open,
  onOpenChange,
  node,
  connections = [],
  onSuccess,
}: UseCreateTableDialogStateArgs) {
  const { t } = useTranslation();
  const toast = useToast();
  const [tableName, setTableName] = useState('');
  const [tableComment, setTableComment] = useState('');
  const [selectedSection, setSelectedSection] = useState<TreeSectionId>('columns');
  const [checkedSections, setCheckedSections] = useState<Set<TreeSectionId>>(
    () => new Set(['columns', 'keys']),
  );
  const [columns, setColumns] = useState<CreateTableColumn[]>([
    { name: 'id', type: 'BIGINT', nullable: false },
  ]);
  const [foreignKeys, setForeignKeys] = useState<CreateTableForeignKey[]>([]);
  const [indexes, setIndexes] = useState<CreateTableIndex[]>([]);
  const [previewExpanded, setPreviewExpanded] = useState(true);
  const [isPending, setIsPending] = useState(false);
  const [leftRootExpanded, setLeftRootExpanded] = useState(true);
  const formScrollRef = useRef<HTMLDivElement>(null);

  const [position, setPosition] = useState({ x: 0, y: 0 });
  const [isDragging, setIsDragging] = useState(false);
  const dragRef = useRef<{ startX: number; startY: number; startLeft: number; startTop: number } | null>(null);
  const [size, setSize] = useState({ width: DEFAULT_WIDTH, height: DEFAULT_HEIGHT });
  const sizeRef = useRef(size);
  const resizeRef = useRef<{ startX: number; startY: number; startW: number; startH: number } | null>(null);

  useEffect(() => {
    sizeRef.current = size;
  }, [size]);

  const connectionId = node?.connectionId ? String(node.connectionId) : undefined;
  const catalog = node?.catalog ?? (node?.type === ExplorerNodeType.DB ? node.name : '');
  const schema = node?.schema ?? undefined;
  const conn = connections.find((c) => String(c.id) === connectionId) ?? node?.dbConnection;
  const dbType = conn?.dbType;
  const connectionName = conn?.name ?? 'localhost';
  const displayTableName = tableName.trim() || 'table_name';
  const rootLabel = `${displayTableName} ${[catalog, schema].filter(Boolean).join('.') || ''} [${connectionName}]`.trim();

  const handleFormWheel = (e: ReactWheelEvent) => {
    const el = formScrollRef.current;
    if (!el || el.scrollHeight <= el.clientHeight) return;
    const { deltaY } = e;
    if (deltaY !== 0) {
      el.scrollTop += deltaY;
      e.preventDefault();
    }
  };

  const initPosition = useCallback(() => {
    // Fit the default size to the window so the footer buttons stay reachable
    // on smaller screens, then center the dialog.
    const { width, height } = fitToViewport(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    setSize({ width, height });
    setPosition({
      x: clamp((window.innerWidth - width) / 2, VIEWPORT_MARGIN, window.innerWidth - width),
      y: clamp((window.innerHeight - height) / 2, VIEWPORT_MARGIN, window.innerHeight - height),
    });
  }, []);

  useEffect(() => {
    if (open) initPosition();
  }, [open, initPosition]);

  // Shrink / reposition back into view when the window is resized while open.
  useEffect(() => {
    if (!open) return;
    const handleWindowResize = () => {
      const { width, height } = fitToViewport(sizeRef.current.width, sizeRef.current.height);
      setSize({ width, height });
      setPosition((prev) => ({
        x: clamp(prev.x, 0, window.innerWidth - width),
        y: clamp(prev.y, 0, window.innerHeight - height),
      }));
    };
    window.addEventListener('resize', handleWindowResize);
    return () => window.removeEventListener('resize', handleWindowResize);
  }, [open]);

  const handleHeaderMouseDown = (e: ReactMouseEvent) => {
    if ((e.target as HTMLElement).closest('button, a, [role="button"]')) return;
    setIsDragging(true);
    dragRef.current = {
      startX: e.clientX,
      startY: e.clientY,
      startLeft: position.x,
      startTop: position.y,
    };
  };

  useEffect(() => {
    if (!isDragging) return;
    const onMove = (e: MouseEvent) => {
      if (!dragRef.current) return;
      const dx = e.clientX - dragRef.current.startX;
      const dy = e.clientY - dragRef.current.startY;
      // Keep the dialog fully inside the window.
      setPosition({
        x: clamp(dragRef.current.startLeft + dx, 0, window.innerWidth - sizeRef.current.width),
        y: clamp(dragRef.current.startTop + dy, 0, window.innerHeight - sizeRef.current.height),
      });
    };
    const onUp = () => {
      setIsDragging(false);
      dragRef.current = null;
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
    return () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
    };
  }, [isDragging]);

  const handleResizeMouseDown = (e: ReactMouseEvent) => {
    e.preventDefault();
    resizeRef.current = {
      startX: e.clientX,
      startY: e.clientY,
      startW: size.width,
      startH: size.height,
    };
    // Clamp manual resizing to the space left between the dialog and the window edge.
    const maxWidth = Math.max(MIN_WIDTH, window.innerWidth - position.x);
    const maxHeight = Math.max(MIN_HEIGHT, window.innerHeight - position.y);
    const onMove = (event: MouseEvent) => {
      if (!resizeRef.current) return;
      const dw = event.clientX - resizeRef.current.startX;
      const dh = event.clientY - resizeRef.current.startY;
      setSize({
        width: clamp(resizeRef.current.startW + dw, MIN_WIDTH, maxWidth),
        height: clamp(resizeRef.current.startH + dh, MIN_HEIGHT, maxHeight),
      });
    };
    const onUp = () => {
      resizeRef.current = null;
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  };

  const resetForm = () => {
    setTableName('');
    setTableComment('');
    setSelectedSection('columns');
    setCheckedSections(new Set(['columns', 'keys']));
    setColumns([{ name: 'id', type: 'BIGINT', nullable: false }]);
    setForeignKeys([]);
    setIndexes([]);
    setPreviewExpanded(true);
    setLeftRootExpanded(true);
  };

  const handleClose = (openState: boolean) => {
    if (!openState) resetForm();
    onOpenChange(openState);
  };

  const addColumn = () => {
    setColumns((prev) => [...prev, { name: '', type: 'VARCHAR(255)', nullable: true }]);
  };

  const removeColumn = (index: number) => {
    setColumns((prev) => prev.filter((_, i) => i !== index));
  };

  const updateColumn = (index: number, field: keyof CreateTableColumn, value: string | boolean) => {
    setColumns((prev) => prev.map((col, i) => (i === index ? { ...col, [field]: value } : col)));
  };

  const addForeignKey = () => {
    setForeignKeys((prev) => [...prev, { column: '', refTable: '', refColumn: '' }]);
  };

  const removeForeignKey = (index: number) => {
    setForeignKeys((prev) => prev.filter((_, i) => i !== index));
  };

  const updateForeignKey = (index: number, field: keyof CreateTableForeignKey, value: string) => {
    setForeignKeys((prev) => prev.map((fk, i) => (i === index ? { ...fk, [field]: value } : fk)));
  };

  const addIndex = () => {
    setIndexes((prev) => [...prev, { name: '', columns: '' }]);
  };

  const removeIndex = (index: number) => {
    setIndexes((prev) => prev.filter((_, i) => i !== index));
  };

  const updateIndex = (index: number, field: keyof CreateTableIndex, value: string) => {
    setIndexes((prev) => prev.map((idx, i) => (i === index ? { ...idx, [field]: value } : idx)));
  };

  const moveColumn = (index: number, dir: 'up' | 'down') => {
    const newIdx = dir === 'up' ? index - 1 : index + 1;
    if (newIdx < 0 || newIdx >= columns.length) return;
    setColumns((prev) => {
      const arr = [...prev];
      [arr[index], arr[newIdx]] = [arr[newIdx], arr[index]];
      return arr;
    });
  };

  const previewSql = useMemo(() => {
    const q = (s: string) => quoteIdentifier(s, dbType);
    const validCols = columns.filter((c) => c.name.trim());
    const tblName = tableName.trim() || 'table_name';
    if (validCols.length === 0) return `create table ${q(displayTableName)}\n(\n);`;
    const hasKeys = checkedSections.has('keys');
    const hasFk = checkedSections.has('foreign_keys');
    const hasIdx = checkedSections.has('indexes');
    const colDefs = validCols.map((c, idx) => {
      const nullPart = c.nullable ? '' : ' NOT NULL';
      const pkPart = hasKeys && idx === 0 && validCols[0].name.toLowerCase() === 'id' ? ' PRIMARY KEY' : '';
      return `  ${q(c.name.trim())} ${c.type}${nullPart}${pkPart}`;
    });
    const parts = [...colDefs];
    if (hasFk && foreignKeys.length > 0) {
      foreignKeys
        .filter((fk) => fk.column.trim() && fk.refTable.trim() && fk.refColumn.trim())
        .forEach((fk, i) => {
          parts.push(`  CONSTRAINT fk_${tblName}_${i + 1} FOREIGN KEY (${q(fk.column.trim())}) REFERENCES ${q(fk.refTable.trim())} (${q(fk.refColumn.trim())})`);
        });
    }
    if (hasIdx && indexes.length > 0) {
      indexes
        .filter((idx) => idx.name.trim() && idx.columns.trim())
        .forEach((idx) => {
          const cols = idx.columns.trim().split(/\s*,\s*/).map((c) => q(c.trim())).join(', ');
          parts.push(`  KEY ${q(idx.name.trim())} (${cols})`);
        });
    }
    return `create table ${q(tblName)}\n(\n${parts.join(',\n')}\n)`;
  }, [
    tableName,
    columns,
    foreignKeys,
    indexes,
    dbType,
    displayTableName,
    [...checkedSections].sort().join(','),
  ]);

  const handleSubmit = async () => {
    if (!connectionId || !tableName.trim()) return;
    if (!validateTableName(tableName)) {
      toast.error(`${t(I18N_KEYS.EXPLORER.CREATE_TABLE_FAILED)}: ${t(I18N_KEYS.EXPLORER.CREATE_TABLE_INVALID_NAME)}`);
      return;
    }
    const validCols = columns.filter((c) => c.name.trim());
    if (validCols.length === 0) {
      toast.error(`${t(I18N_KEYS.EXPLORER.CREATE_TABLE_FAILED)}: ${t('explorer.create_table_need_columns')}`);
      return;
    }
    const invalidCol = validCols.find((c) => !validateColumnName(c.name));
    if (invalidCol) {
      toast.error(`${t(I18N_KEYS.EXPLORER.CREATE_TABLE_FAILED)}: ${t(I18N_KEYS.EXPLORER.CREATE_TABLE_INVALID_COLUMN, { name: invalidCol.name })}`);
      return;
    }

    const createSql = previewSql.replace(/^create table/i, 'CREATE TABLE');

    setIsPending(true);
    try {
      const response = await tableService.createTable({
        connectionId: Number(connectionId),
        databaseName: catalog || undefined,
        schemaName: schema ?? undefined,
        sql: createSql,
      });

      if (response.type === 'ERROR' || !response.success) {
        const errMsg = response.messages?.[0]?.message ?? response.errorMessage ?? 'Unknown error';
        toast.error(`${t(I18N_KEYS.EXPLORER.CREATE_TABLE_FAILED)}: ${errMsg}`);
        return;
      }

      toast.success(t(I18N_KEYS.EXPLORER.CREATE_TABLE_SUCCESS));
      handleClose(false);
      if (node) {
        onSuccess?.(node);
      }
    } catch (err) {
      toast.error(`${t(I18N_KEYS.EXPLORER.CREATE_TABLE_FAILED)}: ${resolveErrorMessage(err, t(I18N_KEYS.EXPLORER.CREATE_TABLE_FAILED))}`);
    } finally {
      setIsPending(false);
    }
  };

  return {
    MIN_WIDTH,
    MIN_HEIGHT,
    DEFAULT_WIDTH,
    DEFAULT_HEIGHT,
    COMMON_TYPES,
    formScrollRef,
    handleFormWheel,
    tableName,
    setTableName,
    tableComment,
    setTableComment,
    selectedSection,
    setSelectedSection,
    checkedSections,
    setCheckedSections,
    columns,
    foreignKeys,
    indexes,
    previewExpanded,
    setPreviewExpanded,
    isPending,
    leftRootExpanded,
    setLeftRootExpanded,
    position,
    size,
    handleHeaderMouseDown,
    handleResizeMouseDown,
    connectionId,
    catalog,
    schema,
    connectionName,
    displayTableName,
    rootLabel,
    previewSql,
    handleClose,
    addColumn,
    removeColumn,
    updateColumn,
    addForeignKey,
    removeForeignKey,
    updateForeignKey,
    addIndex,
    removeIndex,
    updateIndex,
    moveColumn,
    handleSubmit,
  };
}
