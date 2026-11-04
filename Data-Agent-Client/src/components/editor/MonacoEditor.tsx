import Editor, { loader } from '@monaco-editor/react';
import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { SqlCompletionItem, SqlCompletionKind, SqlSyntaxError } from '../../types/sql';
import { sqlCompletionService } from '../../services/sqlCompletion.service';
import { useTheme } from '../../hooks/useTheme';
import { useAuthStore } from '../../store/authStore';
import { EXPLORER_SQL_OBJECT_CHANGED } from '../../constants/explorer';
import { SqlCompletionSession, staticCompletions, completionSortText } from './completion/sqlCompletion';
import { parameterSpans } from './completion/sqlSignatureHelp';
import { SqlCompletionDictionary } from './completion/sqlCompletionDictionary';
import './MonacoEditor.css';

// Configure Monaco CDN
loader.config({ paths: { vs: 'https://cdn.jsdelivr.net/npm/monaco-editor@0.43.0/min/vs' } });

export interface MonacoEditorHandle {
  /**
   * Returns the selected text if selection exists, otherwise returns full editor content
   */
  getSelectionOrAllContent(): string;

  /** Snapshot the selection, or full SQL and cursor for dialect-aware execution. */
  getSelectionOrCursorInput(): { sql: string; cursorOffset?: number };

  /**
   * Programmatically set editor content
   */
  setValue(value: string): void;
}

interface SqlCompletionContext {
  connectionId?: number;
  databaseName?: string | null;
  schemaName?: string | null;
}

interface MonacoEditorProps {
  value: string;
  onChange?: (value: string | undefined) => void;
  language?: string;
  readOnly?: boolean;
  height?: string | number;
  diagnostics?: SqlSyntaxError[];
  completionContext?: SqlCompletionContext;
}

const COMPLETION_KIND_MAP: Record<SqlCompletionKind, string> = {
  KEYWORD: 'Keyword',
  FUNCTION: 'Function',
  SCHEMA: 'Module',
  TABLE: 'Class',
  VIEW: 'Struct',
  COLUMN: 'Field',
  ALIAS: 'Variable',
  PROCEDURE: 'Method',
  SNIPPET: 'Snippet',
  ALL_COLUMNS: 'Field',
  JOIN: 'Reference',
  VARIABLE: 'Variable',
  PACKAGE: 'Module',
  SYNONYM: 'Reference',
};

const COMPLETION_TYPE_KEYS: Record<SqlCompletionKind, string> = {
  KEYWORD: 'keyword', FUNCTION: 'function', PROCEDURE: 'procedure', SCHEMA: 'schema',
  TABLE: 'table', VIEW: 'view', COLUMN: 'column', ALIAS: 'alias', SNIPPET: 'snippet',
  ALL_COLUMNS: 'all_columns', JOIN: 'join',
  VARIABLE: 'variable', PACKAGE: 'package', SYNONYM: 'synonym',
};

// Keep all supplementary information in Monaco's right-aligned description column.
const completionLabel = (item: SqlCompletionItem, typeLabel: string) => {
  let description = typeLabel;
  if (item.kind === 'FUNCTION' || item.kind === 'PROCEDURE') {
    const [signature, returnType] = (item.detail ?? '').split(' → ');
    const parameters = signature.startsWith(item.label) ? signature.slice(item.label.length).trim() : signature.trim();
    const signatureDetail = [parameters, returnType].filter(Boolean).join(' → ');
    description = [typeLabel, signatureDetail].filter(Boolean).join(' · ');
  } else if (item.kind === 'COLUMN') {
    description = [typeLabel, item.detail].filter(Boolean).join(' · ');
  } else if (item.kind === 'VARIABLE' || item.kind === 'SYNONYM') {
    description = [typeLabel, item.detail].filter(Boolean).join(' · ');
  }
  return { label: item.label, description };
};

export const MonacoEditor = forwardRef<MonacoEditorHandle, MonacoEditorProps>(
  ({
    value,
    onChange,
    language = 'sql',
    readOnly = false,
    height = '100%',
    diagnostics = [],
    completionContext,
  }, ref) => {

    const { theme: appTheme } = useTheme();
    const { t } = useTranslation();
    const translateRef = useRef(t);
    translateRef.current = t;
    const editorRef = useRef<any>(null);
    const monacoRef = useRef<any>(null);
    const completionContextRef = useRef<SqlCompletionContext | undefined>(completionContext);
    completionContextRef.current = completionContext;
    const workspace = useAuthStore(state => `${state.user?.id ?? ''}:${state.workspaceType}:${state.workspaceOrgId ?? ''}`);
    const binding = JSON.stringify([workspace, completionContext?.connectionId, completionContext?.databaseName, completionContext?.schemaName]);
    const bindingRef = useRef(binding);
    bindingRef.current = binding;
    const sessionRef = useRef<SqlCompletionSession | null>(null);
    const dictionaryRef = useRef<{ binding: string; dictionary: SqlCompletionDictionary } | null>(null);

    useEffect(() => {
      sessionRef.current?.reset();
      dictionaryRef.current = null;
      const context = completionContextRef.current;
      const connectionId = context?.connectionId;
      const dictionary = connectionId ? new SqlCompletionDictionary(
        (resolveEnvironment, signal) => sqlCompletionService.dictionary(connectionId, signal,
          resolveEnvironment ? { ...context, resolveEnvironment: true } : undefined),
        () => { if (bindingRef.current === binding) sessionRef.current?.reset(); },
      ) : null;
      if (dictionary) dictionaryRef.current = { binding, dictionary };
      const invalidate = (event: Event) => {
        const detail = (event as CustomEvent<{ connectionId?: number }>).detail;
        if (detail?.connectionId !== connectionId || !connectionId || !dictionary) return;
        dictionary.invalidate();
        sessionRef.current?.reset();
        void sqlCompletionService.invalidate(connectionId).then(() => {
          if (bindingRef.current !== binding || dictionaryRef.current?.dictionary !== dictionary) return;
          // Fence completions started while server-side DDL invalidation was pending.
          dictionary.invalidate();
          sessionRef.current?.reset();
          return dictionary.refresh();
        }).catch(() => {});
      };
      window.addEventListener(EXPLORER_SQL_OBJECT_CHANGED, invalidate);
      return () => {
        dictionary?.dispose();
        sessionRef.current?.reset();
        window.removeEventListener(EXPLORER_SQL_OBJECT_CHANGED, invalidate);
      };
    }, [binding]);

    useEffect(() => {
      const model = editorRef.current?.getModel();
      if (!model || !monacoRef.current) return;
      monacoRef.current.editor.setModelMarkers(model, 'dm-sql-parser', diagnostics.map(error => ({
        startLineNumber: error.line,
        startColumn: error.column + 1,
        endLineNumber: error.line,
        endColumn: error.column + 2,
        message: error.message,
        severity: monacoRef.current.MarkerSeverity.Error,
      })));
    }, [diagnostics, value]);

    const handleEditorWillMount = (monaco: any) => {
      // Define JetBrains-like dark theme
      monaco.editor.defineTheme('jetbrains-dark', {
        base: 'vs-dark',
        inherit: true,
        rules: [
          { token: 'keyword', foreground: 'cc7832', fontStyle: 'bold' },
          { token: 'string', foreground: '6a8759' },
          { token: 'function', foreground: 'ffc66d' },
          { token: 'number', foreground: '6897bb' },
          { token: 'comment', foreground: '808080', fontStyle: 'italic' },
          { token: 'operator', foreground: 'a9b7c6' },
          { token: 'delimiter', foreground: 'a9b7c6' },
        ],
        colors: {
          'editor.background': '#1e1f22',
          'editor.foreground': '#bcbec4',
          'editor.lineHighlightBackground': '#2e2f33',
          'editor.selectionBackground': '#2e436e',
          'editorCursor.foreground': '#a9b7c6',
          'editorLineNumber.foreground': '#606366',
          'editorLineNumber.activeForeground': '#a9b7c6',
          'editorIndentGuide.background': '#373737',
          'editor.border': '#4e5155',
        }
      });

      // Define JetBrains-like light theme
      monaco.editor.defineTheme('jetbrains-light', {
        base: 'vs',
        inherit: true,
        rules: [
          { token: 'keyword', foreground: 'd81e1e', fontStyle: 'bold' },
          { token: 'string', foreground: '067d17' },
          { token: 'function', foreground: 'be860b' },
          { token: 'number', foreground: '0b5394' },
          { token: 'comment', foreground: '8c8c8c', fontStyle: 'italic' },
          { token: 'operator', foreground: '001080' },
          { token: 'delimiter', foreground: '001080' },
        ],
        colors: {
          'editor.background': '#ffffff',
          'editor.foreground': '#000000',
          'editor.lineHighlightBackground': '#f5f5f5',
          'editor.selectionBackground': '#add6ff',
          'editorCursor.foreground': '#000000',
          'editorLineNumber.foreground': '#999999',
          'editorLineNumber.activeForeground': '#000000',
          'editorIndentGuide.background': '#e0e0e0',
          'editor.border': '#d0d0d0',
        }
      });
    };

    const getCurrentTheme = () => {
      return appTheme === 'light' ? 'jetbrains-light' : 'jetbrains-dark';
    };

    const completionDisposableRef = useRef<{ dispose(): void } | null>(null);

    useEffect(() => {
      return () => {
        completionDisposableRef.current?.dispose();
        completionDisposableRef.current = null;
      };
    }, []);

    const registerLanguageProviders = (monaco: any) => {
      completionDisposableRef.current?.dispose();
      const session = new SqlCompletionSession(sqlCompletionService.complete);
      sessionRef.current = session;
      const suggestions = (model: any, items: SqlCompletionItem[]) => items.map((item, index) => {
        const start = model.getPositionAt(item.replaceStart);
        const end = model.getPositionAt(item.replaceEnd);
        const typeLabel = translateRef.current(`sql_completion.types.${COMPLETION_TYPE_KEYS[item.kind]}`);
        const templateDescription = item.kind === 'SNIPPET'
          ? translateRef.current(`sql_completion.templates.${item.label}`, {
            defaultValue: item.documentation ?? item.detail ?? typeLabel,
          })
          : undefined;
        return {
          label: completionLabel(item, templateDescription ?? typeLabel),
          kind: monaco.languages.CompletionItemKind[COMPLETION_KIND_MAP[item.kind]] ?? monaco.languages.CompletionItemKind.Text,
          insertText: item.insertText,
          insertTextRules: item.snippet ? monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet : undefined,
          detail: item.kind === 'SNIPPET' ? item.label : item.detail ?? undefined,
          documentation: templateDescription ?? item.documentation ?? undefined,
          filterText: item.filterText,
          range: new monaco.Range(start.lineNumber, start.column, end.lineNumber, end.column),
          sortText: completionSortText(item, index),
          command: item.kind === 'SNIPPET' ? { id: 'editor.action.triggerSuggest', title: 'Suggest table' } : undefined,
        };
      });
      const provider = monaco.languages.registerCompletionItemProvider('sql', {
        triggerCharacters: ['.', '"', ' ', ',', '('],
        provideCompletionItems: async (model: any, position: any, _context: any, token: any) => {
          const ctx = completionContextRef.current;
          if (!ctx?.connectionId || model !== editorRef.current?.getModel()) return { suggestions: [] };
          const requestBinding = bindingRef.current;
          const dictionary = dictionaryRef.current;
          const version = model.getVersionId();
          const current = () => {
            const cursor = editorRef.current?.getPosition();
            return bindingRef.current === requestBinding && dictionaryRef.current === dictionary && !model.isDisposed()
              && model === editorRef.current?.getModel() && model.getVersionId() === version
              && cursor?.lineNumber === position.lineNumber && cursor?.column === position.column;
          };
          if (dictionary?.binding === requestBinding) await dictionary.dictionary.ready;
          if (token.isCancellationRequested || !current()) return { suggestions: [] };
          const sql = model.getValue();
          const cursorOffset = model.getOffsetAt(position);
          if (dictionary?.binding === requestBinding) void dictionary.dictionary.refresh();
          const local = dictionary?.binding === requestBinding
            ? staticCompletions(dictionary.dictionary.items, sql, cursorOffset) : { items: [], incomplete: false };
          const result = await session.progressive({
            connectionId: ctx.connectionId, databaseName: ctx.databaseName, schemaName: ctx.schemaName, sql, cursorOffset,
          }, token, current, `${requestBinding}:${model.uri.toString()}`, local, () => {
            if (editorRef.current?.hasTextFocus()) editorRef.current.trigger('sql-completion', 'editor.action.triggerSuggest', {});
          });
          if (token.isCancellationRequested || !current()) return { suggestions: [] };
          return { suggestions: suggestions(model, result.items), incomplete: result.incomplete };
        },
      });
      const languageParams = (model: any, position: any) => {
        const ctx = completionContextRef.current;
        if (!ctx?.connectionId || model !== editorRef.current?.getModel()) return null;
        return {
          connectionId: ctx.connectionId, databaseName: ctx.databaseName, schemaName: ctx.schemaName,
          sql: model.getValue(), cursorOffset: model.getOffsetAt(position),
        };
      };
      const hoverProvider = monaco.languages.registerHoverProvider('sql', {
        provideHover: async (model: any, position: any, token: any) => {
          const params = languageParams(model, position);
          if (!params) return null;
          const requestBinding = bindingRef.current;
          const controller = new AbortController();
          const subscription = token.onCancellationRequested(() => controller.abort());
          try {
            const result = await sqlCompletionService.hover(params, controller.signal);
            if (token.isCancellationRequested || bindingRef.current !== requestBinding || model.isDisposed()
              || model !== editorRef.current?.getModel() || !result.markdown) return null;
            const start = model.getPositionAt(result.rangeStart);
            const end = model.getPositionAt(result.rangeEnd);
            return {
              contents: [{ value: result.markdown }],
              range: new monaco.Range(start.lineNumber, start.column, end.lineNumber, end.column),
            };
          } catch { return null; } finally { subscription.dispose(); }
        },
      });
      const signatureProvider = monaco.languages.registerSignatureHelpProvider('sql', {
        signatureHelpTriggerCharacters: ['(', ','],
        provideSignatureHelp: async (model: any, position: any, token: any) => {
          const params = languageParams(model, position);
          if (!params) return null;
          const requestBinding = bindingRef.current;
          const controller = new AbortController();
          const subscription = token.onCancellationRequested(() => controller.abort());
          try {
            const result = await sqlCompletionService.signature(params, controller.signal);
            if (token.isCancellationRequested || bindingRef.current !== requestBinding || model.isDisposed()
              || model !== editorRef.current?.getModel() || !result.signatures.length) return null;
            return {
              value: {
                signatures: result.signatures.map(signature => {
                  const spans = parameterSpans(signature);
                  return {
                    label: signature.label,
                    parameters: signature.parameters.map((parameter, index) => ({ label: spans ? spans[index] : parameter.label })),
                  };
                }),
                activeSignature: result.activeSignature,
                activeParameter: result.activeParameter,
              },
              dispose() { },
            };
          } catch { return null; } finally { subscription.dispose(); }
        },
      });
      completionDisposableRef.current = { dispose() { session.reset(); provider.dispose(); hoverProvider.dispose(); signatureProvider.dispose(); } };

    };

    useImperativeHandle(ref, () => ({
      getSelectionOrAllContent(): string {
        const editor = editorRef.current;
        if (!editor) return '';
        const selection = editor.getSelection();
        if (selection && !selection.isEmpty()) {
          return editor.getModel()?.getValueInRange(selection) ?? '';
        }
        return editor.getValue();
      },
      getSelectionOrCursorInput(): { sql: string; cursorOffset?: number } {
        const editor = editorRef.current;
        const model = editor?.getModel();
        if (!editor || !model) return { sql: '' };
        const selection = editor.getSelection();
        if (selection && !selection.isEmpty()) return { sql: model.getValueInRange(selection) };
        const position = editor.getPosition();
        return { sql: model.getValue(), cursorOffset: position ? model.getOffsetAt(position) : 0 };
      },
      setValue(newValue: string): void {
        if (editorRef.current) {
          editorRef.current.setValue(newValue);
        }
      }
    }), []);

    return (
      <Editor
        className="sql-completion-editor"
        height={height}
        language={language}
        value={value}
        theme={getCurrentTheme()}
        onChange={onChange}
        beforeMount={handleEditorWillMount}
        onMount={(editor, monaco) => {
          editorRef.current = editor;
          monacoRef.current = monaco;
          // The pinned 0.43 runtime accepts a boolean; installed 0.55 typings use a string.
          editorRef.current.updateOptions({ wordBasedSuggestions: false });
          registerLanguageProviders(monaco);
          monaco.editor.setModelMarkers(editor.getModel(), 'dm-sql-parser', diagnostics.map(error => ({
            startLineNumber: error.line,
            startColumn: error.column + 1,
            endLineNumber: error.line,
            endColumn: error.column + 2,
            message: error.message,
            severity: monaco.MarkerSeverity.Error,
          })));
        }}
        options={{
          readOnly,
          suggest: { showInlineDetails: true },
          minimap: { enabled: false },
          fontSize: 13,
          fontFamily: "'JetBrains Mono', monospace",
          lineHeight: 1.5,
          scrollBeyondLastLine: false,
          automaticLayout: true,
          padding: { top: 10, bottom: 10 },
          renderLineHighlight: 'all',
          scrollbar: {
            vertical: 'visible',
            horizontal: 'visible',
            verticalScrollbarSize: 10,
            horizontalScrollbarSize: 10,
          }
        }}
      />
    );
  }
);

MonacoEditor.displayName = 'MonacoEditor';
