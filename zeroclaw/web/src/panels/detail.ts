// Copyright (c) 2026 @Natfii. All rights reserved.

import { fetchDetail } from "../api";
import type { FactNode, MemoryDetail } from "../types";

/** Source type to display color mapping. */
const SOURCE_BADGE_COLORS: Record<string, string> = {
  heuristic: "#06b6d4",
  llm: "#a855f7",
  user: "#22c55e",
  agent: "#f59e0b",
};

/** The currently open detail panel element, if any. */
let activePanel: HTMLElement | null = null;

/**
 * Open the node detail panel, sliding in from the right.
 * Lazy-fetches full content via the API.
 *
 * @param node - The FactNode whose detail to display.
 */
export function openDetailPanel(node: FactNode): void {
  closeDetailPanel();

  const panel = document.createElement("div");
  panel.className = "panel detail-panel";
  panel.setAttribute("role", "dialog");
  panel.setAttribute("aria-labelledby", "detail-title");
  panel.setAttribute("aria-describedby", "detail-content");
  panel.style.cssText = `
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    width: min(400px, 85vw);
    z-index: 200;
    overflow-y: auto;
    padding: 16px;
    transform: translateX(100%);
    transition: transform 0.25s ease-out;
    display: flex;
    flex-direction: column;
    gap: 12px;
  `;

  /** Close button (48x48dp minimum). */
  const closeBtn = document.createElement("button");
  closeBtn.className = "close-btn";
  closeBtn.setAttribute("aria-label", "Close detail panel");
  closeBtn.textContent = "\u2715";
  closeBtn.style.cssText = "align-self: flex-end;";
  closeBtn.addEventListener("click", closeDetailPanel);
  panel.appendChild(closeBtn);

  /** Title (key). */
  const title = document.createElement("h2");
  title.id = "detail-title";
  title.textContent = node.key;
  title.style.cssText = `
    font-size: 18px;
    font-weight: 600;
    margin: 0;
    color: var(--text-primary);
  `;
  panel.appendChild(title);

  /** Source badge. */
  const badge = document.createElement("span");
  badge.textContent = node.source;
  const badgeColor = SOURCE_BADGE_COLORS[node.source] ?? "#64748b";
  badge.style.cssText = `
    display: inline-block;
    padding: 2px 10px;
    border-radius: 999px;
    font-size: 12px;
    font-weight: 600;
    color: #fff;
    background: ${badgeColor};
    width: fit-content;
  `;
  panel.appendChild(badge);

  /** Content area (lazy-loaded). */
  const contentArea = document.createElement("div");
  contentArea.id = "detail-content";
  contentArea.setAttribute("aria-live", "polite");
  contentArea.style.cssText = `
    font-size: 14px;
    line-height: 1.6;
    color: var(--text-secondary);
    min-height: 60px;
  `;
  contentArea.textContent = "Loading...";
  panel.appendChild(contentArea);

  /** Metadata section placeholder. */
  const metaSection = document.createElement("div");
  metaSection.style.cssText = `
    display: flex;
    flex-direction: column;
    gap: 8px;
    font-size: 13px;
    color: var(--text-secondary);
    border-top: 1px solid var(--border-subtle);
    padding-top: 12px;
    margin-top: 4px;
  `;
  panel.appendChild(metaSection);

  /** Tags area. */
  const tagsContainer = document.createElement("div");
  tagsContainer.style.cssText = "display: flex; flex-wrap: wrap; gap: 6px;";
  if (node.tags) {
    for (const tag of node.tags.split(",")) {
      const trimmed = tag.trim();
      if (!trimmed) continue;
      const pill = document.createElement("span");
      pill.textContent = trimmed;
      pill.style.cssText = `
        display: inline-block;
        padding: 2px 8px;
        border-radius: 999px;
        font-size: 11px;
        background: var(--border-subtle);
        color: var(--text-primary);
      `;
      tagsContainer.appendChild(pill);
    }
  }
  panel.appendChild(tagsContainer);

  activePanel = panel;
  document.body.appendChild(panel);

  /** Trigger slide-in animation on next frame. */
  requestAnimationFrame(() => {
    panel.style.transform = "translateX(0)";
  });

  /** Tap-away to dismiss. */
  const backdrop = document.createElement("div");
  backdrop.style.cssText = `
    position: fixed;
    inset: 0;
    z-index: 199;
    background: rgba(0, 0, 0, 0.3);
  `;
  backdrop.addEventListener("click", closeDetailPanel);
  panel.dataset.backdropId = "detail-backdrop";
  backdrop.id = "detail-backdrop";
  document.body.appendChild(backdrop);

  /** Swipe-right to dismiss. */
  let touchStartX = 0;
  panel.addEventListener(
    "touchstart",
    (e: TouchEvent) => {
      touchStartX = e.touches[0].clientX;
    },
    { passive: true },
  );
  panel.addEventListener(
    "touchend",
    (e: TouchEvent) => {
      const dx = e.changedTouches[0].clientX - touchStartX;
      if (dx > 80) closeDetailPanel();
    },
    { passive: true },
  );

  /** Lazy-fetch full detail. */
  fetchDetail(node.id)
    .then((detail: MemoryDetail) => {
      contentArea.textContent = detail.content;
      renderMeta(metaSection, detail);
    })
    .catch(() => {
      contentArea.textContent = "Failed to load detail.";
    });
}

/** Render metadata rows into the given container. */
function renderMeta(container: HTMLElement, detail: MemoryDetail): void {
  /** Confidence bar. */
  const confRow = document.createElement("div");
  confRow.style.cssText = "display: flex; align-items: center; gap: 8px;";

  const confLabel = document.createElement("span");
  confLabel.textContent = "Confidence:";
  confRow.appendChild(confLabel);

  const confBarOuter = document.createElement("div");
  confBarOuter.style.cssText = `
    flex: 1;
    height: 8px;
    background: var(--border-subtle);
    border-radius: 4px;
    overflow: hidden;
  `;
  const confBarInner = document.createElement("div");
  const pct = Math.round(detail.confidence * 100);
  confBarInner.style.cssText = `
    width: ${pct}%;
    height: 100%;
    background: ${pct >= 80 ? "#22c55e" : pct >= 50 ? "#f59e0b" : "#ef4444"};
    border-radius: 4px;
    transition: width 0.3s ease;
  `;
  confBarOuter.appendChild(confBarInner);
  confRow.appendChild(confBarOuter);

  const confValue = document.createElement("span");
  confValue.textContent = `${pct}%`;
  confRow.appendChild(confValue);
  container.appendChild(confRow);

  /** Category. */
  appendMetaRow(container, "Category", detail.category);

  /** Timestamps. */
  appendMetaRow(container, "Created", formatTimestamp(detail.timestamp));
  if (detail.last_accessed_at) {
    appendMetaRow(container, "Last accessed", formatTimestamp(detail.last_accessed_at));
  }

  /** Access count. */
  appendMetaRow(container, "Access count", String(detail.access_count));
}

/** Append a label: value row to a container. */
function appendMetaRow(container: HTMLElement, label: string, value: string): void {
  const row = document.createElement("div");
  row.style.cssText = "display: flex; justify-content: space-between;";

  const labelEl = document.createElement("span");
  labelEl.textContent = label;
  labelEl.style.fontWeight = "500";
  row.appendChild(labelEl);

  const valueEl = document.createElement("span");
  valueEl.textContent = value;
  row.appendChild(valueEl);

  container.appendChild(row);
}

/** Format an ISO timestamp to a readable local string. */
function formatTimestamp(iso: string): string {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

/** Close and remove the active detail panel. */
export function closeDetailPanel(): void {
  if (activePanel) {
    activePanel.style.transform = "translateX(100%)";
    const panelRef = activePanel;
    setTimeout(() => panelRef.remove(), 250);
    activePanel = null;
  }
  const backdrop = document.getElementById("detail-backdrop");
  backdrop?.remove();
}
