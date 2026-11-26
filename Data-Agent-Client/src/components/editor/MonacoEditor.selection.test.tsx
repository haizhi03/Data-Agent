// @vitest-environment jsdom
import { act, cleanup, render } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MonacoEditor } from './MonacoEditor';

const fixture = vi.hoisted(() => ({
  content: 'SELECT 1;', start: 0, end: 0,
  selection: () => {}, contentChanged: () => {},
  disposeSelection: vi.fn(), disposeContent: vi.fn(),
}));
vi.mock('monaco-editor', () => ({}));
vi.mock('monaco-editor/esm/vs/editor/editor.worker?worker', () => ({ default: class {} }));
vi.mock('../../hooks/useTheme', () => ({ useTheme: () => ({ theme: 'light' }) }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
vi.mock('@monaco-editor/react', async () => {
  const React = await import('react');
  return {
    loader: { config: vi.fn() },
    default: ({ onMount }: any) => {
      React.useEffect(() => {
        const model = { getValue: () => fixture.content, getOffsetAt: (position: any) => position.column - 1 };
        const editor = {
          getModel: () => model,
          getSelection: () => ({ getStartPosition: () => ({ column: fixture.start + 1 }), getEndPosition: () => ({ column: fixture.end + 1 }) }),
          updateOptions: vi.fn(),
          onDidChangeCursorSelection: (callback: () => void) => { fixture.selection = callback; return { dispose: fixture.disposeSelection }; },
          onDidChangeModelContent: (callback: () => void) => { fixture.contentChanged = callback; return { dispose: fixture.disposeContent }; },
        };
        const provider = () => ({ dispose: vi.fn() });
        onMount(editor, { editor: { setModelMarkers: vi.fn() }, MarkerSeverity: { Error: 8 }, languages: {
          registerCompletionItemProvider: provider, registerHoverProvider: provider, registerSignatureHelpProvider: provider,
        } });
      }, []);
      return null;
    },
  };
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

it('publishes live model selections to the latest tab callback and disposes listeners', () => {
  const first = vi.fn(), second = vi.fn();
  const view = render(<MonacoEditor value="SELECT 1;" onSelectionChange={first} />);
  expect(first).toHaveBeenLastCalledWith('SELECT 1;', 0, 0);
  fixture.end = 6;
  act(() => fixture.selection());
  expect(first).toHaveBeenLastCalledWith('SELECT 1;', 0, 6);
  view.rerender(<MonacoEditor value="SELECT 2;" completionContext={{ connectionId: 2 }} onSelectionChange={second} />);
  fixture.content = 'SELECT 2;';
  act(() => fixture.contentChanged());
  expect(second).toHaveBeenLastCalledWith('SELECT 2;', 0, 6);
  expect(fixture.disposeSelection).not.toHaveBeenCalled();
  view.unmount();
  expect(fixture.disposeSelection).toHaveBeenCalledTimes(1);
  expect(fixture.disposeContent).toHaveBeenCalledTimes(1);
});
