// Copyright (c) 2026 @Natfii. All rights reserved.

import "./style.css";
import { fetchGraph, subscribeSSE } from "./api";
import { initGraph, addNode, updateGraph } from "./graph";
import type { ForceGraphInstance } from "./graph";
import { openDetailPanel } from "./panels/detail";
import { openLeaderboardPanel } from "./panels/leaderboard";
import { initFilterBar } from "./panels/filters";
import { initTableView, updateTableView } from "./panels/table";
import type { FactNode, GraphResponse, MemoryEvent } from "./types";

/** Whether power-save mode is active (from native bridge or CSS). */
function isPowerSave(): boolean {
  if (window.__ZERO_POWER_SAVE === true) return true;
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/** Whether a screen reader is likely active (heuristic). */
function isScreenReader(): boolean {
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/** Active graph instance, if in graph view mode. */
let graphInstance: ForceGraphInstance | null = null;

/** Current graph data (kept in sync for filtering and view switches). */
let currentData: GraphResponse = { nodes: [], links: [] };

/** Active SSE connection. */
let sseSource: EventSource | null = null;

/** Current view mode. */
let viewMode: "graph" | "table" = "graph";

/** Active filter state. */
let filterCategory: string | null = null;
let filterSource: string | null = null;
let filterSearch = "";

async function init(): Promise<void> {
  const app = document.getElementById("app");
  if (!app) return;

  /** Auth check. */
  try {
    const res = await fetch("/_app/../api/session");
    if (!res.ok) {
      app.textContent = "Not authenticated. Please pair with the app.";
      return;
    }
  } catch {
    app.textContent = "Cannot reach gateway.";
    return;
  }

  /** Determine initial view mode. */
  const savedView = localStorage.getItem("brain-view-mode");
  const defaultToTable = isScreenReader() || isPowerSave();
  viewMode = (savedView ?? (defaultToTable ? "table" : "graph")) as "graph" | "table";

  app.dataset.viewMode = viewMode;

  /** Build the skeleton layout. */
  const filterBar = document.createElement("div");
  filterBar.className = "filter-bar";
  filterBar.setAttribute("role", "toolbar");
  filterBar.setAttribute("aria-label", "Memory filters");
  app.appendChild(filterBar);

  const graphContainer = document.createElement("div");
  graphContainer.id = "graph-container";
  graphContainer.style.cssText = "width: 100%; height: 100%;";
  app.appendChild(graphContainer);

  /** Fetch initial graph data. */
  try {
    currentData = await fetchGraph(5);
  } catch (err) {
    graphContainer.textContent = "Failed to load memory graph.";
    console.error("[Brain] fetch error:", err);
    return;
  }

  /** Initialize the filter bar. */
  initFilterBar(onFilterChange, onViewToggle, viewMode);

  /** Render the initial view. */
  renderCurrentView(graphContainer);

  /** Listen for node:select events from both graph and table. */
  app.addEventListener("node:select", ((e: CustomEvent<FactNode>) => {
    openDetailPanel(e.detail);
  }) as EventListener);

  /** Set up SSE for live updates. */
  sseSource = subscribeSSE(handleSSEEvent);

  /** Keyboard shortcut: L for leaderboard. */
  document.addEventListener("keydown", (e: KeyboardEvent) => {
    if (e.key === "l" && !e.ctrlKey && !e.metaKey && !e.altKey) {
      const target = e.target as HTMLElement;
      if (target.tagName === "INPUT" || target.tagName === "SELECT") return;
      openLeaderboardPanel();
    }
  });

  console.log(
    "[Brain] initialized, powerSave:",
    isPowerSave(),
    "view:",
    viewMode,
    "nodes:",
    currentData.nodes.length,
  );
}

/** Render the current view (graph or table) into the container. */
function renderCurrentView(container: HTMLElement): void {
  container.textContent = "";
  const filtered = applyFilters(currentData);

  if (viewMode === "graph") {
    graphInstance = initGraph(container, filtered, isPowerSave());
  } else {
    graphInstance = null;
    initTableView(container, filtered);
  }
}

/** Apply current filters to the graph data. */
function applyFilters(data: GraphResponse): GraphResponse {
  let nodes = data.nodes;

  if (filterCategory) {
    nodes = nodes.filter((n) => n.category === filterCategory);
  }
  if (filterSource) {
    nodes = nodes.filter((n) => n.source === filterSource);
  }
  if (filterSearch) {
    const lower = filterSearch.toLowerCase();
    nodes = nodes.filter(
      (n) =>
        n.key.toLowerCase().includes(lower) ||
        n.tags.toLowerCase().includes(lower),
    );
  }

  /** Filter links to only include those between visible nodes. */
  const nodeIds = new Set(nodes.map((n) => n.id));
  const links = data.links.filter(
    (l) => nodeIds.has(l.source_id) && nodeIds.has(l.target_id),
  );

  return { nodes, links };
}

/** Handle filter bar changes. */
function onFilterChange(
  category: string | null,
  source: string | null,
  search: string,
): void {
  filterCategory = category;
  filterSource = source;
  filterSearch = search;

  const container = document.getElementById("graph-container");
  if (!container) return;

  if (viewMode === "graph" && graphInstance) {
    const filtered = applyFilters(currentData);
    updateGraph(graphInstance, filtered);
  } else if (viewMode === "table") {
    const filtered = applyFilters(currentData);
    updateTableView(container, filtered.nodes);
  }
}

/** Handle view toggle between graph and table. */
function onViewToggle(mode: "graph" | "table"): void {
  viewMode = mode;
  const app = document.getElementById("app");
  if (app) app.dataset.viewMode = mode;

  const container = document.getElementById("graph-container");
  if (!container) return;

  renderCurrentView(container);
}

/** Handle incoming SSE memory events. */
function handleSSEEvent(event: MemoryEvent): void {
  switch (event.type) {
    case "fact_created": {
      const node = event.data as unknown as FactNode;
      if (node && node.id) {
        currentData.nodes.push(node);
        if (viewMode === "graph" && graphInstance) {
          addNode(graphInstance, node);
        } else {
          const container = document.getElementById("graph-container");
          if (container) {
            const filtered = applyFilters(currentData);
            updateTableView(container, filtered.nodes);
          }
        }
      }
      break;
    }

    case "consolidation_complete": {
      /** Refresh the entire graph after consolidation. */
      fetchGraph(5)
        .then((data) => {
          currentData = data;
          const container = document.getElementById("graph-container");
          if (!container) return;
          if (viewMode === "graph" && graphInstance) {
            const filtered = applyFilters(currentData);
            updateGraph(graphInstance, filtered);
          } else {
            const filtered = applyFilters(currentData);
            updateTableView(container, filtered.nodes);
          }
        })
        .catch((err) => console.warn("[SSE] refresh error:", err));
      break;
    }

    default:
      console.log("[SSE] unhandled event:", event.type);
  }
}

init();
