// Copyright (c) 2026 @Natfii. All rights reserved.

import type { FactNode, GraphResponse } from "../types";

/** Current sort state for the table view. */
let tableSortCol: keyof FactNode = "key";
let tableSortAsc = true;

/** Maximum content length before truncation. */
const CONTENT_TRUNCATE = 100;

/**
 * Initialize the accessible table view as a fallback for the force-graph.
 *
 * Renders a semantic HTML table with sortable columns. Row clicks dispatch
 * a `node:select` custom event for opening the detail panel.
 *
 * @param container - The DOM element to render the table into.
 * @param data - The graph data to display.
 */
export function initTableView(container: HTMLElement, data: GraphResponse): void {
  renderTable(container, data.nodes);
}

/**
 * Update the table with new data (e.g., after filtering or SSE update).
 *
 * @param container - The DOM element containing the table.
 * @param nodes - The filtered/updated nodes to display.
 */
export function updateTableView(container: HTMLElement, nodes: FactNode[]): void {
  renderTable(container, nodes);
}

/** Render the sortable memory facts table. */
function renderTable(container: HTMLElement, nodes: FactNode[]): void {
  container.textContent = "";

  const wrapper = document.createElement("div");
  wrapper.style.cssText = `
    overflow-x: auto;
    padding: 56px 16px 16px;
    height: 100%;
  `;

  const table = document.createElement("table");
  table.style.cssText = `
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
    color: var(--text-primary);
  `;
  table.setAttribute("role", "table");
  table.setAttribute("aria-label", "Memory facts");

  const thead = document.createElement("thead");
  const headerRow = document.createElement("tr");

  const columns: { key: keyof FactNode; label: string }[] = [
    { key: "key", label: "Key" },
    { key: "category", label: "Category" },
    { key: "source", label: "Source" },
    { key: "tags", label: "Tags" },
    { key: "access_count", label: "Access Count" },
    { key: "last_accessed_at", label: "Last Accessed" },
  ];

  for (const col of columns) {
    const th = document.createElement("th");
    th.setAttribute("scope", "col");
    th.style.cssText = `
      text-align: left;
      padding: 10px 12px;
      border-bottom: 2px solid var(--border-subtle);
      cursor: pointer;
      user-select: none;
      white-space: nowrap;
      min-width: 48px;
      min-height: 48px;
      font-weight: 600;
      color: var(--text-secondary);
    `;
    th.textContent = col.label;

    if (col.key === tableSortCol) {
      th.setAttribute("aria-sort", tableSortAsc ? "ascending" : "descending");
      th.textContent = `${col.label} ${tableSortAsc ? "\u25B2" : "\u25BC"}`;
    } else {
      th.setAttribute("aria-sort", "none");
    }

    th.addEventListener("click", () => {
      if (tableSortCol === col.key) {
        tableSortAsc = !tableSortAsc;
      } else {
        tableSortCol = col.key;
        tableSortAsc = true;
      }
      renderTable(container, nodes);
    });

    headerRow.appendChild(th);
  }
  thead.appendChild(headerRow);
  table.appendChild(thead);

  /** Sort the nodes. */
  const sorted = [...nodes].sort((a, b) => {
    const aVal = a[tableSortCol];
    const bVal = b[tableSortCol];

    /** Handle nulls. */
    if (aVal == null && bVal == null) return 0;
    if (aVal == null) return tableSortAsc ? -1 : 1;
    if (bVal == null) return tableSortAsc ? 1 : -1;

    if (typeof aVal === "string" && typeof bVal === "string") {
      return tableSortAsc ? aVal.localeCompare(bVal) : bVal.localeCompare(aVal);
    }
    return tableSortAsc
      ? Number(aVal) - Number(bVal)
      : Number(bVal) - Number(aVal);
  });

  const tbody = document.createElement("tbody");
  for (const node of sorted) {
    const tr = document.createElement("tr");
    tr.style.cssText = `
      border-bottom: 1px solid var(--border-subtle);
      cursor: pointer;
    `;
    tr.setAttribute("tabindex", "0");
    tr.setAttribute("role", "row");

    /** Row click dispatches node:select. */
    const dispatchSelect = (): void => {
      container.dispatchEvent(
        new CustomEvent("node:select", { detail: node, bubbles: true }),
      );
    };
    tr.addEventListener("click", dispatchSelect);
    tr.addEventListener("keydown", (e: KeyboardEvent) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        dispatchSelect();
      }
    });

    /** Hover highlight. */
    tr.addEventListener("mouseenter", () => {
      tr.style.background = "var(--border-subtle)";
    });
    tr.addEventListener("mouseleave", () => {
      tr.style.background = "transparent";
    });

    /** Key. */
    appendCell(tr, node.key);

    /** Category. */
    appendCell(tr, node.category);

    /** Source. */
    appendCell(tr, node.source);

    /** Tags. */
    appendCell(tr, node.tags || "");

    /** Access count. */
    appendCell(tr, String(node.access_count));

    /** Last accessed. */
    appendCell(tr, formatTimestamp(node.last_accessed_at));

    tbody.appendChild(tr);
  }
  table.appendChild(tbody);

  /** Empty state. */
  if (sorted.length === 0) {
    const emptyRow = document.createElement("tr");
    const emptyCell = document.createElement("td");
    emptyCell.setAttribute("colspan", String(columns.length));
    emptyCell.textContent = "No memory facts match your filters.";
    emptyCell.style.cssText = `
      padding: 24px 12px;
      text-align: center;
      color: var(--text-secondary);
    `;
    emptyRow.appendChild(emptyCell);
    tbody.appendChild(emptyRow);
  }

  wrapper.appendChild(table);
  container.appendChild(wrapper);
}

/** Append a table cell with text content (XSS-safe). */
function appendCell(row: HTMLTableRowElement, text: string): void {
  const td = document.createElement("td");
  td.style.cssText = "padding: 10px 12px; vertical-align: top;";
  const truncated =
    text.length > CONTENT_TRUNCATE
      ? text.slice(0, CONTENT_TRUNCATE) + "..."
      : text;
  td.textContent = truncated;
  row.appendChild(td);
}

/** Format an ISO timestamp to a readable local string. */
function formatTimestamp(iso: string | null): string {
  if (!iso) return "\u2014";
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}
