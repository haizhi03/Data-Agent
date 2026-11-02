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
import type { ExecuteSqlResponse, SqlBatchMode, SqlSyntaxError } from "../types/sql";
import { sqlExecutionService } from "../services/sqlExecution.service";
import { sqlAnalysisService } from "../services/sqlAnalysis.service";
import { connectionService } from "../services/connection.service";
import { getSqlDialectByDbType } from "../constants/sqlDialect";
import { formatSql } from "../utils/sql";
import { I18N_KEYS } from "../constants/i18nKeys";
import { EXPLORER_SQL_OBJECT_CHANGED } from "../constants/explorer";

export default function Home() {
    const { t } = useTranslation();
    const { tabs, activeTabId, updateTabContent, updateTabMetadata } = useWorkspaceStore();
    const [isResultsVisible, setIsResultsVisible] = useState(false);
    const [executeResult, setExecuteResult] = useState<ExecuteSqlResponse | null>(null);
    const [scriptResults, setScriptResults] = useState<ExecuteSqlResponse[]>([]);
    const [scriptMode, setScriptMode] = useState<SqlBatchMode>('ATOMIC');
    const [timeoutSeconds, setTimeoutSeconds] = useState(30);
    const [maxRows, setMaxRows] = useState(10000);
    const executionIdRef = useRef<string | null>(null);
    const [cancelStatus, setCancelStatus] = useState<string | null>(null);
    const editorRef = useRef<MonacoEditorHandle | null>(null);
    const [isRunning, setIsRunning] = useState(false);
    const [connectionType, setConnectionType] = useState<{ id: number; dbType: string } | null>(null);
    const [diagnostics, setDiagnostics] = useState<SqlSyntaxError[]>([]);

    const activeTab = tabs.find(t => t.id === activeTabId);
    const erTabs = tabs.filter((tab) => tab.type === 'er' && tab.metadata);
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

    useEffect(() => {
        const connectionId = sqlContext?.connectionId;
        const sql = activeTab?.type === 'file' ? activeTab.content : undefined;
        setDiagnostics([]);
        if (!connectionId || connectionType?.id !== connectionId
            || connectionType.dbType.toUpperCase() !== 'DM' || !sql?.trim()) return;
        let cancelled = false;
        const timeout = window.setTimeout(() => {
            sqlAnalysisService.analyze({
                connectionId,
                databaseName: sqlContext.databaseName,
                schemaName: sqlContext.schemaName,
                sql,
            }).then(result => {
                if (!cancelled) setDiagnostics(result.errors);
            }).catch(() => {
                if (!cancelled) setDiagnostics([]);
            });
        }, 350);
        return () => {
            cancelled = true;
            window.clearTimeout(timeout);
        };
    }, [activeTab?.content, activeTab?.type, activeTabId, connectionType, sqlContext?.connectionId,
        sqlContext?.databaseName, sqlContext?.schemaName]);

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
        setCancelStatus(null);
        const executionId = crypto.randomUUID();
        executionIdRef.current = executionId;
        setExecuteResult(null);
        setScriptResults([]);
        setIsResultsVisible(true);

        try {
            const results = await sqlExecutionService.executeScript({
                connectionId: sqlContext.connectionId,
                databaseName: sqlContext.databaseName ?? undefined,
                schemaName: sqlContext.schemaName ?? undefined,
                sql,
                mode: scriptMode,
                timeoutMs: timeoutSeconds * 1000,
                maxRows,
                executionId,
            });
            setScriptResults(results);
            const result = results[0];
            if (!result) throw new Error('The script contained no executable statements');
            setExecuteResult({
                ...result,
                originalSql: result.originalSql || sql,
                executedSql: result.executedSql || sql,
            });
            if (results.some(item => item.success) && connectionType?.id === sqlContext.connectionId
                && connectionType.dbType.toUpperCase() === 'DM') {
                sqlAnalysisService.analyze({
                    connectionId: sqlContext.connectionId,
                    databaseName: sqlContext.databaseName,
                    schemaName: sqlContext.schemaName,
                    sql,
                }).then(analysis => {
                    if (analysis.errors.length) return;
                    for (const [index, statement] of analysis.statements.entries()) {
                        if (results[index]?.success && ['CREATE', 'ALTER', 'DROP', 'TRUNCATE'].includes(statement.type) && statement.objectType) {
                            window.dispatchEvent(new CustomEvent(EXPLORER_SQL_OBJECT_CHANGED, {
                                detail: {
                                    connectionId: sqlContext.connectionId,
                                    catalog: sqlContext.databaseName,
                                    schema: sqlContext.schemaName,
                                    objectType: statement.objectType,
                                },
                            }));
                        }
                    }
                }).catch(() => undefined);
            }
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
            executionIdRef.current = null;
            setIsRunning(false);
        }
    }, [activeTab, connectionType, sqlContext, scriptMode, timeoutSeconds, maxRows]);

    const handleStop = useCallback(async () => {
        if (!executionIdRef.current || !sqlContext?.connectionId) return;
        try {
            const outcome = await sqlExecutionService.cancelExecution({
                connectionId: sqlContext.connectionId,
                databaseName: sqlContext.databaseName,
                schemaName: sqlContext.schemaName,
                executionId: executionIdRef.current,
            });
            setCancelStatus(outcome === 'CANCEL_REQUESTED' ? 'Cancel requested; waiting for database confirmation'
                : outcome === 'CANCEL_FAILED' ? 'Cancel request failed; query may still be running'
                    : 'No active database statement to cancel');
        } catch (error) {
            setCancelStatus(`Cancel request failed: ${String(error)}`);
        }
    }, [sqlContext]);

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

            {/* Opened ER diagrams stay mounted so switching back skips reload and layout. */}
            <div className="relative min-h-0 flex-1">
                {erTabs.map((tab) => {
                    const retained = activeTabId !== tab.id;
                    return (
                        <div
                            key={tab.id}
                            className={`absolute inset-0 isolate overflow-hidden bg-transparent ${retained ? 'z-0 opacity-0 pointer-events-none' : 'z-10'}`}
                            aria-hidden={retained}
                        >
                            <ErDiagramTab metadata={tab.metadata as ErDiagramTabMetadata} />
                        </div>
                    );
                })}
                {activeTab?.type === 'plan' ? (
                    <div className="absolute inset-0 z-10 flex min-h-0 flex-col overflow-hidden bg-transparent">
                        <PlanConsole
                            tabId={activeTab.id}
                            payload={(activeTab.metadata as PlanTabMetadata).planPayload}
                        />
                    </div>
                ) : activeTab?.type === 'subagent-console' ? (
                    <div className="absolute inset-0 z-10 min-h-0 overflow-hidden bg-transparent">
                        <SubAgentConsole
                            tabId={activeTab.id}
                            metadata={activeTab.metadata as SubAgentConsoleTabMetadata}
                        />
                    </div>
                ) : activeTab?.type === 'er' ? null : (
                    <div className="absolute inset-0 z-10 flex h-full min-h-0 flex-col overflow-hidden">
                        <ResultsPanel
                            isVisible={isResultsVisible}
                            onClose={() => setIsResultsVisible(false)}
                            executeResult={executeResult}
                            scriptResults={scriptResults}
                            isRunning={isRunning}
                            cancelStatus={cancelStatus}
                        >
                            <div className="flex-1 flex flex-col min-h-0 relative bg-transparent">
                                {activeTab?.type === 'file' && (
                                    <div className="workbench-header flex h-10 items-center gap-1 px-3 text-[10px] theme-text-secondary shrink-0">
                                        <Toolbar
                                            onRun={handleRunQuery}
                                            onFormat={connectionType && connectionType.id === sqlContext?.connectionId ? handleFormatSql : undefined}
                                            onStop={handleStop}
                                            isRunning={isRunning}
                                            scriptMode={scriptMode}
                                            onScriptModeChange={setScriptMode}
                                            timeoutSeconds={timeoutSeconds}
                                            onTimeoutSecondsChange={setTimeoutSeconds}
                                            maxRows={maxRows}
                                            onMaxRowsChange={setMaxRows}
                                            showTransactionControls={connectionType?.dbType.toUpperCase() !== 'DM'}
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
                                                diagnostics={diagnostics}
                                                completionContext={{
                                                    connectionId: sqlContext?.connectionId,
                                                    databaseName: sqlContext?.databaseName,
                                                    schemaName: sqlContext?.schemaName,
                                                }}
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
                    </div>
                )}
            </div>
        </div>
    );
}
