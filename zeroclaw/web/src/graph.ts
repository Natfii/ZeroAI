// Copyright (c) 2026 @Natfii. All rights reserved.

import ForceGraph from "force-graph";
import type { NodeObject, LinkObject } from "force-graph";
import type { FactNode, SynapseLink, GraphResponse } from "./types";

/** Internal node type combining FactNode fields with force-graph positional fields. */
interface GraphNode extends NodeObject {
  id: string;
  key: string;
  category: string;
  source: string;
  tags: string;
  score: number;
  display_score: number;
  access_count: number;
  last_accessed_at: string | null;
}

/** Internal link type with resolved source/target references. */
interface GraphLink extends LinkObject<GraphNode> {
  similarity: number;
  source_id: string;
  target_id: string;
}

/** The force-graph instance type used throughout this module. */
export type ForceGraphInstance = ForceGraph<GraphNode, GraphLink>;

/** Idle timeout before pausing the simulation (milliseconds). */
const IDLE_TIMEOUT_MS = 30_000;

/** Minimum node radius in pixels (ensures 48px touch target with padding). */
const MIN_NODE_RADIUS = 12;

/** Maximum node radius in pixels. */
const MAX_NODE_RADIUS = 28;

/** Map source type to its CSS variable color. */
const SOURCE_COLORS: Record<string, string> = {
  heuristic: "#06b6d4",
  llm: "#a855f7",
  user: "#22c55e",
  agent: "#f59e0b",
};

/** Map source type to its glow color. */
const SOURCE_GLOWS: Record<string, string> = {
  heuristic: "rgba(6, 182, 212, 0.4)",
  llm: "rgba(168, 85, 247, 0.4)",
  user: "rgba(34, 197, 94, 0.4)",
  agent: "rgba(245, 158, 11, 0.4)",
};

/** Default fallback color for unknown sources. */
const FALLBACK_COLOR = "#64748b";
const FALLBACK_GLOW = "rgba(100, 116, 139, 0.3)";

/** Compute node radius from display_score, relative to the dataset. */
function computeRadius(score: number, maxScore: number): number {
  if (maxScore <= 0) return MIN_NODE_RADIUS;
  const normalized = score / maxScore;
  return MIN_NODE_RADIUS + normalized * (MAX_NODE_RADIUS - MIN_NODE_RADIUS);
}

/**
 * Initialise the force-graph renderer inside the given container.
 *
 * @param container - The DOM element to render into.
 * @param data - Initial graph data (nodes + links).
 * @param isPowerSave - Whether power-save mode is active.
 * @returns The force-graph instance.
 */
export function initGraph(
  container: HTMLElement,
  data: GraphResponse,
  isPowerSave: boolean,
): ForceGraphInstance {
  const maxScore = data.nodes.reduce(
    (max, n) => Math.max(max, n.display_score),
    0,
  );

  /** Transform API links into force-graph format with source/target as IDs. */
  const links: GraphLink[] = data.links.map((l: SynapseLink) => ({
    source: l.source_id,
    target: l.target_id,
    similarity: l.similarity,
    source_id: l.source_id,
    target_id: l.target_id,
  }));

  const nodes: GraphNode[] = data.nodes.map((n: FactNode) => ({
    ...n,
  }));

  const graph = new ForceGraph<GraphNode, GraphLink>(container);

  graph
    .graphData({ nodes, links })
    .nodeId("id")
    .linkSource("source")
    .linkTarget("target")
    .cooldownTicks(isPowerSave ? 100 : 200)
    .d3AlphaDecay(0.05)
    .d3VelocityDecay(0.3)
    .autoPauseRedraw(true)
    .linkDirectionalParticles(isPowerSave ? 0 : 2)
    .linkDirectionalParticleSpeed(0.005)
    .linkWidth((link: GraphLink) => Math.max(1, link.similarity * 4))
    .linkColor(() => "rgba(148, 163, 184, 0.25)")
    .backgroundColor("rgba(0,0,0,0)")
    .nodeCanvasObjectMode(() => "replace")
    .nodeCanvasObject((node: GraphNode, ctx: CanvasRenderingContext2D, globalScale: number) => {
      const r = computeRadius(node.display_score, maxScore);
      const color = SOURCE_COLORS[node.source] ?? FALLBACK_COLOR;
      const glow = SOURCE_GLOWS[node.source] ?? FALLBACK_GLOW;
      const x = node.x ?? 0;
      const y = node.y ?? 0;

      /** Outer glow. */
      ctx.beginPath();
      ctx.arc(x, y, r + 4, 0, 2 * Math.PI);
      ctx.fillStyle = glow;
      ctx.fill();

      /** Core circle. */
      ctx.beginPath();
      ctx.arc(x, y, r, 0, 2 * Math.PI);
      ctx.fillStyle = color;
      ctx.fill();

      /** Bright ring for core category nodes. */
      if (node.category === "core") {
        ctx.beginPath();
        ctx.arc(x, y, r + 2, 0, 2 * Math.PI);
        ctx.strokeStyle = color;
        ctx.lineWidth = 1.5;
        ctx.stroke();
      }

      /** Label (only at sufficient zoom). */
      const fontSize = Math.max(10, 12 / globalScale);
      if (globalScale > 0.6) {
        ctx.font = `${fontSize}px system-ui, sans-serif`;
        ctx.textAlign = "center";
        ctx.textBaseline = "top";
        ctx.fillStyle = "rgba(226, 232, 240, 0.9)";
        ctx.fillText(node.key, x, y + r + 4);
      }
    })
    .nodePointerAreaPaint((node: GraphNode, paintColor: string, ctx: CanvasRenderingContext2D) => {
      const r = computeRadius(node.display_score, maxScore);
      const x = node.x ?? 0;
      const y = node.y ?? 0;
      /** Expand hit area to 48px minimum diameter for touch targets. */
      const hitRadius = Math.max(r + 4, 24);
      ctx.beginPath();
      ctx.arc(x, y, hitRadius, 0, 2 * Math.PI);
      ctx.fillStyle = paintColor;
      ctx.fill();
    })
    .onNodeClick((node: GraphNode) => {
      container.dispatchEvent(
        new CustomEvent("node:select", { detail: node, bubbles: true }),
      );
    })
    .onBackgroundClick(() => {
      container.dispatchEvent(
        new CustomEvent("node:deselect", { bubbles: true }),
      );
    });

  /** Idle timeout: pause simulation after 30s of no interaction. */
  let idleTimer: ReturnType<typeof setTimeout> | null = null;

  function resetIdleTimer(): void {
    if (idleTimer != null) clearTimeout(idleTimer);
    graph.resumeAnimation();
    idleTimer = setTimeout(() => {
      graph.pauseAnimation();
    }, IDLE_TIMEOUT_MS);
  }

  container.addEventListener("pointerdown", resetIdleTimer, { passive: true });
  container.addEventListener("wheel", resetIdleTimer, { passive: true });
  container.addEventListener("touchstart", resetIdleTimer, { passive: true });

  /** Start the idle timer immediately. */
  resetIdleTimer();

  return graph;
}

/**
 * Add a new node to the live graph.
 * @param graph - The force-graph instance.
 * @param node - The FactNode to add.
 */
export function addNode(graph: ForceGraphInstance, node: FactNode): void {
  const { nodes, links } = graph.graphData();
  nodes.push({ ...node });
  graph.graphData({ nodes, links });
}

/**
 * Remove a node (and its connected links) from the live graph.
 * @param graph - The force-graph instance.
 * @param nodeId - The ID of the node to remove.
 */
export function removeNode(graph: ForceGraphInstance, nodeId: string): void {
  const { nodes, links } = graph.graphData();
  const filteredNodes = nodes.filter((n) => n.id !== nodeId);
  const filteredLinks = links.filter((l) => {
    const srcId = typeof l.source === "object" ? l.source?.id : l.source;
    const tgtId = typeof l.target === "object" ? l.target?.id : l.target;
    return srcId !== nodeId && tgtId !== nodeId;
  });
  graph.graphData({ nodes: filteredNodes, links: filteredLinks });
}

/**
 * Replace the entire graph dataset (e.g., after consolidation).
 * @param graph - The force-graph instance.
 * @param data - The new GraphResponse data.
 */
export function updateGraph(graph: ForceGraphInstance, data: GraphResponse): void {
  const links: GraphLink[] = data.links.map((l: SynapseLink) => ({
    source: l.source_id,
    target: l.target_id,
    similarity: l.similarity,
    source_id: l.source_id,
    target_id: l.target_id,
  }));

  const nodes: GraphNode[] = data.nodes.map((n: FactNode) => ({
    ...n,
  }));

  graph.graphData({ nodes, links });
}
