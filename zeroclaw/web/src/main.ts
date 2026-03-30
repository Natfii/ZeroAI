// Copyright (c) 2026 @Natfii. All rights reserved.

import "./style.css";

/** Whether power-save mode is active (from native bridge or CSS). */
function isPowerSave(): boolean {
  if (window.__ZERO_POWER_SAVE === true) return true;
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/** Whether a screen reader is likely active (heuristic). */
function isScreenReader(): boolean {
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

async function init(): Promise<void> {
  const app = document.getElementById("app");
  if (!app) return;

  // Auth check
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

  // Determine initial view mode
  const savedView = localStorage.getItem("brain-view-mode");
  const defaultToTable = isScreenReader() || isPowerSave();
  const viewMode = savedView ?? (defaultToTable ? "table" : "graph");

  app.dataset.viewMode = viewMode;
  app.innerHTML = `<div class="filter-bar" role="toolbar" aria-label="Memory filters">
    <span style="font-weight:600;margin-right:auto;">Memory Brain</span>
    <button id="view-toggle" class="close-btn" aria-label="Toggle view mode"
            style="font-size:14px;min-width:auto;padding:6px 12px;">
      ${viewMode === "graph" ? "Show as Table" : "Show as Graph"}
    </button>
  </div>
  <div id="graph-container" style="width:100%;height:100%;"></div>`;

  // Graph/table rendering will be added in Task 17
  console.log("[Brain] initialized, powerSave:", isPowerSave(), "view:", viewMode);
}

init();
