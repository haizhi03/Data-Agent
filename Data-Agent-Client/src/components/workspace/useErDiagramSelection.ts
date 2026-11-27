import { useCallback, useMemo, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import { useReactFlow } from '@xyflow/react';
import type { ErRelation } from '../../services/table.service';
import type { ErTableNodeType } from './ErDiagramNode';
import {
  focusInvolvedTableIds,
  relationFocusKey,
  splitFocusRelations,
} from './erDiagramFocus';
import { tableId } from './erDiagramRelations';

export interface PinnedErLinks {
  relations: ErRelation[];
  selectedKeys: string[];
}

export function useErDiagramSelection(
  nodes: ErTableNodeType[],
  setNodes: Dispatch<SetStateAction<ErTableNodeType[]>>,
) {
  const { getNodes } = useReactFlow();
  const [selectedNodeIds, setSelectedNodeIds] = useState<string[]>([]);
  const [onlyTableIds, setOnlyTableIds] = useState<string[] | null>(null);
  const [pinnedLinks, setPinnedLinks] = useState<PinnedErLinks | null>(null);
  const [showAllLines, setShowAllLines] = useState(false);
  const selectedNodeIdsRef = useRef(selectedNodeIds);
  selectedNodeIdsRef.current = selectedNodeIds;
  const onlyTableKey = onlyTableIds?.join('\n') ?? '';

  const modifySelection = useCallback((nodeId: string) => {
    const selected = new Set(getNodes().filter((node) => node.selected).map((node) => node.id));
    if (selected.has(nodeId)) selected.delete(nodeId);
    else selected.add(nodeId);
    const ids = [...selected];
    setSelectedNodeIds(ids);
    setNodes((current) => current.map((node) => ({ ...node, selected: selected.has(node.id) })));
  }, [getNodes, setNodes]);

  const selectAllTables = useCallback(() => {
    const ids = getNodes().map((node) => node.id);
    setSelectedNodeIds(ids);
    setNodes((current) => current.map((node) => ({ ...node, selected: true })));
  }, [getNodes, setNodes]);

  const hidePinnedRelation = useCallback((key: string) => {
    setPinnedLinks((current) => {
      if (!current) return current;
      return {
        ...current,
        relations: current.relations.filter((relation) => relationFocusKey(relation) !== key),
      };
    });
  }, []);

  const clearPinnedLinks = useCallback(() => {
    setPinnedLinks(null);
  }, []);

  const showOnlySelected = useCallback(() => {
    const ids = nodes
      .filter((node) => selectedNodeIds.includes(node.id))
      .map((node) => tableId(node.data.table));
    if (ids.length === 0) return;
    const current = new Set(onlyTableIds ?? []);
    if (current.size === ids.length && ids.every((id) => current.has(id))) return;
    setShowAllLines(false);
    setOnlyTableIds([...ids].sort());
  }, [nodes, onlyTableIds, selectedNodeIds]);

  const showAllTables = useCallback(() => {
    setOnlyTableIds(null);
  }, []);

  return {
    selectedNodeIds,
    setSelectedNodeIds,
    selectedNodeIdsRef,
    onlyTableIds,
    setOnlyTableIds,
    onlyTableKey,
    pinnedLinks,
    setPinnedLinks,
    showAllLines,
    setShowAllLines,
    modifySelection,
    selectAllTables,
    hidePinnedRelation,
    clearPinnedLinks,
    showOnlySelected,
    showAllTables,
  };
}

export function useErDiagramFocus(
  nodes: ErTableNodeType[],
  selectedNodeIds: string[],
  pinnedLinks: PinnedErLinks | null,
  relations: ErRelation[] | undefined,
  setPinnedLinks: Dispatch<SetStateAction<PinnedErLinks | null>>,
) {
  const selectedTables = useMemo(
    () => nodes.filter((node) => selectedNodeIds.includes(node.id)).map((node) => node.data.table),
    [nodes, selectedNodeIds],
  );
  const selectedTableKeys = useMemo(
    () => new Set(selectedTables.map((table) => tableId(table))),
    [selectedTables],
  );
  const focusSplit = useMemo(
    () => splitFocusRelations(relations ?? [], selectedTableKeys),
    [relations, selectedTableKeys],
  );
  const focusRelations = useMemo(
    () => [...focusSplit.inside, ...focusSplit.outside],
    [focusSplit],
  );
  const pinnedKeys = useMemo(
    () => new Set(pinnedLinks?.selectedKeys ?? []),
    [pinnedLinks],
  );
  const pinnedSplit = useMemo(
    () => (pinnedLinks ? splitFocusRelations(pinnedLinks.relations, pinnedKeys) : null),
    [pinnedKeys, pinnedLinks],
  );
  const pinnedKeepIds = useMemo(
    () => (pinnedSplit ? focusInvolvedTableIds(pinnedKeys, pinnedSplit) : null),
    [pinnedKeys, pinnedSplit],
  );
  const pinCurrentSelection = useCallback(() => {
    setPinnedLinks({
      relations: focusRelations,
      selectedKeys: [...selectedTableKeys],
    });
  }, [focusRelations, selectedTableKeys, setPinnedLinks]);

  return {
    selectedTables,
    selectedTableKeys,
    focusSplit,
    focusRelations,
    pinnedKeys,
    pinnedSplit,
    pinnedKeepIds,
    pinCurrentSelection,
  };
}
