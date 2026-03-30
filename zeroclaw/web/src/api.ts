// Copyright (c) 2026 @Natfii. All rights reserved.

import type {
  GraphResponse,
  MemoryDetail,
  LeaderboardRow,
  MemoryStats,
  MemoryEvent,
} from "./types";

/** Base path for API calls, resolved relative to the /_app/ base. */
const API_BASE = "/_app/../api";

/** Maximum SSE reconnect delay in milliseconds. */
const MAX_BACKOFF_MS = 30_000;

/** Initial SSE reconnect delay in milliseconds. */
const INITIAL_BACKOFF_MS = 1_000;

/**
 * Fetch wrapper that throws on non-OK responses.
 * Returns parsed JSON of type T.
 */
async function apiFetch<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`);
  if (!res.ok) {
    throw new Error(`API ${path}: ${res.status} ${res.statusText}`);
  }
  return res.json() as Promise<T>;
}

/**
 * Fetch the memory graph (nodes without content, top-N links per node).
 * @param maxLinksPerNode - Server-side cap on links returned per node (default 5).
 */
export function fetchGraph(maxLinksPerNode?: number): Promise<GraphResponse> {
  const params = maxLinksPerNode != null ? `?maxLinksPerNode=${maxLinksPerNode}` : "";
  return apiFetch<GraphResponse>(`/memory/graph${params}`);
}

/**
 * Fetch full detail for a single memory entry.
 * @param id - The memory fact ID.
 */
export function fetchDetail(id: string): Promise<MemoryDetail> {
  return apiFetch<MemoryDetail>(`/memory/detail/${encodeURIComponent(id)}`);
}

/** Fetch the provider leaderboard rows. */
export function fetchLeaderboard(): Promise<LeaderboardRow[]> {
  return apiFetch<LeaderboardRow[]>("/memory/leaderboard");
}

/** Fetch aggregate memory stats. */
export function fetchStats(): Promise<MemoryStats> {
  return apiFetch<MemoryStats>("/memory/stats");
}

/**
 * Subscribe to real-time memory events via Server-Sent Events.
 *
 * Automatically reconnects with exponential backoff (1s -> 30s cap).
 * Pauses the connection when the tab becomes hidden and resumes on
 * visibility change to conserve battery.
 *
 * @param onEvent - Callback invoked for each parsed MemoryEvent.
 * @returns The initial EventSource instance (may be replaced internally on reconnect).
 */
export function subscribeSSE(
  onEvent: (event: MemoryEvent) => void,
): EventSource {
  let source: EventSource | null = null;
  let backoff = INITIAL_BACKOFF_MS;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let disposed = false;

  function connect(): EventSource {
    const es = new EventSource(`${API_BASE}/memory/events`);

    es.onmessage = (msg) => {
      backoff = INITIAL_BACKOFF_MS;
      try {
        const parsed = JSON.parse(msg.data) as MemoryEvent;
        onEvent(parsed);
      } catch {
        console.warn("[SSE] Failed to parse event:", msg.data);
      }
    };

    es.onerror = () => {
      es.close();
      if (disposed) return;

      reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        if (!disposed && document.visibilityState !== "hidden") {
          source = connect();
        }
      }, backoff);

      backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
    };

    return es;
  }

  /** Pause SSE when the tab is hidden, reconnect when visible. */
  function onVisibilityChange(): void {
    if (disposed) return;

    if (document.visibilityState === "hidden") {
      if (reconnectTimer != null) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      source?.close();
      source = null;
    } else {
      if (!source) {
        backoff = INITIAL_BACKOFF_MS;
        source = connect();
      }
    }
  }

  document.addEventListener("visibilitychange", onVisibilityChange);

  source = connect();

  /** Expose a close method on the returned EventSource for cleanup. */
  const original = source;
  const origClose = original.close.bind(original);
  original.close = () => {
    disposed = true;
    document.removeEventListener("visibilitychange", onVisibilityChange);
    if (reconnectTimer != null) clearTimeout(reconnectTimer);
    source?.close();
    origClose();
  };

  return original;
}
