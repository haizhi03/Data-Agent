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
import { useSqlRunStore } from "../store/sqlRunStore";
import type { TableTabMetadata, PlanTabMetadata, SubAgentConsoleTabMetadata, ErDiagramTabMetadata } from "../types/tab";
import type { SqlBatchMode, SqlSyntaxError } from "../types/sql";
import { connectionService } from "../services/connection.service";
import { sqlAnalysisService } from "../services/sqlAnalysis.service";
import { getSqlDialectByDbType } from "../constants/sqlDialect";
import { formatSql } from "../utils/sql";
import { containsNonAtomicStatement, statementSqlAtOffset } from "../utils/sqlRunner";
import { extractSqlVariables, substituteSqlVariablesAtOffset, type SqlVariable } from "../utils/sqlVariables";
import { SqlParameterDialog } from "../components/editor/SqlParameterDialog";
import { useToast } from '../hooks/useToast';
import { Button } from '../components/ui/Button';
import { I18N_KEYS } from "../constants/i18nKeys";

const NO_VARIABLES: SqlVariable[] = [];

export default function Home() {
    const { t } = useTranslation();
    const toast = useToast();
    const preparingRunRef = useRef(false);
    const { tabs, activeTabId, updateTabContent, updateTabMetadata } = useWorkspaceStore();
    const {
        executeResult,
        scriptResults,
        isRunning,
        isResultsVisible,
        cancelStatus,
        runSql,
        stopSql,
        setResultsVisible,
    } = useSqlRunStore();
    const [timeoutSeconds, setTimeoutSeconds] = useState(30);
    const [maxRows, setMaxRows] = useState(10000);
    const editorRef = useRef<MonacoEditorHandle | null>(null);
    const [connectionType, setConnectionType] = useState<{ id: number; dbType: string } | null>(null);
    const [diagnostics, setDiagnostics] = useState<SqlSyntaxError[]>([]);
    const [pendingParameters, setPendingParameters] = useState<{
        sql: string;
        variables: SqlVariable[];
        mysql: boolean;
        cursorOffset?: number;
        tabId?: string;
    } | null>(null);

    const activeTab = tabs.find(t => t.id === activeTabId);
    const erTabs = tabs.filter((tab) => tab.type === 'er' && tab.metadata);
    const isSpecialTab = activeTab?.type === 'plan' || activeTab?.type === 'subagent-console' || activeTab?.type === 'er';
    const sqlContext = !isSpecialTab
        ? activeTab?.metadata as import('../types/tab').ConsoleTabMetadata | undefined
        : undefined;

    const scriptMode: SqlBatchMode = sqlContext?.sqlBatchMode ?? 'ATOMIC';

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

    const executeSqlText = useCallback(async (sql: string, cursorOffset?: number) => {
        if (!sql || !sqlContext?.connectionId || isRunning || preparingRunRef.current) return;
        if (cursorOffset !== undefined) {
            preparingRunRef.current = true;
            try {
                const analysis = await sqlAnalysisService.analyze({ ...sqlContext, sql });
                // A response for a previous tab or an edited script must not execute.
                const workspace = useWorkspaceStore.getState();
                const currentTab = workspace.tabs.find(tab => tab.id === activeTabId);
                const currentContext = currentTab?.metadata as import('../types/tab').ConsoleTabMetadata | undefined;
                if (workspace.activeTabId !== activeTabId
                    || currentTab?.content !== activeTab?.content
                    || currentContext?.connectionId !== sqlContext.connectionId
                    || currentContext?.databaseName !== sqlContext.databaseName
                    || currentContext?.schemaName !== sqlContext.schemaName) return;
                sql = statementSqlAtOffset(sql, cursorOffset, analysis);
                if (!sql.trim()) return;
            } catch (error) {
                toast.error(t(I18N_KEYS.WORKSPACE.STATEMENT_ANALYSIS_FAILED, { error: String(error) }));
                return;
            } finally {
                preparingRunRef.current = false;
            }
        }

        // ATOMIC mode only accepts queries and transactional DML; DDL (e.g. the
        // generated DROP/CREATE/ALTER scripts) is rejected server-side. Auto-switch
        // to STEPWISE for those scripts so they run instead of failing.
        const effectiveMode: SqlBatchMode =
            scriptMode === 'ATOMIC' && containsNonAtomicStatement(sql, connectionType?.dbType.toUpperCase() !== 'DM') ? 'STEPWISE' : scriptMode;

        await runSql({
            connectionId: sqlContext.connectionId,
            databaseName: sqlContext.databaseName,
            schemaName: sqlContext.schemaName,
            dbType: connectionType?.id === sqlContext.connectionId ? connectionType.dbType : undefined,
            sql,
            mode: effectiveMode,
            timeoutMs: timeoutSeconds * 1000,
            maxRows,
        });
    }, [connectionType, sqlContext, scriptMode, timeoutSeconds, maxRows, runSql, isRunning, activeTabId, activeTab?.content, t, toast]);

    const runSqlFromEditor = useCallback((getInput: () => { sql: string; cursorOffset?: number }) => {
        if (pendingParameters || isRunning || preparingRunRef.current || activeTab?.type !== 'file') return;
        const { sql, cursorOffset } = getInput();
        if (!sql.trim() || !sqlContext?.connectionId) return;

        const mysql = connectionType?.dbType.toUpperCase() !== 'DM';
        const variables = extractSqlVariables(sql, mysql);
        if (variables.length > 0) {
            setPendingParameters({ sql, variables, mysql, cursorOffset, tabId: activeTabId ?? undefined });
            return;
        }
        void executeSqlText(sql, cursorOffset);
    }, [connectionType, executeSqlText, pendingParameters, sqlContext?.connectionId, isRunning, activeTab?.type, activeTabId]);

    // Toolbar "Run": execute the whole script, or the selection when one is active.
    const handleRunQuery = useCallback(() => {
        runSqlFromEditor(() =>
            ({ sql: editorRef.current?.getSelectionOrAllContent() ?? activeTab?.content ?? '' }));
    }, [runSqlFromEditor, activeTab?.content]);

    // Ctrl/Cmd+Enter: execute the statement under the cursor, or the selection.
    const handleRunStatement = useCallback(() => {
        runSqlFromEditor(() =>
            editorRef.current?.getSelectionOrCursorInput() ?? { sql: '' });
    }, [runSqlFromEditor]);

    const handleStop = useCallback(() => { void stopSql(); }, [stopSql]);

    // SQL Editor Shortcuts
    useEffect(() => {
        const handleKeyDown = (e: KeyboardEvent) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                e.preventDefault();
                handleRunStatement();
            }
        };

        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [handleRunStatement]);

    return (
        <div className="flex-1 min-w-0 h-full overflow-hidden flex flex-col">
            {/* Tab Bar */}
            <TabBar />
            {isRunning && (
                <div className="workbench-header flex items-center justify-between px-3 py-1 text-xs">
                    <span>{t(I18N_KEYS.WORKSPACE.SQL_RUNNING)}</span>
                    <Button variant="outline" size="sm" onClick={handleStop}>{t(I18N_KEYS.COMMON.STOP)}</Button>
                </div>
            )}

            {/* Opened ER diagrams stay mounted so switching back skips reload and layout.
                Retained diagrams use opacity-0, not display:none, so the canvas keeps its
                clientWidth/clientHeight. visibility:hidden still paints React Flow's transformed
                layer through the semi-transparent insert row and Output panel. */}
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
                            onClose={() => setResultsVisible(false)}
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
                                            onScriptModeChange={(mode) => updateTabMetadata(activeTab.id, { sqlBatchMode: mode })}
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

            <SqlParameterDialog
                open={pendingParameters !== null}
                variables={pendingParameters?.variables ?? NO_VARIABLES}
                onCancel={() => setPendingParameters(null)}
                onSubmit={(values) => {
                    const pending = pendingParameters;
                    setPendingParameters(null);
                    if (pending && useWorkspaceStore.getState().activeTabId === pending.tabId) {
                        const input = substituteSqlVariablesAtOffset(pending.sql, values, pending.mysql, pending.cursorOffset);
                        void executeSqlText(input.sql, input.cursorOffset);
                    }
                }}
            />
        </div>
    );
}
