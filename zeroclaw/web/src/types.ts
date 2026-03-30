// Copyright (c) 2026 @Natfii. All rights reserved.

/** A memory fact node in the Brain Visualizer graph. */
export interface FactNode {
  id: string;
  key: string;
  category: "core" | "daily" | "conversation" | string;
  source: "heuristic" | "llm" | "user" | "agent" | string;
  tags: string;
  score: number;
  display_score: number;
  access_count: number;
  last_accessed_at: string | null;
}

/** A similarity link between two memory facts. */
export interface SynapseLink {
  source_id: string;
  target_id: string;
  similarity: number;
}

/** Response from GET /api/memory/graph. */
export interface GraphResponse {
  nodes: FactNode[];
  links: SynapseLink[];
}

/** Full memory entry from GET /api/memory/detail/{id}. */
export interface MemoryDetail {
  id: string;
  key: string;
  content: string;
  category: string;
  timestamp: string;
  confidence: number;
  source: string;
  tags: string;
  access_count: number;
  last_accessed_at: string | null;
}

/** Provider leaderboard row from GET /api/memory/leaderboard. */
export interface LeaderboardRow {
  provider: string;
  model: string;
  total: number;
  successRate: number;
  avgLatency: number;
}

/** Memory stats from GET /api/memory/stats. */
export interface MemoryStats {
  total_facts: number;
  categories: Record<string, number>;
  backlog_count: number;
}

/** SSE memory event. */
export interface MemoryEvent {
  type: string;
  data: Record<string, unknown>;
  timestamp: string;
}

/** Power save state injected by native bridge. */
declare global {
  interface Window {
    __ZERO_POWER_SAVE?: boolean;
  }
}
