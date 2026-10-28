import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Plus, X } from 'lucide-react';
import { cn } from '../../lib/utils';

export interface ConversationTabSummary {
  id: number | null;
  titleOverride?: string | null;
  titleCandidate: string | null;
  messageCount: number;
  createdAt: number;
  lastTouchedAt: number;
}

interface AIAssistantHeaderProps {
  currentConversationId: number | null;
  conversationTabs: ConversationTabSummary[];
  onSelectTab: (id: number | null) => void;
  onCloseTab: (id: number | null) => void;
  onNewChat: () => void;
  onClosePanel?: () => void;
}

function formatTabTitle(tab: ConversationTabSummary): { display: string; full: string } {
  const raw = tab.titleOverride || tab.titleCandidate || '';
  const candidate = raw.replace(/\s+/g, ' ').trim();
  const full = candidate || (tab.id == null || tab.id <= 0 ? 'New chat' : `Conversation ${tab.id}`);
  const display = full.length > 28 ? `${full.slice(0, 28)}…` : full;
  return { display, full };
}

export function AIAssistantHeader({
  currentConversationId,
  conversationTabs,
  onSelectTab,
  onCloseTab,
  onNewChat,
  onClosePanel,
}: AIAssistantHeaderProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(false);
  const [isTabsScrolling, setIsTabsScrolling] = useState(false);
  const scrollEndTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const tabs = useMemo<ConversationTabSummary[]>(() => conversationTabs.length === 0 ? [{
    id: null,
    titleCandidate: null,
    messageCount: 0,
    createdAt: Date.now(),
    lastTouchedAt: Date.now(),
  }] : conversationTabs, [conversationTabs]);

  const updateScrollState = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const maxLeft = el.scrollWidth - el.clientWidth;
    setCanScrollLeft(el.scrollLeft > 1);
    setCanScrollRight(maxLeft - el.scrollLeft > 1);
  }, []);

  const onTabsScroll = useCallback(() => {
    updateScrollState();
    setIsTabsScrolling(true);
    if (scrollEndTimerRef.current) clearTimeout(scrollEndTimerRef.current);
    scrollEndTimerRef.current = setTimeout(() => setIsTabsScrolling(false), 700);
  }, [updateScrollState]);

  useEffect(() => () => {
    if (scrollEndTimerRef.current) clearTimeout(scrollEndTimerRef.current);
  }, []);

  useEffect(() => {
    updateScrollState();
    const onResize = () => updateScrollState();
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [tabs.length, updateScrollState]);

  useEffect(() => {
    const active = scrollRef.current?.querySelector<HTMLElement>('[data-ai-tab-active="true"]');
    active?.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' });
    setTimeout(updateScrollState, 0);
  }, [currentConversationId, updateScrollState]);

  return (
    <div className="workbench-header flex items-center justify-between gap-2 px-3 py-2 shrink-0">
      <div className="relative min-w-0 flex-1 overflow-hidden">
        {canScrollLeft && <div className="pointer-events-none absolute left-0 top-0 bottom-0 z-10 w-6 bg-gradient-to-r from-[color:var(--bg-panel)] via-[color:var(--bg-panel)]/88 to-transparent" />}
        {canScrollRight && <div className="pointer-events-none absolute right-0 top-0 bottom-0 z-10 w-6 bg-gradient-to-l from-[color:var(--bg-panel)] via-[color:var(--bg-panel)]/88 to-transparent" />}
        <div
          ref={scrollRef}
          className={cn('tabs-scroll h-8 flex items-center gap-1.5', isTabsScrolling ? 'tabs-scroll--scrolling' : 'tabs-scroll--idle')}
          onScroll={onTabsScroll}
          onWheel={(event) => {
            const el = scrollRef.current;
            if (!el || el.scrollWidth <= el.clientWidth) return;
            if (Math.abs(event.deltaY) > Math.abs(event.deltaX)) {
              event.preventDefault();
              el.scrollBy({ left: event.deltaY, behavior: 'auto' });
            }
          }}
        >
          {tabs.map((tab) => {
            const isActive = tab.id === currentConversationId;
            const { display, full } = formatTabTitle(tab);
            return (
              <div
                key={tab.id == null ? '__new__' : String(tab.id)}
                data-ai-tab-active={isActive ? 'true' : undefined}
                role="tab"
                tabIndex={0}
                className={cn(
                  'workbench-chip flex h-8 max-w-[220px] shrink-0 items-center gap-1.5 rounded-xl px-3 text-xs leading-none select-none group',
                  isActive ? 'workbench-chip--active text-[color:var(--text-primary)]' : 'theme-text-secondary hover:theme-text-primary'
                )}
                title={full}
                onClick={() => onSelectTab(tab.id)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    onSelectTab(tab.id);
                  }
                }}
              >
                <span className="truncate min-w-0">{display}</span>
                <button
                  type="button"
                  className="workbench-icon-button h-5 w-5 p-0 shrink-0 opacity-0 transition-colors group-hover:opacity-100 hover:bg-red-500/20 hover:text-red-400"
                  aria-label="Close tab"
                  title="Close"
                  onClick={(event) => {
                    event.stopPropagation();
                    onCloseTab(tab.id);
                  }}
                  onPointerDown={(event) => event.stopPropagation()}
                >
                  <X className="w-3 h-3" />
                </button>
              </div>
            );
          })}
        </div>
      </div>
      <div className="flex items-center gap-2 theme-text-secondary shrink-0">
        <button type="button" className="workbench-icon-button" onClick={onNewChat} aria-label="New chat" title="New chat">
          <Plus className="w-4 h-4" />
        </button>
        {onClosePanel && (
          <button type="button" className="workbench-icon-button" aria-label="Close panel" title="Close panel" onClick={onClosePanel}>
            <X className="w-4 h-4" />
          </button>
        )}
      </div>
    </div>
  );
}
