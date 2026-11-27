import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useReactFlow } from '@xyflow/react';
import type { ErTableNodeType } from './ErDiagramNode';
import {
  CARD_WIDTH,
  ER_MIN_ZOOM,
  cardHeight,
  layoutBounds,
  minZoomForCanvas,
  panExtentForDiagram,
  viewportForDiagram,
} from './erDiagramLayout';

export function useErDiagramViewport(
  nodes: ErTableNodeType[],
  layouting: boolean,
  showCanvas: boolean,
) {
  const { getNodes, setViewport } = useReactFlow();
  const flowRef = useRef<HTMLDivElement>(null);
  const framedToken = useRef(-1);
  /** After leaving "only these tables", fit this subset in the restored layout. */
  const frameFocusRef = useRef<Set<string> | null>(null);
  const [layoutVersion, setLayoutVersion] = useState(0);
  const [frameReady, setFrameReady] = useState(false);
  const [paneSize, setPaneSize] = useState({ width: 0, height: 0 });

  const requestFrame = useCallback(() => {
    setLayoutVersion((version) => version + 1);
  }, []);

  const frameNodes = useCallback((current: ErTableNodeType[]) => {
    const pane = flowRef.current;
    if (!pane || current.length === 0) return Promise.resolve(false);
    const focus = frameFocusRef.current;
    const focused = focus && focus.size > 0 ? current.filter((node) => focus.has(node.id)) : current;
    const cards = focused.length > 0 ? focused : current;
    const bounds = layoutBounds(cards.map((node) => ({
      x: node.position.x,
      y: node.position.y,
      width: node.measured?.width || node.width || CARD_WIDTH,
      height: node.measured?.height || node.height || cardHeight(node.data.table.columns.length, node.data.expanded),
    })));
    const viewport = viewportForDiagram(bounds, pane.clientWidth, pane.clientHeight);
    if (!viewport) return Promise.resolve(false);
    return setViewport(viewport, { duration: 0 }).then((applied) => applied, () => false);
  }, [setViewport]);

  const fitToWindow = useCallback(() => {
    void frameNodes(getNodes() as ErTableNodeType[]);
  }, [frameNodes, getNodes]);

  useLayoutEffect(() => {
    if (layouting || nodes.length === 0 || framedToken.current === layoutVersion) return;
    setFrameReady(false);
    let stopped = false;
    let attempts = 0;
    let frame = 0;
    const apply = () => {
      if (stopped || framedToken.current === layoutVersion) return;
      void frameNodes(nodes).then((fitted) => {
        if (stopped || framedToken.current === layoutVersion) return;
        if (fitted) {
          framedToken.current = layoutVersion;
          frameFocusRef.current = null;
          setFrameReady(true);
          return;
        }
        attempts += 1;
        if (attempts < 8) frame = requestAnimationFrame(apply);
      });
    };
    apply();
    const pane = flowRef.current;
    const observer = pane ? new ResizeObserver(() => apply()) : null;
    if (pane && observer) observer.observe(pane);
    return () => {
      stopped = true;
      cancelAnimationFrame(frame);
      observer?.disconnect();
    };
  }, [frameNodes, layoutVersion, layouting, nodes]);

  useLayoutEffect(() => {
    const pane = flowRef.current;
    if (!pane) return;
    const publish = () => {
      const width = pane.clientWidth;
      const height = pane.clientHeight;
      setPaneSize((current) => (current.width === width && current.height === height ? current : { width, height }));
    };
    publish();
    const observer = new ResizeObserver(publish);
    observer.observe(pane);
    return () => observer.disconnect();
  }, [showCanvas]);

  const canvasBounds = useMemo(() => {
    if (nodes.length === 0) return null;
    return layoutBounds(nodes.map((node) => ({
      x: node.position.x,
      y: node.position.y,
      width: node.measured?.width || node.width || CARD_WIDTH,
      height: node.measured?.height || node.height || cardHeight(node.data.table.columns.length, node.data.expanded),
    })));
  }, [nodes]);
  const translateExtent = useMemo(() => (
    canvasBounds ? panExtentForDiagram(canvasBounds, paneSize.width, paneSize.height) ?? undefined : undefined
  ), [canvasBounds, paneSize.height, paneSize.width]);
  const minZoom = translateExtent
    ? minZoomForCanvas(translateExtent, paneSize.width, paneSize.height)
    : ER_MIN_ZOOM;

  return {
    flowRef,
    frameFocusRef,
    frameReady,
    minZoom,
    translateExtent,
    fitToWindow,
    requestFrame,
  };
}
