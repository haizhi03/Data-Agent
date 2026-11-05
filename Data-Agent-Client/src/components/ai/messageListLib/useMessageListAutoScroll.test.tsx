// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useMessageListAutoScroll } from './useMessageListAutoScroll';

function Chat({ user = 'first', text = '' }: { user?: string; text?: string }) {
  const scroll = useMessageListAutoScroll(user);
  return <div data-testid="viewport" ref={scroll.viewportRef} tabIndex={0}
    onScroll={scroll.onScroll} onWheel={scroll.onWheel} onKeyDown={scroll.onKeyDown}
    onPointerDown={scroll.onPointerDown} onTouchStart={scroll.onTouchStart} onTouchMove={scroll.onTouchMove}>
    <div ref={scroll.contentRef}>{text}</div>
  </div>;
}

let height: number;
let viewportHeight: number;
let frameId: number;
let frames: Map<number, FrameRequestCallback>;
let observers: { callback: () => void; observe: ReturnType<typeof vi.fn>; disconnect: ReturnType<typeof vi.fn> }[];

beforeEach(() => {
  vi.stubGlobal('PointerEvent', MouseEvent);
  height = 1000; viewportHeight = 200; frameId = 0; frames = new Map(); observers = [];
  vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => {
    const id = ++frameId; frames.set(id, callback); return id;
  }));
  vi.stubGlobal('cancelAnimationFrame', vi.fn((id: number) => { frames.delete(id); }));
  vi.stubGlobal('ResizeObserver', class {
    observe = vi.fn(); disconnect = vi.fn();
    constructor(public callback: () => void) { observers.push(this); }
  });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function viewport() { return screen.getByTestId('viewport'); }
function geometry() {
  Object.defineProperties(viewport(), {
    scrollHeight: { configurable: true, get: () => height },
    clientHeight: { configurable: true, get: () => viewportHeight },
  });
}
function frame() {
  act(() => { const callbacks = [...frames.values()]; frames.clear(); callbacks.forEach(callback => callback(0)); });
}
function resize() { act(() => { observers.forEach(observer => observer.callback()); }); }
function scrollTo(top: number) { viewport().scrollTop = top; fireEvent.scroll(viewport()); }
function readEarlier(top: number) { fireEvent.wheel(viewport(), { deltaY: -100 }); scrollTo(top); }

it('starts at the bottom and follows streamed chunks without treating content growth as user scrolling', () => {
  const view = render(<Chat text="first chunk" />); geometry(); frame();
  expect(viewport().scrollTop).toBe(800);
  height = 1200;
  view.rerender(<Chat text="first chunk plus more text" />);
  // A layout-generated scroll event may precede the scheduled animation frame.
  fireEvent.scroll(viewport()); frame();
  expect(viewport().scrollTop).toBe(1000);
});

it('keeps the reading position while scrolled up, then follows again after reaching the bottom', () => {
  const view = render(<Chat />); geometry(); frame();
  readEarlier(300);
  height = 1200; view.rerender(<Chat text="more text" />); resize(); frame();
  expect(viewport().scrollTop).toBe(300);
  scrollTo(1000);
  height = 1400; view.rerender(<Chat text="another chunk" />); frame();
  expect(viewport().scrollTop).toBe(1200);
});

it('sending a new user message resumes following even when viewing earlier messages', () => {
  const view = render(<Chat />); geometry(); frame();
  readEarlier(100);
  height = 1400; view.rerender(<Chat user="second" text="new question" />); frame();
  expect(viewport().scrollTop).toBe(1200);
});

it('an upward wheel gesture prevents an already queued scroll from pulling the reader back down', () => {
  const view = render(<Chat />); geometry(); frame();
  height = 1400; view.rerender(<Chat text="new chunk" />);
  fireEvent.wheel(viewport(), { deltaY: -100 }); scrollTo(700); frame();
  expect(viewport().scrollTop).toBe(700);
});

it('follows delayed table or diagram layout and viewport resizing while preserving a paused position', () => {
  render(<Chat />); geometry(); frame();
  height = 1400; resize(); frame();
  expect(viewport().scrollTop).toBe(1200);
  viewportHeight = 100; resize(); frame();
  expect(viewport().scrollTop).toBe(1300);
  readEarlier(500);
  height = 1800; resize(); frame();
  expect(viewport().scrollTop).toBe(500);
});

it('keeps following when a completed tool card collapses and the browser clamps scrollTop', () => {
  const view = render(<Chat />); geometry(); frame();
  height = 700; scrollTo(500);
  resize(); frame();
  height = 1100; view.rerender(<Chat text="answer after tool completion" />); frame();
  expect(viewport().scrollTop).toBe(900);
});

it('does not mistake a temporary Markdown reflow away from the bottom for a reader scrolling up', () => {
  const view = render(<Chat />); geometry(); frame();
  // A partially rendered table shrinks and then grows again before the next paint.
  height = 700; viewport().scrollTop = 500;
  height = 1300; fireEvent.scroll(viewport());
  view.rerender(<Chat text="next table row" />); frame();
  expect(viewport().scrollTop).toBe(1100);
});

it.each(['keyboard', 'touch', 'scrollbar'])('pauses for explicit %s interaction', gesture => {
  const view = render(<Chat />); geometry(); frame();
  if (gesture === 'keyboard') fireEvent.keyDown(viewport(), { key: 'PageUp' });
  if (gesture === 'touch') {
    fireEvent.touchStart(viewport(), { touches: [{ clientY: 100 }] });
    fireEvent.touchMove(viewport(), { touches: [{ clientY: 200 }] });
  }
  if (gesture === 'scrollbar') fireEvent.pointerDown(viewport(), { clientX: 0 });
  scrollTo(300);
  height = 1400; view.rerender(<Chat text="more rows" />); resize(); frame();
  expect(viewport().scrollTop).toBe(300);
});

it('releases observers and pending animation frames when the message list closes', () => {
  const view = render(<Chat />); geometry();
  expect(frames.size).toBe(1);
  expect(observers[0].observe).toHaveBeenCalledTimes(2);
  view.unmount();
  expect(observers[0].disconnect).toHaveBeenCalledOnce();
  expect(frames.size).toBe(0);
});
