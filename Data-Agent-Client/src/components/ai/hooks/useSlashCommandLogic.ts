import { useCallback, useMemo, useRef, useState } from 'react';
import { SLASH_COMMANDS, type SlashCommandItem } from '../slashCommands';

interface UseSlashCommandLogicReturn {
  slashOpen: boolean;
  slashQuery: string;
  slashHighlightedIndex: number;
  filteredSlashCommands: SlashCommandItem[];
  setSlashOpen: (open: boolean) => void;
  setSlashQuery: (query: string) => void;
  setSlashHighlightedIndex: (index: number) => void;
  runSlashCommand: (cmd: SlashCommandItem) => void;
  slashStateRef: React.MutableRefObject<{ start: number; query: string }>;
}

interface UseSlashCommandLogicOptions {
  input: string;
  setInput: (value: string) => void;
  onCommand?: (commandId: string) => void;
  supportedCommands?: string[];
}

/** Manages slash command state and logic */
export function useSlashCommandLogic({
  input,
  setInput,
  onCommand,
  supportedCommands,
}: UseSlashCommandLogicOptions): UseSlashCommandLogicReturn {
  const [slashOpen, setSlashOpen] = useState(false);
  const [slashQuery, setSlashQuery] = useState('');
  const [slashHighlightedIndex, setSlashHighlightedIndex] = useState(0);
  const slashStateRef = useRef<{ start: number; query: string }>({ start: 0, query: '' });

  const filteredSlashCommands = useMemo(() => {
    const q = slashQuery.toLowerCase().trim();
    const commands = supportedCommands ? SLASH_COMMANDS.filter(c => supportedCommands.includes(c.id)) : SLASH_COMMANDS;
    return q
      ? commands.filter((c) => c.slug.toLowerCase().startsWith(q))
      : commands;
  }, [slashQuery, supportedCommands]);

  const runSlashCommand = useCallback(
    (cmd: SlashCommandItem) => {
      const { start, query } = slashStateRef.current;
      setInput(input.slice(0, start) + input.slice(start + 1 + query.length));
      setSlashOpen(false);
      onCommand?.(cmd.id);
    },
    [input, setInput, onCommand]
  );

  return {
    slashOpen,
    slashQuery,
    slashHighlightedIndex,
    filteredSlashCommands,
    setSlashOpen,
    setSlashQuery,
    setSlashHighlightedIndex,
    runSlashCommand,
    slashStateRef,
  };
}
