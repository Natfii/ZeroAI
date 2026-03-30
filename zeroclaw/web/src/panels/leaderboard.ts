// Copyright (c) 2026 @Natfii. All rights reserved.

import { fetchLeaderboard } from "../api";
import type { LeaderboardRow } from "../types";

/** The currently open leaderboard panel element, if any. */
let activePanel: HTMLElement | null = null;

/** Current sort state. */
let sortColumn: keyof LeaderboardRow = "total";
let sortAsc = false;

/**
 * Open the leaderboard panel, sliding up from the bottom.
 * Fetches fresh data on each open.
 */
export function openLeaderboardPanel(): void {
  closeLeaderboardPanel();

  const panel = document.createElement("div");
  panel.className = "panel leaderboard-panel";
  panel.setAttribute("role", "dialog");
  panel.setAttribute("aria-label", "Provider leaderboard");
  panel.style.cssText = `
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    max-height: 50vh;
    z-index: 200;
    overflow-y: auto;
    padding: 0 16px 16px;
    transform: translateY(100%);
    transition: transform 0.25s ease-out;
    border-radius: 16px 16px 0 0;
  `;

  /** Drag handle at top. */
  const handleBar = document.createElement("div");
  handleBar.style.cssText = `
    display: flex;
    justify-content: center;
    padding: 12px 0 8px;
    cursor: grab;
  `;
  const handle = document.createElement("div");
  handle.style.cssText = `
    width: 40px;
    height: 4px;
    border-radius: 2px;
    background: var(--text-secondary);
    opacity: 0.5;
  `;
  handleBar.appendChild(handle);
  panel.appendChild(handleBar);

  /** Header row with title and close button. */
  const header = document.createElement("div");
  header.style.cssText = `
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
  `;

  const title = document.createElement("h3");
  title.textContent = "Provider Leaderboard";
  title.style.cssText = `
    font-size: 16px;
    font-weight: 600;
    margin: 0;
    color: var(--text-primary);
  `;
  header.appendChild(title);

  const closeBtn = document.createElement("button");
  closeBtn.className = "close-btn";
  closeBtn.setAttribute("aria-label", "Close leaderboard");
  closeBtn.textContent = "\u2715";
  closeBtn.addEventListener("click", closeLeaderboardPanel);
  header.appendChild(closeBtn);

  panel.appendChild(header);

  /** Table container (populated after fetch). */
  const tableContainer = document.createElement("div");
  tableContainer.style.cssText = "overflow-x: auto;";
  tableContainer.setAttribute("aria-live", "polite");
  tableContainer.textContent = "Loading...";
  panel.appendChild(tableContainer);

  activePanel = panel;
  document.body.appendChild(panel);

  /** Slide-in animation. */
  requestAnimationFrame(() => {
    panel.style.transform = "translateY(0)";
  });

  /** Swipe-down to dismiss. */
  let touchStartY = 0;
  panel.addEventListener(
    "touchstart",
    (e: TouchEvent) => {
      touchStartY = e.touches[0].clientY;
    },
    { passive: true },
  );
  panel.addEventListener(
    "touchend",
    (e: TouchEvent) => {
      const dy = e.changedTouches[0].clientY - touchStartY;
      if (dy > 80) closeLeaderboardPanel();
    },
    { passive: true },
  );

  /** Fetch and render data. */
  fetchLeaderboard()
    .then((rows) => {
      renderTable(tableContainer, rows);
    })
    .catch(() => {
      tableContainer.textContent = "Failed to load leaderboard.";
    });
}

/** Render the sortable leaderboard table. */
function renderTable(container: HTMLElement, rows: LeaderboardRow[]): void {
  container.textContent = "";

  const table = document.createElement("table");
  table.style.cssText = `
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
    color: var(--text-primary);
  `;

  const thead = document.createElement("thead");
  const headerRow = document.createElement("tr");

  const columns: { key: keyof LeaderboardRow; label: string }[] = [
    { key: "provider", label: "Provider" },
    { key: "model", label: "Model" },
    { key: "total", label: "Total" },
    { key: "successRate", label: "Success Rate" },
    { key: "avgLatency", label: "Avg Latency" },
  ];

  for (const col of columns) {
    const th = document.createElement("th");
    th.setAttribute("scope", "col");
    th.style.cssText = `
      text-align: left;
      padding: 8px 12px;
      border-bottom: 1px solid var(--border-subtle);
      cursor: pointer;
      user-select: none;
      white-space: nowrap;
      min-width: 48px;
      min-height: 48px;
    `;
    th.textContent = col.label;

    if (col.key === sortColumn) {
      th.setAttribute("aria-sort", sortAsc ? "ascending" : "descending");
      th.textContent = `${col.label} ${sortAsc ? "\u25B2" : "\u25BC"}`;
    }

    th.addEventListener("click", () => {
      if (sortColumn === col.key) {
        sortAsc = !sortAsc;
      } else {
        sortColumn = col.key;
        sortAsc = true;
      }
      renderTable(container, rows);
    });

    headerRow.appendChild(th);
  }
  thead.appendChild(headerRow);
  table.appendChild(thead);

  /** Sort rows. */
  const sorted = [...rows].sort((a, b) => {
    const aVal = a[sortColumn];
    const bVal = b[sortColumn];
    if (typeof aVal === "string" && typeof bVal === "string") {
      return sortAsc ? aVal.localeCompare(bVal) : bVal.localeCompare(aVal);
    }
    const numA = Number(aVal);
    const numB = Number(bVal);
    return sortAsc ? numA - numB : numB - numA;
  });

  const tbody = document.createElement("tbody");
  for (const row of sorted) {
    const tr = document.createElement("tr");
    tr.style.cssText = "border-bottom: 1px solid var(--border-subtle);";

    /** Provider. */
    const tdProvider = document.createElement("td");
    tdProvider.textContent = row.provider;
    tdProvider.style.cssText = "padding: 8px 12px;";
    tr.appendChild(tdProvider);

    /** Model. */
    const tdModel = document.createElement("td");
    tdModel.textContent = row.model;
    tdModel.style.cssText = "padding: 8px 12px;";
    tr.appendChild(tdModel);

    /** Total. */
    const tdTotal = document.createElement("td");
    tdTotal.textContent = String(row.total);
    tdTotal.style.cssText = "padding: 8px 12px;";
    tr.appendChild(tdTotal);

    /** Success rate (colored). */
    const tdRate = document.createElement("td");
    const ratePct = Math.round(row.successRate * 100);
    tdRate.textContent = `${ratePct}%`;
    let rateColor = "#ef4444";
    if (ratePct > 80) rateColor = "#22c55e";
    else if (ratePct >= 50) rateColor = "#f59e0b";
    tdRate.style.cssText = `padding: 8px 12px; color: ${rateColor}; font-weight: 600;`;
    tr.appendChild(tdRate);

    /** Avg latency. */
    const tdLatency = document.createElement("td");
    tdLatency.textContent = `${Math.round(row.avgLatency)}ms`;
    tdLatency.style.cssText = "padding: 8px 12px;";
    tr.appendChild(tdLatency);

    tbody.appendChild(tr);
  }
  table.appendChild(tbody);
  container.appendChild(table);
}

/** Close and remove the active leaderboard panel. */
export function closeLeaderboardPanel(): void {
  if (activePanel) {
    activePanel.style.transform = "translateY(100%)";
    const panelRef = activePanel;
    setTimeout(() => panelRef.remove(), 250);
    activePanel = null;
  }
}
