import { useEffect } from 'react';
import { Handle, Position, useNodeId, useUpdateNodeInternals, type Node, type NodeProps } from '@xyflow/react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronUp, KeyRound } from 'lucide-react';
import { I18N_KEYS } from '../../constants/i18nKeys';
import type { ErTable } from '../../services/table.service';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '../ui/Tooltip';
import { columnHandleId, overflowHandleId } from './erDiagramEdges';
import { useErDiagramInteraction } from './erDiagramInteraction';
import { CARD_WIDTH, cardHeight, MAX_VISIBLE_ROWS, visibleColumns } from './erDiagramLayout';
import { columnKey, tableId } from './erDiagramRelations';

export type ErTableNodeData = {
  table: ErTable;
  expanded: boolean;
};

export type ErTableNodeType = Node<ErTableNodeData, 'erTable'>;

function ColumnComment({ comment }: { comment: string }) {
  const className = 'min-w-0 flex-1 truncate theme-text-secondary';
  if (!comment.trim()) {
    return <span className={className} />;
  }
  return (
    <Tooltip disableHoverableContent>
      <TooltipTrigger asChild>
        <span className={className}>{comment}</span>
      </TooltipTrigger>
      <TooltipContent
        side="top"
        className="max-w-64 whitespace-pre-wrap break-words px-2 py-1 text-left text-xs font-normal leading-snug"
      >
        {comment}
      </TooltipContent>
    </Tooltip>
  );
}

function tableTitle(table: ErTable) {
  if (!table.external) return table.name;
  const scope = table.schema || table.catalog || '';
  return scope ? `${scope}.${table.name}` : table.name;
}

function SideHandles({ role, side, column }: { role: 'source' | 'target'; side: 'left' | 'right'; column: string }) {
  return (
    <Handle
      type={role}
      position={side === 'left' ? Position.Left : Position.Right}
      id={columnHandleId(role, side, column)}
      isConnectable={false}
      className="!border-0 !bg-transparent !opacity-0"
    />
  );
}

export function ErTableNode({ data }: NodeProps<ErTableNodeType>) {
  const { t } = useTranslation();
  const interaction = useErDiagramInteraction();
  const nodeId = useNodeId();
  const updateInternals = useUpdateNodeInternals();
  const table = data.table;
  const id = tableId(table);
  const title = tableTitle(table);
  const expanded = !!data.expanded;
  const columns = visibleColumns(table.columns, interaction.linked, interaction.highlighted, id, expanded);
  const hiddenCount = table.columns.length - columns.length;
  const height = cardHeight(table.columns.length, expanded);
  const shownColumnKey = columns.map((column) => column.name).join('\n');

  useEffect(() => {
    if (nodeId) updateInternals(nodeId);
  }, [expanded, nodeId, shownColumnKey, updateInternals]);

  return (
    <div
      className={`flex h-full w-full flex-col overflow-hidden rounded-md border theme-bg-popup shadow-sm ${table.external ? 'border-dashed border-rose-400/80' : 'theme-border'}`}
      style={{ width: CARD_WIDTH, height }}
      onDoubleClick={(event) => {
        if (!table.external) return;
        event.stopPropagation();
        interaction.openExternal(table);
      }}
    >
      <div
        className="er-drag-handle flex h-[34px] shrink-0 select-none items-center gap-1 border-b theme-border px-2 text-xs font-semibold theme-text-primary"
        title={table.external
          ? `${t(I18N_KEYS.EXPLORER.ER_DIAGRAM_OPEN_EXTERNAL)}。${t(I18N_KEYS.EXPLORER.ER_DIAGRAM_DRAG_TABLE)}`
          : t(I18N_KEYS.EXPLORER.ER_DIAGRAM_DRAG_TABLE)}
      >
        <span className="min-w-0 flex-1 truncate" title={table.comment || title}>{title}</span>
        {table.external && (
          <span className="nodrag nopan shrink-0 rounded px-1 text-[10px] font-normal theme-text-secondary">
            {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXTERNAL)}
          </span>
        )}
        {interaction.junctionTables.has(id) && (
          <span className="nodrag nopan shrink-0 rounded bg-violet-500/15 px-1 text-[10px] font-normal text-violet-600">
            {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_JUNCTION)}
          </span>
        )}
        <button
          type="button"
          data-er-export-hidden
          className="nodrag nopan shrink-0 rounded border theme-border px-1 text-[10px] font-normal theme-text-primary"
          title={t(I18N_KEYS.EXPLORER.VIEW_DATA)}
          onPointerDown={(event) => event.stopPropagation()}
          onDoubleClick={(event) => event.stopPropagation()}
          onClick={(event) => {
            event.stopPropagation();
            interaction.openData(table);
          }}
        >
          {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_OPEN_DATA)}
        </button>
        <button
          type="button"
          data-er-export-hidden
          className="nodrag nopan shrink-0 rounded border theme-border px-1 text-[10px] font-normal theme-text-primary"
          title={t(I18N_KEYS.EXPLORER.VIEW_DDL)}
          onPointerDown={(event) => event.stopPropagation()}
          onDoubleClick={(event) => event.stopPropagation()}
          onClick={(event) => {
            event.stopPropagation();
            void interaction.openDdl(table);
          }}
        >
          {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_OPEN_DDL)}
        </button>
      </div>
      <div className="nodrag nopan">
        <TooltipProvider delayDuration={300}>
          {columns.map((column) => {
            const key = columnKey(id, column.name);
            const linked = interaction.linked.has(key);
            const highlighted = interaction.highlighted.has(key);
            return (
              <div
                key={column.name}
                data-er-highlighted={highlighted ? 'true' : 'false'}
                className={`relative flex h-[22px] items-center gap-1 px-2 text-[11px] ${highlighted ? 'bg-rose-500/15' : ''} ${linked ? 'cursor-pointer' : ''}`}
                onPointerDown={(event) => {
                  if (!linked) return;
                  event.stopPropagation();
                }}
                onClick={(event) => {
                  if (!linked) return;
                  event.stopPropagation();
                  interaction.togglePin({ tableId: id, column: column.name });
                }}
                onPointerEnter={() => {
                  if (!linked) return;
                  interaction.setHover({ tableId: id, column: column.name });
                }}
                onPointerLeave={() => {
                  interaction.clearHover({ tableId: id, column: column.name });
                }}
              >
                <SideHandles role="target" side="left" column={column.name} />
                <SideHandles role="source" side="left" column={column.name} />
                <SideHandles role="target" side="right" column={column.name} />
                <SideHandles role="source" side="right" column={column.name} />
                <span className="w-3 shrink-0 text-amber-500">
                  {column.primaryKey ? <KeyRound className="h-3 w-3" /> : null}
                </span>
                <span className="flex w-2 shrink-0 justify-center">
                  {linked ? <span className="h-1.5 w-1.5 rounded-full bg-rose-500" /> : null}
                </span>
                <span className="w-[38%] truncate theme-text-primary" title={column.name}>{column.name}</span>
                <span className="w-[28%] truncate text-sky-500" title={column.typeName}>{column.typeName}</span>
                <ColumnComment comment={column.comment} />
              </div>
            );
          })}
        </TooltipProvider>
      </div>
      {table.columns.length > MAX_VISIBLE_ROWS && (
        <button
          type="button"
          className="nodrag nopan relative flex h-[22px] w-full shrink-0 items-center justify-center gap-1 border-t theme-border text-[10px] theme-text-secondary"
          disabled={interaction.busy}
          onPointerDown={(event) => event.stopPropagation()}
          onDoubleClick={(event) => event.stopPropagation()}
          onClick={(event) => {
            event.stopPropagation();
            if (!nodeId) return;
            interaction.toggleExpanded(nodeId);
          }}
        >
          <Handle type="target" position={Position.Left} id={overflowHandleId('target', 'left')} isConnectable={false} className="!border-0 !bg-transparent !opacity-0" />
          <Handle type="source" position={Position.Left} id={overflowHandleId('source', 'left')} isConnectable={false} className="!border-0 !bg-transparent !opacity-0" />
          <Handle type="target" position={Position.Right} id={overflowHandleId('target', 'right')} isConnectable={false} className="!border-0 !bg-transparent !opacity-0" />
          <Handle type="source" position={Position.Right} id={overflowHandleId('source', 'right')} isConnectable={false} className="!border-0 !bg-transparent !opacity-0" />
          {expanded ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
          {expanded
            ? t(I18N_KEYS.EXPLORER.ER_DIAGRAM_COLLAPSE_COLUMNS)
            : t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPAND_COLUMNS, { count: hiddenCount })}
        </button>
      )}
    </div>
  );
}
