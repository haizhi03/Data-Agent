import { Download, FileText, Pencil, Plus, Table, Table2, Trash2, Upload } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import {
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuSub,
  ContextMenuSubContent,
  ContextMenuSubTrigger,
} from '../ui/ContextMenu';
import { ExplorerNodeType } from '../../constants/explorer';
import type { ExplorerNode } from '../../types/explorer';
import { I18N_KEYS } from '../../constants/i18nKeys';

interface NodeContextMenuContentProps {
  node: ExplorerNode;
  isConnected: boolean;
  onOpenQueryConsole: (node: ExplorerNode) => void;
  onCreateTable: (node: ExplorerNode) => void;
  onViewDdl: (node: ExplorerNode) => void;
  onViewData: (node: ExplorerNode, highlightColumn?: string) => void;
  onRename: (node: ExplorerNode) => void;
  onDelete: (node: ExplorerNode, type: ExplorerNodeType) => void;
  onImportData: (node: ExplorerNode) => void;
  onExportData: (node: ExplorerNode) => void;
}

export function NodeContextMenuContent({
  node,
  isConnected,
  onOpenQueryConsole,
  onCreateTable,
  onViewDdl,
  onViewData,
  onRename,
  onDelete,
  onImportData,
  onExportData,
}: NodeContextMenuContentProps) {
  const { t } = useTranslation();

  // Determine node characteristics
  const isDdlNode =
    node.type === ExplorerNodeType.TABLE ||
    node.type === ExplorerNodeType.VIEW ||
    node.type === ExplorerNodeType.FUNCTION ||
    node.type === ExplorerNodeType.PROCEDURE ||
    node.type === ExplorerNodeType.TRIGGER;

  const isTableOrView = node.type === ExplorerNodeType.TABLE || node.type === ExplorerNodeType.VIEW;

  const isColumnOrIndexOrKey =
    node.type === ExplorerNodeType.COLUMN ||
    node.type === ExplorerNodeType.INDEX ||
    node.type === ExplorerNodeType.KEY;

  const isDb = node.type === ExplorerNodeType.DB;

  const isDeletableFolder =
    node.type === ExplorerNodeType.FOLDER &&
    node.folderName &&
    ['tables', 'views', 'routines', 'triggers'].includes(node.folderName);

  const isTablesFolder =
    node.type === ExplorerNodeType.FOLDER && node.folderName === 'tables';

  const canCreateTable = isConnected && (isDb || isTablesFolder);

  // Extract column name from key/index name (format: "name (col1, col2)" or just "name")
  const extractColumnName = (nodeName: string): string | undefined => {
    const match = nodeName.match(/\(([^)]+)\)/);
    if (match) {
      return match[1].split(',')[0].trim();
    }
    return undefined;
  };

  // Track if any data operation item was shown for separator logic
  let hasDataOperations = false;
  let hasDeleteOperation = false;

  return (
    <ContextMenuContent>
      {/* 1. 新建 (Create New) submenu - available for all connected nodes */}
      {isConnected && node.type !== ExplorerNodeType.EMPTY && (
        <>
          <ContextMenuSub>
            <ContextMenuSubTrigger>
              <Plus className="w-3.5 h-3.5 mr-2" />
              {t(I18N_KEYS.EXPLORER.CREATE_NEW)}
            </ContextMenuSubTrigger>
            <ContextMenuSubContent>
              <ContextMenuItem onSelect={() => onOpenQueryConsole(node)}>
                <FileText className="w-3.5 h-3.5 mr-2" />
                {t(I18N_KEYS.EXPLORER.OPEN_QUERY_CONSOLE)}
              </ContextMenuItem>
              {canCreateTable && (
                <ContextMenuItem onSelect={() => onCreateTable(node)}>
                  <Table2 className="w-3.5 h-3.5 mr-2" />
                  {t(I18N_KEYS.EXPLORER.CREATE_TABLE)}
                </ContextMenuItem>
              )}
            </ContextMenuSubContent>
          </ContextMenuSub>
          <ContextMenuSeparator />
        </>
      )}

      {/* 2. View DDL - for DDL objects */}
      {isDdlNode && (
        <>
          <ContextMenuItem onSelect={() => onViewDdl(node)}>
            <FileText className="w-3.5 h-3.5 mr-2" />
            {t(I18N_KEYS.EXPLORER.VIEW_DDL)}
          </ContextMenuItem>
          {hasDataOperations && <ContextMenuSeparator />}
        </>
      )}

      {/* 3. View Data - for tables/views and their children */}
      {(isTableOrView || isColumnOrIndexOrKey) && (
        <>
          <ContextMenuItem
            onSelect={() => {
              const highlightCol =
                node.type === ExplorerNodeType.COLUMN
                  ? node.name
                  : extractColumnName(node.name);
              onViewData(node, highlightCol);
            }}
          >
            <Table className="w-3.5 h-3.5 mr-2" />
            {t(I18N_KEYS.EXPLORER.VIEW_DATA)}
          </ContextMenuItem>
          {hasDeleteOperation && <ContextMenuSeparator />}
        </>
      )}
      {isTableOrView || isColumnOrIndexOrKey ? (hasDataOperations = true) : null}

      {/* 3.5 Import/Export Data - for tables (UI placeholder, functionality to be implemented later) */}
      {node.type === ExplorerNodeType.TABLE && (
        <>
          <ContextMenuItem onSelect={() => onImportData(node)}>
            <Upload className="w-3.5 h-3.5 mr-2" />
            {t(I18N_KEYS.EXPLORER.IMPORT_DATA)}
          </ContextMenuItem>
          <ContextMenuItem onSelect={() => onExportData(node)}>
            <Download className="w-3.5 h-3.5 mr-2" />
            {t(I18N_KEYS.EXPLORER.EXPORT_DATA)}
          </ContextMenuItem>
        </>
      )}

      {/* 4. Rename - for tables */}
      {node.type === ExplorerNodeType.TABLE && (
        <>
          <ContextMenuItem onSelect={() => onRename(node)}>
            <Pencil className="w-3.5 h-3.5 mr-2" />
            {t(I18N_KEYS.EXPLORER.RENAME_TABLE)}
          </ContextMenuItem>
          <ContextMenuSeparator />
        </>
      )}

      {/* 5. Delete Operations */}
      {node.type === ExplorerNodeType.TABLE && (
        <ContextMenuItem
          onSelect={() => onDelete(node, ExplorerNodeType.TABLE)}
          className="text-destructive focus:text-destructive"
        >
          <Trash2 className="w-3.5 h-3.5 mr-2" />
          {t(I18N_KEYS.EXPLORER.DELETE_TABLE)}
        </ContextMenuItem>
      )}

      {node.type === ExplorerNodeType.VIEW && (
        <ContextMenuItem
          onSelect={() => onDelete(node, ExplorerNodeType.VIEW)}
          className="text-destructive focus:text-destructive"
        >
          <Trash2 className="w-3.5 h-3.5 mr-2" />
          {t(I18N_KEYS.EXPLORER.DELETE_VIEW)}
        </ContextMenuItem>
      )}

      {node.type === ExplorerNodeType.FUNCTION && (
        <ContextMenuItem
          onSelect={() => onDelete(node, ExplorerNodeType.FUNCTION)}
          className="text-destructive focus:text-destructive"
        >
          <Trash2 className="w-3.5 h-3.5 mr-2" />
          {t(I18N_KEYS.EXPLORER.DELETE_FUNCTION)}
        </ContextMenuItem>
      )}

      {node.type === ExplorerNodeType.PROCEDURE && (
        <ContextMenuItem
          onSelect={() => onDelete(node, ExplorerNodeType.PROCEDURE)}
          className="text-destructive focus:text-destructive"
        >
          <Trash2 className="w-3.5 h-3.5 mr-2" />
          {t(I18N_KEYS.EXPLORER.DELETE_PROCEDURE)}
        </ContextMenuItem>
      )}

      {node.type === ExplorerNodeType.TRIGGER && (
        <ContextMenuItem
          onSelect={() => onDelete(node, ExplorerNodeType.TRIGGER)}
          className="text-destructive focus:text-destructive"
        >
          <Trash2 className="w-3.5 h-3.5 mr-2" />
          {t(I18N_KEYS.EXPLORER.DELETE_TRIGGER)}
        </ContextMenuItem>
      )}

      {isDb && (
        <ContextMenuItem
          onSelect={() => onDelete(node, ExplorerNodeType.DB)}
          className="text-destructive focus:text-destructive"
        >
          <Trash2 className="w-3.5 h-3.5 mr-2" />
          {t(I18N_KEYS.EXPLORER.DELETE_DATABASE)}
        </ContextMenuItem>
      )}

      {isDeletableFolder && node.children && node.children.filter((c) => c.type !== 'empty').length > 0 && (
        <ContextMenuItem
          onSelect={() => onDelete(node, ExplorerNodeType.FOLDER)}
          className="text-destructive focus:text-destructive"
        >
          <Trash2 className="w-3.5 h-3.5 mr-2" />
          {t(I18N_KEYS.EXPLORER.DELETE_ALL_IN_FOLDER)}
        </ContextMenuItem>
      )}
    </ContextMenuContent>
  );
}
