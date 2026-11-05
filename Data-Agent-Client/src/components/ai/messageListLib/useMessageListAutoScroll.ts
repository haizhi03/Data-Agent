import { useCallback, useLayoutEffect, useRef } from 'react';
import type { KeyboardEvent, PointerEvent, TouchEvent, UIEvent, WheelEvent } from 'react';

/** Follow streaming content until the reader scrolls up; sending a new message resumes following. */
export function useMessageListAutoScroll(latestUserMessageId: string | undefined) {
  const viewportRef = useRef<HTMLDivElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const following = useRef(true);
  const pendingFrame = useRef<number>();
  const touchY = useRef<number>();

  const scheduleScroll = useCallback(() => {
    if (!following.current || pendingFrame.current !== undefined) return;
    pendingFrame.current = window.requestAnimationFrame(() => {
      pendingFrame.current = undefined;
      const viewport = viewportRef.current;
      if (!viewport || !following.current) return;
      viewport.scrollTop = Math.max(0, viewport.scrollHeight - viewport.clientHeight);
    });
  }, []);

  useLayoutEffect(() => {
    following.current = true;
    scheduleScroll();
  }, [latestUserMessageId, scheduleScroll]);

  // Includes streamed text/tool updates and changes to loading or approval cards.
  useLayoutEffect(() => { scheduleScroll(); });

  useLayoutEffect(() => {
    const viewport = viewportRef.current;
    const content = contentRef.current;
    // Markdown tables, diagrams and images can finish laying out without a React update.
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(scheduleScroll);
    if (viewport) observer?.observe(viewport);
    if (content) observer?.observe(content);
    return () => {
      observer?.disconnect();
      if (pendingFrame.current !== undefined) window.cancelAnimationFrame(pendingFrame.current);
      pendingFrame.current = undefined;
    };
  }, [scheduleScroll]);

  const onScroll = (event: UIEvent<HTMLDivElement>) => {
    const viewport = event.currentTarget;
    if (viewport.scrollHeight - viewport.clientHeight - viewport.scrollTop <= 1) following.current = true;
    // A scroll event is also emitted by anchoring, clamping and Markdown/tool-card reflow.
    // Only explicit reader input may pause following, never a change in scrollTop alone.
    scheduleScroll();
  };
  const onWheel = (event: WheelEvent<HTMLDivElement>) => {
    if (event.deltaY < 0) following.current = false;
  };
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.target === event.currentTarget && (['ArrowUp', 'PageUp', 'Home'].includes(event.key)
      || (event.key === ' ' && event.shiftKey))) following.current = false;
  };
  const onPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    const viewport = event.currentTarget;
    const bounds = viewport.getBoundingClientRect();
    // Native/overlay scrollbar interaction. Clicking message text does not pause following.
    if (event.target === viewport && event.clientX >= bounds.right - Math.max(16, viewport.offsetWidth - viewport.clientWidth)) {
      following.current = false;
    }
  };
  const onTouchStart = (event: TouchEvent<HTMLDivElement>) => { touchY.current = event.touches[0]?.clientY; };
  const onTouchMove = (event: TouchEvent<HTMLDivElement>) => {
    const nextY = event.touches[0]?.clientY;
    if (nextY !== undefined && touchY.current !== undefined && nextY > touchY.current) following.current = false;
    touchY.current = nextY;
  };

  return { viewportRef, contentRef, onScroll, onWheel, onKeyDown, onPointerDown, onTouchStart, onTouchMove };
}
