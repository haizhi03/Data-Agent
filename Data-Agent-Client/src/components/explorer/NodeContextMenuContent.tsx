import { useTranslation } from 'react-i18next';
import { ContextMenuContent, ContextMenuItem } from '../ui/ContextMenu';
import { ExplorerNodeType } from '../../constants/explorer';
import type { ExplorerNode } from '../../types/explorer';
import { I18N_KEYS } from '../../constants/i18nKeys';

interface NodeContextMenuContentProps {
  node: ExplorerNode;
  isConnected: boolean;
  onOpenQueryConsole: (node: ExplorerNode) => void;
  onRunSqlScript: (node: ExplorerNode) => void;
  onSqlGenerator: (node: ExplorerNode) => void;
  onCreateTable: (node: ExplorerNode) => void;
  onOpenErDiagram: (node: ExplorerNode) => void;
  onViewDdl: (node: ExplorerNode) => void;
  onViewData: (node: ExplorerNode, highlightColumn?: string) => void;
  onRename: (node: ExplorerNode) => void;
  onDelete: (node: ExplorerNode, type: ExplorerNodeType) => void;
  onImportData: (node: ExplorerNode) => void;
  onExportData: (node: ExplorerNode) => void;
  onExportTablesWizard: (node: ExplorerNode) => void;
  onImportTablesWizard: (node: ExplorerNode) => void;
}

export function NodeContextMenuContent({
  node,
  isConnected,
  onOpenQueryConsole,
  onOpenErDiagram,
  onViewDdl,
  onViewData,
}: NodeContextMenuContentProps) {
  const { t } = useTranslation();
  const isDdlNode = node.type === ExplorerNodeType.TABLE
    || node.type === ExplorerNodeType.VIEW
    || node.type === ExplorerNodeType.FUNCTION
    || node.type === ExplorerNodeType.PROCEDURE
    || node.type === ExplorerNodeType.TRIGGER
    || node.type === ExplorerNodeType.SEQUENCE
    || node.type === ExplorerNodeType.CONSTRAINT;
  const isTableOrView = node.type === ExplorerNodeType.TABLE || node.type === ExplorerNodeType.VIEW;
  const isTablesFolder = node.type === ExplorerNodeType.FOLDER && node.folderName === 'tables';

  return <ContextMenuContent>
    {isConnected && <ContextMenuItem onSelect={() => onOpenQueryConsole(node)}>{t(I18N_KEYS.EXPLORER.OPEN_QUERY_CONSOLE)}</ContextMenuItem>}
    {isTableOrView && <ContextMenuItem onSelect={() => onViewData(node)}>{t(I18N_KEYS.EXPLORER.VIEW_DATA)}</ContextMenuItem>}
    {isDdlNode && <ContextMenuItem onSelect={() => onViewDdl(node)}>{t(I18N_KEYS.EXPLORER.VIEW_DDL)}</ContextMenuItem>}
    {isTablesFolder && <ContextMenuItem onSelect={() => onOpenErDiagram(node)}>{t(I18N_KEYS.EXPLORER.ER_DIAGRAM)}</ContextMenuItem>}
  </ContextMenuContent>;
}
