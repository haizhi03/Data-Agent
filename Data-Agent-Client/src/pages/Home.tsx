import { useState, useEffect, useRef, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { TabBar } from "../components/workspace/TabBar";
import { MonacoEditor, type MonacoEditorHandle } from "../components/editor/MonacoEditor";
import { ResultsPanel } from "../components/results/ResultsPanel";
import { Toolbar } from "../components/workspace/Toolbar";
import { TableDataTab } from "../components/workspace/TableDataTab";
import { ErDiagramTab } from "../components/workspace/ErDiagramTab";
import { EmptyState } from "../components/workspace/EmptyState";
import { PlanConsole } from "../components/plan/PlanConsole";
import { SubAgentConsole } from "../components/subagent/SubAgentConsole";
import { useWorkspaceStore } from "../store/workspaceStore";
import type { TableTabMetadata, PlanTabMetadata, SubAgentConsoleTabMetadata, ErDiagramTabMetadata } from "../types/tab";
import type { ExecuteSqlResponse } from "../types/sql";
import { sqlExecutionService } from "../services/sqlExecution.service";
import { connectionService } from "../services/connection.service";
import { getSqlDialectByDbType } from "../constants/sqlDialect";
import { formatSql } from "../utils/sql";
import { I18N_KEYS } from "../constants/i18nKeys";

export default function Home() {
    const { t } = useTranslation();
    const { tabs, activeTabId, updateTabContent, updateTabMetadata } = useWorkspaceStore();
    const [isResultsVisible, setIsResultsVisible] = useState(false);
    const [executeResult, setExecuteResult] = useState<ExecuteSqlResponse | null>(null);
    const editorRef = useRef<MonacoEditorHandle | null>(null);
    const [isRunning, setIsRunning] = useState(false);
    const [connectionType, setConnectionType] = useState<{ id: number; dbType: string } | null>(null);

    const activeTab = tabs.find(t => t.id === activeTabId);
    const isSpecialTab = activeTab?.type === 'plan' || activeTab?.type === 'subagent-console' || activeTab?.type === 'er';
    const sqlContext = !isSpecialTab
        ? activeTab?.metadata as import('../types/tab').ConsoleTabMetadata | undefined
        : undefined;

    useEffect(() => {
        const connectionId = sqlContext?.connectionId;
        setConnectionType(null);
        if (!connectionId) return;
        let cancelled = false;
        connectionService.getConnectionById(connectionId)
            .then(connection => { if (!cancelled) setConnectionType({ id: connectionId, dbType: connection.dbType }); })
            .catch(() => { if (!cancelled) setConnectionType(null); });
        return () => { cancelled = true; };
    }, [sqlContext?.connectionId]);

    const handleFormatSql = useCallback(() => {
        if (!activeTab || activeTab.type !== 'file' || !connectionType || connectionType.id !== sqlContext?.connectionId) return;
        const original = activeTab.content || '';
        const formatted = formatSql(original, getSqlDialectByDbType(connectionType.dbType), connectionType.dbType);
        if (formatted !== original) updateTabContent(activeTab.id, formatted);
    }, [activeTab, connectionType, sqlContext?.connectionId, updateTabContent]);

    const handleRunQuery = useCallback(async () => {
        const sql = editorRef.current?.getSelectionOrAllContent().trim()
                    ?? activeTab?.content?.trim() ?? '';
        if (!sql || !sqlContext?.connectionId) return;

        setIsRunning(true);
        setExecuteResult(null);
        setIsResultsVisible(true);

        try {
            const result = await sqlExecutionService.executeSql({
                connectionId: sqlContext.connectionId,
                databaseName: sqlContext.databaseName ?? undefined,
                schemaName: sqlContext.schemaName ?? undefined,
                sql,
            });
            setExecuteResult({
                ...result,
                originalSql: result.originalSql || sql,
                executedSql: result.executedSql || sql,
            });
        } catch (err: unknown) {
            setExecuteResult({
                success: false,
                errorMessage: String(err),
                executionTimeMs: 0,
                originalSql: sql,
                executedSql: sql,
                query: false,
                affectedRows: 0,
            });
        } finally {
            setIsRunning(false);
        }
    }, [activeTab, sqlContext]);

    // SQL Editor Shortcuts
    useEffect(() => {
        const handleKeyDown = (e: KeyboardEvent) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                e.preventDefault();
                handleRunQuery();
            }
        };

        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [handleRunQuery]);

    return (
        <div className="flex-1 min-w-0 h-full overflow-hidden flex flex-col">
            {/* Tab Bar */}
            <TabBar />

            {/* Workspace Area */}
            {activeTab?.type === 'plan' ? (
                <div className="flex-1 min-h-0 overflow-hidden bg-transparent">
                    <PlanConsole
                        tabId={activeTab.id}
                        payload={(activeTab.metadata as PlanTabMetadata).planPayload}
                    />
                </div>
            ) : activeTab?.type === 'subagent-console' ? (
                <div className="flex-1 min-h-0 overflow-hidden bg-transparent">
                    <SubAgentConsole
                        tabId={activeTab.id}
                        metadata={activeTab.metadata as SubAgentConsoleTabMetadata}
                    />
                </div>
            ) : activeTab?.type === 'er' && activeTab.metadata ? (
                <div className="flex-1 min-h-0 overflow-hidden bg-transparent">
                    <ErDiagramTab metadata={activeTab.metadata as ErDiagramTabMetadata} />
                </div>
            ) : (
                <ResultsPanel
                    isVisible={isResultsVisible}
                    onClose={() => setIsResultsVisible(false)}
                    executeResult={executeResult}
                    isRunning={isRunning}
                >
                    <div className="flex-1 flex flex-col min-h-0 relative bg-transparent">
                        {activeTab?.type === 'file' && (
                            <div className="workbench-header flex h-10 items-center gap-1 px-3 text-[10px] theme-text-secondary shrink-0">
                                <Toolbar
                                    onRun={handleRunQuery}
                                    onFormat={connectionType && connectionType.id === sqlContext?.connectionId ? handleFormatSql : undefined}
                                    onStop={() => setIsRunning(false)}
                                    isRunning={isRunning}
                                    connectionId={sqlContext?.connectionId}
                                    currentDatabase={sqlContext?.databaseName}
                                    onDatabaseChange={(db) =>
                                        updateTabMetadata(activeTab.id, { databaseName: db })
                                    }
                                />
                            </div>
                        )}

                        <div className="flex-1 relative overflow-hidden flex flex-col">
                            <div className="flex-1 relative overflow-hidden">
                                {activeTab?.type === 'file' ? (
                                    <MonacoEditor
                                        ref={editorRef}
                                        value={activeTab.content || ''}
                                        onChange={(val) => updateTabContent(activeTab.id, val || '')}
                                    />
                                ) : activeTab?.type === 'table' && activeTab.metadata ? (
                                    <TableDataTab key={activeTab.id} tabId={activeTab.id} metadata={activeTab.metadata as TableTabMetadata} />
                                ) : activeTab?.type === 'table' ? (
                                    <div className="flex-1 h-full flex items-center justify-center theme-text-secondary italic text-xs">
                                        -- {t(I18N_KEYS.WORKSPACE.DATA_GRID_PLACEHOLDER)} --
                                    </div>
                                ) : (
                                    <EmptyState />
                                )}
                            </div>
                        </div>
                    </div>
                </ResultsPanel>
            )}
        </div>
    );
}
