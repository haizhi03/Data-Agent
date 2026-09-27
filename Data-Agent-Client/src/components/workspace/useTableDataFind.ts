import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { Ref } from 'react';
import type { TableDataResponse } from '../../services/tableData.service';

export interface FindMatch {
  rowIndex: number;
  colKey: string;
}

interface UseTableDataFindArgs {
  data: TableDataResponse | null;
  viewMode: 'grid' | 'transpose';
  formatCellValue: (value: unknown) => string;
}

export function useTableDataFind({ data, viewMode, formatCellValue }: UseTableDataFindArgs) {
  const [findVisible, setFindVisible] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');
  const [currentMatchIndex, setCurrentMatchIndex] = useState(0);
  const searchInputRef = useRef<HTMLInputElement | null>(null);
  const wasVisibleRef = useRef(false);

  const isGridMode = viewMode === 'grid';

  // 仅在当前页已加载的数据中匹配，不发起新查询、不改动 WHERE
  const matches = useMemo<FindMatch[]>(() => {
    if (!findVisible || !isGridMode || !data) return [];
    const term = searchTerm.trim().toLowerCase();
    if (!term) return [];
    const result: FindMatch[] = [];
    data.rows.forEach((row, rowIndex) => {
      data.headers.forEach((header, colIndex) => {
        if (formatCellValue(row[colIndex]).toLowerCase().includes(term)) {
          result.push({ rowIndex, colKey: header });
        }
      });
    });
    return result;
  }, [data, findVisible, formatCellValue, isGridMode, searchTerm]);

  // 输入内容变化时回到第一个匹配
  useEffect(() => {
    setCurrentMatchIndex(0);
  }, [searchTerm]);

  // 翻页/刷新后按新一页数据重算，索引越界时收敛
  useEffect(() => {
    setCurrentMatchIndex((prev) => (matches.length === 0 ? 0 : Math.min(prev, matches.length - 1)));
  }, [matches]);

  const matchedKeys = useMemo(() => {
    const set = new Set<string>();
    matches.forEach((match) => set.add(`${match.rowIndex}::${match.colKey}`));
    return set;
  }, [matches]);

  const currentMatch = matches.length > 0 ? (matches[currentMatchIndex] ?? null) : null;

  const focusInput = useCallback(() => {
    searchInputRef.current?.focus();
    searchInputRef.current?.select();
  }, []);

  useEffect(() => {
    if (findVisible && !wasVisibleRef.current) {
      focusInput();
    }
    wasVisibleRef.current = findVisible;
  }, [findVisible, focusInput]);

  const onToggleFind = useCallback(() => {
    setFindVisible((visible) => !visible);
  }, []);

  const onCloseFind = useCallback(() => {
    setFindVisible(false);
    setSearchTerm('');
    setCurrentMatchIndex(0);
  }, []);

  const onFindNext = useCallback(() => {
    setCurrentMatchIndex((prev) => (matches.length === 0 ? 0 : (prev + 1) % matches.length));
  }, [matches.length]);

  const onFindPrevious = useCallback(() => {
    setCurrentMatchIndex((prev) => (matches.length === 0 ? 0 : (prev - 1 + matches.length) % matches.length));
  }, [matches.length]);

  // Ctrl/Cmd+F 仅在表格页生效；焦点在输入框/编辑器内时不抢占快捷键
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 'f') return;
      const target = event.target as HTMLElement | null;
      const tag = target?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || target?.isContentEditable) return;
      event.preventDefault();
      if (findVisible) {
        focusInput();
      } else {
        setFindVisible(true);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [findVisible, focusInput]);

  return {
    findVisible,
    findDisabled: !isGridMode || !data,
    searchTerm,
    searchInputRef: searchInputRef as Ref<HTMLInputElement>,
    matchCount: matches.length,
    currentMatchIndex,
    matchedKeys,
    currentMatch,
    onToggleFind,
    onSearchTermChange: setSearchTerm,
    onFindNext,
    onFindPrevious,
    onCloseFind,
  };
}
