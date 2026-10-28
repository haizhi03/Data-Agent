import { useState, useEffect, useCallback } from "react";
import { BrowserRouter as Router, useRoutes, useLocation } from "react-router-dom";
import { SettingsModal } from "./components/common/SettingsModal";
import { ThemeProvider } from "./hooks/useTheme";
import { Header } from "./components/layouts/Header";
import { ToastContainer } from "./components/ui/Toast";
import { useWorkspaceStore } from "./store/workspaceStore";
import { routerConfig } from "./router.tsx";
import { ROUTES } from "./constants/routes";
import { DataAttributes } from "./constants/dataAttributes";
import { WorkspaceLayout } from "./components/layouts/WorkspaceLayout";
import { useRuntimeStore, localWorkbenchReady } from './store/runtimeStore';
import { initializeRuntime } from './lib/localRuntime';
import { useTranslation } from 'react-i18next';

function AppRoutes({
    showAI,
    toggleAISidebar,
    showExplorer,
    toggleExplorer
}: {
    showAI: boolean;
    toggleAISidebar: () => void;
    showExplorer: boolean;
    toggleExplorer: () => void;
}) {
    const element = useRoutes(routerConfig);
    const location = useLocation();
    const isWorkspace = location.pathname === ROUTES.HOME;

    if (!isWorkspace) {
        return (
            <div className="flex-1 flex flex-col overflow-hidden bg-background">
                <main className="flex-1 overflow-y-auto p-8 animate-fade-in">
                    {element}
                </main>
            </div>
        );
    }

    return (
        <WorkspaceLayout 
            showAI={showAI} 
            onToggleAI={toggleAISidebar}
            showExplorer={showExplorer}
            onToggleExplorer={toggleExplorer}
        >
            {element}
        </WorkspaceLayout>
    );
}

function App() {
    const runtime = useRuntimeStore();
    const { t } = useTranslation();
    const { setSettingsModalOpen } = useWorkspaceStore();

    // Layout state
    const [showAI, setShowAI] = useState(false);
    const [showExplorer, setShowExplorer] = useState(true);

    useEffect(() => {
        if (localWorkbenchReady(runtime)) useWorkspaceStore.getState().fetchSupportedDbTypes();
    }, [runtime.status]);

    // Disable browser context menu except where custom menus exist (e.g. Explorer ContextMenu)
    useEffect(() => {
        const handleContextMenu = (e: MouseEvent) => {
            const target = e.target as HTMLElement;
            // Allow Radix context menus: trigger is inside [data-radix-context-menu-trigger] or similar
            if (target.closest('[data-radix-popper-content-wrapper]') ||
                target.closest('[role="menu"]') ||
                target.closest('[data-explorer-context-menu]')) {
                return;
            }
            // Allow Explorer tree nodes - they use ContextMenu for "View DDL"
            if (target.closest(`[${DataAttributes.EXPLORER_TREE}]`)) {
                return;
            }
            e.preventDefault();
        };
        document.addEventListener('contextmenu', handleContextMenu);
        return () => document.removeEventListener('contextmenu', handleContextMenu);
    }, []);

    const toggleAISidebar = useCallback(() => {
        setShowAI(prev => {
            const newState = !prev;
            if (newState) {
                setTimeout(() => {
                    const aiInput = document.querySelector(`textarea[${DataAttributes.AI_INPUT}]`) as HTMLTextAreaElement;
                    aiInput?.focus();
                }, 100);
            }
            return newState;
        });
    }, []);

    const toggleExplorer = useCallback(() => {
        setShowExplorer(prev => !prev);
    }, []);

    // Global keyboard shortcuts
    useEffect(() => {
        const handleKeyDown = (e: KeyboardEvent) => {
            // Cmd+B or Ctrl+B: Toggle AI Sidebar
            if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "b") {
                e.preventDefault();
                toggleAISidebar();
            }

            // ESC: Toggle Database Explorer (if not in a text input)
            if (e.key === "Escape") {
                const activeEl = document.activeElement;
                const isInput = activeEl?.tagName === "INPUT" ||
                               activeEl?.tagName === "TEXTAREA" ||
                               (activeEl as HTMLElement)?.isContentEditable;

                if (!isInput) {
                    e.preventDefault();
                    toggleExplorer();
                }
            }

            // Cmd+Shift+, or Ctrl+Shift+,: Open Settings
            if ((e.metaKey || e.ctrlKey) && e.shiftKey && (e.key === ',' || e.code === 'Comma')) {
                e.preventDefault();
                setSettingsModalOpen(true);
            }
        };

        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [toggleAISidebar, toggleExplorer, setSettingsModalOpen]);

    if (runtime.status !== 'READY' || !localWorkbenchReady(runtime)) return (
        <div className="h-screen flex items-center justify-center bg-background text-foreground" role="alert">
            <div className="space-y-4 text-center"><p>{t(runtime.error ?? (runtime.status === 'READY' ? 'runtime.store_unavailable' : 'runtime.starting'))}</p>
                <button type="button" onClick={() => void initializeRuntime()}>{t('runtime.retry')}</button></div>
        </div>
    );
    return (
        <ThemeProvider>
            <Router>
                <div className="h-[100dvh] w-full min-w-0 flex flex-col theme-bg-main theme-text-primary overflow-hidden">
                    <Header onToggleAI={toggleAISidebar} />

                    <AppRoutes 
                        showAI={showAI}
                        toggleAISidebar={toggleAISidebar}
                        showExplorer={showExplorer}
                        toggleExplorer={toggleExplorer}
                    />

                    <ToastContainer />
                    <SettingsModal />
                </div>
            </Router>
        </ThemeProvider>
    );
}

export default App;
